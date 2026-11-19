// Taken over from sdk-kit 0.1.0: the blocking SkyWalking client against mocks of OAP.
//! Blocking client (feature `blocking`): every wrapper method once, plus background batching.
#![cfg(feature = "blocking")]

mod support;

use std::time::Duration;

use grow_apache_skywalking as sdk;
use sdk::blocking::SkyWalkingClient as Client;
use sdk::core::ErrorCode;
use sdk::logging::{BatchOptions, LogClient, SearchOptions, WaitOptions};
use sdk::tracing::{DependencyOptions, TraceSearchQuery, WaitForTraceOptions};
use sdk::{
    QueryLogsOptions, QueryTracesOptions, SkyWalkingConfig as Config, SkyWalkingLogClientOptions,
    TopologyOptions, TraceBackend,
};
use serde_json::json;
use support::{builder, gql, gql_data, response, time_info};
use wiremock::matchers::path;
use wiremock::{Mock, MockServer, ResponseTemplate};

const NATIVE_ID: &str = "1fddf66a1c64db2c1345419627769f1f.1.1795036241775";

#[test]
fn blocking_client() {
    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(1)
        .enable_all()
        .build()
        .unwrap();
    let server = rt.block_on(async {
        let server = MockServer::start().await;
        time_info(&server).await;
        Mock::given(path("/healthcheck"))
            .respond_with(response("health.http"))
            .mount(&server)
            .await;
        Mock::given(path("/v3/logs"))
            .respond_with(ResponseTemplate::new(200))
            .mount(&server)
            .await;
        Mock::given(path("/zipkin/api/v2/services"))
            .respond_with(response("listServices.zipkin"))
            .mount(&server)
            .await;
        Mock::given(path("/zipkin/api/v2/spans"))
            .respond_with(response("listOperations.zipkin"))
            .mount(&server)
            .await;
        Mock::given(path("/zipkin/api/v2/traces"))
            .respond_with(response("searchTraces.by-tag"))
            .mount(&server)
            .await;
        gql(&server, "{version}", response("serverVersion.ok")).await;
        gql(&server, "hasQueryTracesV2Support", response("capabilities.ok")).await;
        gql(&server, "listServices", response("listServices.all")).await;
        gql(&server, "findService", response("findService.ok")).await;
        gql(&server, "findEndpoint", response("listEndpoints.ok")).await;
        gql(&server, "listInstances", response("listInstances.ok")).await;
        gql(&server, "queryTraces", response("queryTraces.by-tag")).await;
        gql(&server, "queryTrace(", response("getTrace.native")).await;
        let now = sdk::epoch_ms(std::time::SystemTime::now());
        gql(
            &server,
            "queryLogs",
            gql_data(json!({"queryLogs": {"logs": [{"serviceName": "svc", "content": "hi", "timestamp": now - 1000}]}})),
        )
        .await;
        gql(&server, "queryLogTagAutocompleteKeys", response("queryLogTagAutocompleteKeys.ok")).await;
        gql(&server, "getGlobalTopology", response("topology.global")).await;
        server
    });

    let c = Client::new(builder(&server).build().unwrap()).unwrap();
    assert!(c.health().unwrap().healthy);
    c.ping().unwrap();
    assert_eq!(c.server_version().unwrap(), "10.4.0-b272da3");
    assert!(c
        .graphql("{version}", json!({}))
        .unwrap()
        .field("version")
        .is_some());
    assert_eq!(c.config().trace_backend(), TraceBackend::Auto);
    assert!(!c.async_client().is_closed());
    assert_eq!(c.block_on(async { 7 }), 7);

    let q = c.query();
    assert!(q
        .graphql("{version}", json!(null))
        .unwrap()
        .errors
        .is_empty());
    assert_eq!(q.time_info().unwrap().timezone, "+0000");
    assert!(q.capabilities().unwrap().query_traces_v2);
    assert_eq!(q.list_services(None, None).unwrap().len(), 3);
    assert!(q.find_service("sw-native-a-1fddf66a").unwrap().is_some());
    assert!(!q.list_endpoints("a", None, None).unwrap().is_empty());
    assert!(!q.list_instances("a", None).unwrap().is_empty());
    assert!(!q
        .query_traces(&QueryTracesOptions::service("a"))
        .unwrap()
        .traces
        .is_empty());
    assert!(q.get_trace(NATIVE_ID).unwrap().is_some());
    assert!(!q
        .query_logs(&QueryLogsOptions::service("a"))
        .unwrap()
        .logs
        .is_empty());
    assert!(!q.list_log_tag_keys(None).unwrap().is_empty());
    assert!(!q
        .topology(None, &TopologyOptions::default())
        .unwrap()
        .nodes
        .is_empty());

    let t = c.traces();
    assert!(t.get_trace(NATIVE_ID).unwrap().is_some());
    assert!(!t
        .search_traces(&TraceSearchQuery::default())
        .unwrap()
        .is_empty());
    assert!(!t.list_services().unwrap().is_empty());
    assert!(!t.list_operations("sw-otlp-1fddf66a").unwrap().is_empty());
    assert!(!t
        .get_dependencies(DependencyOptions::default())
        .unwrap()
        .is_empty());
    let opts = WaitForTraceOptions::default().timeout(Duration::from_secs(2));
    assert_eq!(
        t.wait_for_trace(NATIVE_ID, opts).unwrap().trace_id,
        NATIVE_ID
    );

    let l = c.logs();
    assert!(!l
        .search("", SearchOptions::default())
        .unwrap()
        .records
        .is_empty());
    l.wait_for_log("", WaitOptions::default().timeout(Duration::from_secs(2)))
        .unwrap();
    assert!(!l.list_streams().unwrap().is_empty());
    assert!(!l.list_labels().unwrap().is_empty());

    // batching keeps running on the client's runtime between calls
    let logs = c
        .log_client(
            SkyWalkingLogClientOptions::service("svc")
                .batch(BatchOptions::default().flush_interval(Duration::from_millis(20))),
        )
        .unwrap();
    logs.send("background".into());
    for _ in 0..200 {
        if logs.stats().sent == 1 {
            break;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    assert_eq!(logs.stats().sent, 1);
    let handle = c
        .init_tracing(sdk::tracing_config("svc").with_set_global(false))
        .unwrap();

    c.close();
    c.close();
    assert!(handle.is_shutdown());
    assert_eq!(c.ping().unwrap_err().code(), ErrorCode::Cancelled);
    let _ = Client::from_env();
    let bad = Config::builder()
        .ca_cert_path("/nonexistent")
        .build()
        .unwrap();
    assert_eq!(Client::new(bad).unwrap_err().code(), ErrorCode::Config);
    drop(c);
    drop(rt);
}
