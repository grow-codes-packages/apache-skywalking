//! `SkyWalkingTraceQueryClient` (SPEC §5) over the Zipkin and native stores, against fixtures.

mod support;

use std::time::{Duration, SystemTime, UNIX_EPOCH};

use grow_apache_skywalking as sdk;
use sdk::core::ErrorCode;
use sdk::tracing::{
    DependencyOptions, StatusCode, TraceQueryClient, TraceSearchQuery, WaitForTraceOptions,
};
use sdk::{SkyWalkingClient, TraceBackend};
use serde_json::json;
use support::{builder, client, gql, gql_data, response, time_info};
use wiremock::matchers::{method, path, query_param, query_param_is_missing};
use wiremock::{Mock, MockServer, ResponseTemplate};

const OTLP_ID: &str = "055ffba8ecc69a64950538fda698a308";
const NATIVE_ID: &str = "1fddf66a1c64db2c1345419627769f1f.1.1795036241775";

fn with_backend(server: &MockServer, backend: TraceBackend) -> SkyWalkingClient {
    SkyWalkingClient::new(builder(server).trace_backend(backend).build().unwrap()).unwrap()
}

async fn mount_traces(server: &MockServer) {
    time_info(server).await;
    Mock::given(path(format!("/zipkin/api/v2/trace/{OTLP_ID}")))
        .respond_with(response("getTrace.zipkin"))
        .mount(server)
        .await;
    Mock::given(path(
        "/zipkin/api/v2/trace/0000000000000000000000000000beef",
    ))
    .respond_with(response("getTrace.zipkin-404"))
    .mount(server)
    .await;
    gql(server, "queryTrace(", response("getTrace.native")).await;
}

#[tokio::test]
async fn get_trace_backends() {
    let server = MockServer::start().await;
    mount_traces(&server).await;
    let c = client(&server);
    let t = c.traces();
    assert_eq!(t.backend(), TraceBackend::Auto);

    let trace = t.get_trace(&OTLP_ID.to_uppercase()).await.unwrap().unwrap();
    assert_eq!(trace.trace_id, OTLP_ID);
    assert_eq!(trace.spans.len(), 2);
    let root = trace
        .spans
        .iter()
        .find(|s| s.parent_span_id.is_none())
        .unwrap();
    let child = trace
        .spans
        .iter()
        .find(|s| s.parent_span_id.is_some())
        .unwrap();
    assert_eq!(root.name, "get /checkout");
    assert_eq!(root.service_name, "sw-otlp-1fddf66a");
    assert_eq!(root.status.code, StatusCode::Ok);
    assert_eq!(root.attributes["retries"], "2");
    assert!(!root.attributes.contains_key("otel.status_code"));
    assert_eq!(root.events[0].name, "cache.miss");
    assert_eq!(root.events[0].attributes["k"], "v");
    assert_eq!(child.parent_span_id.as_deref(), Some(root.span_id.as_str()));

    // auto: hex id unknown to Zipkin → native lookup (empty here)
    let missing = t.get_trace("0000000000000000000000000000beef").await;
    // the native fixture answers any id, so the fallback returns the native trace
    assert_eq!(missing.unwrap().unwrap().trace_id, NATIVE_ID);

    let native = t.get_trace(NATIVE_ID).await.unwrap().unwrap();
    assert_eq!(native.trace_id, NATIVE_ID);
    let a_exit = native
        .spans
        .iter()
        .find(|s| s.kind == sdk::tracing::SpanKind::Client)
        .unwrap();
    let b = native
        .spans
        .iter()
        .find(|s| s.service_name.contains("native-b"))
        .unwrap();
    assert_eq!(b.parent_span_id.as_deref(), Some(a_exit.span_id.as_str()));
    assert!(a_exit.span_id.ends_with("-1"));

    let zipkin = with_backend(&server, TraceBackend::Zipkin);
    assert!(zipkin
        .traces()
        .get_trace("0000000000000000000000000000beef")
        .await
        .unwrap()
        .is_none());
    assert_eq!(
        zipkin
            .traces()
            .get_trace("not-hex")
            .await
            .unwrap_err()
            .code(),
        ErrorCode::Validation
    );
    assert_eq!(
        zipkin
            .traces()
            .get_trace("00000000000000000000000000000000")
            .await
            .unwrap_err()
            .code(),
        ErrorCode::Validation
    );
    let native_only = with_backend(&server, TraceBackend::Native);
    assert_eq!(
        native_only
            .traces()
            .get_trace(OTLP_ID)
            .await
            .unwrap()
            .unwrap()
            .trace_id,
        NATIVE_ID
    );
    assert_eq!(
        t.get_trace("  ").await.unwrap_err().code(),
        ErrorCode::Validation
    );
    assert_eq!(
        t.with_backend(TraceBackend::Native).backend(),
        TraceBackend::Native
    );
}

#[tokio::test]
async fn get_trace_auto_fallback_to_empty_native() {
    let server = MockServer::start().await;
    Mock::given(path(
        "/zipkin/api/v2/trace/0000000000000000000000000000beef",
    ))
    .respond_with(response("getTrace.zipkin-404"))
    .mount(&server)
    .await;
    gql(&server, "queryTrace(", response("getTrace.native-missing")).await;
    let c = client(&server);
    assert!(c
        .traces()
        .get_trace("0000000000000000000000000000beef")
        .await
        .unwrap()
        .is_none());
}

#[tokio::test]
async fn search_traces_zipkin() {
    let server = MockServer::start().await;
    Mock::given(path("/zipkin/api/v2/traces"))
        .and(query_param("serviceName", "sw-otlp-1fddf66a"))
        .and(query_param("spanName", "get /checkout"))
        .and(query_param(
            "annotationQuery",
            "http.method=GET and run_id=1fddf66a",
        ))
        .and(query_param("minDuration", "1000"))
        .and(query_param("maxDuration", "9000000"))
        .and(query_param("endTs", "1795036300000"))
        .and(query_param("lookback", "600000"))
        .and(query_param("limit", "5"))
        .respond_with(response("searchTraces.by-service"))
        .mount(&server)
        .await;
    Mock::given(path("/zipkin/api/v2/traces"))
        .and(query_param_is_missing("lookback"))
        .and(query_param("limit", "20"))
        .respond_with(response("searchTraces.by-tag"))
        .mount(&server)
        .await;
    let c = client(&server);
    let end = UNIX_EPOCH + Duration::from_millis(1_795_036_300_000);
    let q = TraceSearchQuery {
        service: Some("sw-otlp-1fddf66a".into()),
        operation: Some("GET /checkout".into()),
        min_duration: Some(Duration::from_millis(1)),
        max_duration: Some(Duration::from_secs(9)),
        start: Some(end - Duration::from_secs(600)),
        end: Some(end),
        limit: 5,
        ..TraceSearchQuery::default()
    }
    .tag("run_id", "1fddf66a")
    .tag("http.method", "GET");
    let hits = c.traces().search_traces(&q).await.unwrap();
    assert!(!hits.is_empty());
    assert!(hits
        .iter()
        .all(|h| h.span_count >= 1 && h.root_name.is_some()));
    let tagged = c
        .traces()
        .search_traces(&TraceSearchQuery::default().tag("run_id", "1fddf66a"))
        .await
        .unwrap();
    assert!(!tagged.is_empty());

    for bad in [
        TraceSearchQuery::default().limit(0),
        TraceSearchQuery::default().tag("a", "x and y"),
        TraceSearchQuery {
            start: Some(SystemTime::now() + Duration::from_secs(60)),
            ..Default::default()
        },
        TraceSearchQuery {
            end: Some(UNIX_EPOCH),
            ..Default::default()
        },
    ] {
        assert_eq!(
            c.traces().search_traces(&bad).await.unwrap_err().code(),
            ErrorCode::Validation
        );
    }
}

#[tokio::test]
async fn search_traces_native() {
    let server = MockServer::start().await;
    time_info(&server).await;
    gql(&server, "queryTraces", response("queryTraces.by-service")).await;
    let c = with_backend(&server, TraceBackend::Native);
    let hits = c
        .traces()
        .search_traces(&TraceSearchQuery {
            service: Some("sw-native-a-1fddf66a".into()),
            operation: Some("/native/entry".into()),
            min_duration: Some(Duration::from_micros(1500)),
            max_duration: Some(Duration::from_micros(2500)),
            ..Default::default()
        })
        .await
        .unwrap();
    assert!(!hits.is_empty());
    assert!(hits[0].trace_id.contains('.'));
    let reqs = server.received_requests().await.unwrap();
    let body: serde_json::Value = serde_json::from_slice(&reqs.last().unwrap().body).unwrap();
    let cond = &body["variables"]["c"];
    assert_eq!(cond["minTraceDuration"], 1);
    assert_eq!(cond["maxTraceDuration"], 3);
    assert_eq!(cond["paging"]["pageSize"], 20);
    assert!(cond["endpointId"].as_str().unwrap().contains('_'));
    let err = c
        .traces()
        .search_traces(&TraceSearchQuery::default().operation("/x"))
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
}

async fn mount_names(server: &MockServer) {
    Mock::given(path("/zipkin/api/v2/services"))
        .respond_with(response("listServices.zipkin"))
        .mount(server)
        .await;
    Mock::given(path("/zipkin/api/v2/spans"))
        .respond_with(response("listOperations.zipkin"))
        .mount(server)
        .await;
    gql(
        server,
        "listServices",
        gql_data(json!({"listServices": [
            {"id": "x.1", "name": "sw-native-a-1fddf66a", "normal": true},
            {"id": "y.0", "name": "virtual-db", "normal": false},
            {"id": "z.1", "name": "sw-otlp-1fddf66a"}
        ]})),
    )
    .await;
    gql(server, "findEndpoint", response("listEndpoints.ok")).await;
}

#[tokio::test]
async fn list_services_and_operations() {
    let server = MockServer::start().await;
    mount_names(&server).await;
    let c = client(&server);
    let services = c.traces().list_services().await.unwrap();
    assert!(services.contains(&"sw-otlp-1fddf66a".to_owned()));
    assert!(services.contains(&"sw-native-a-1fddf66a".to_owned()));
    assert!(!services.contains(&"virtual-db".to_owned()));
    assert!(
        services.windows(2).all(|w| w[0] < w[1]),
        "sorted and unique"
    );
    let ops = c
        .traces()
        .list_operations("sw-otlp-1fddf66a")
        .await
        .unwrap();
    assert!(ops.contains(&"get /checkout".to_owned()));
    assert!(ops.contains(&"/native/entry".to_owned()));
    assert_eq!(
        c.traces().list_operations(" ").await.unwrap_err().code(),
        ErrorCode::Validation
    );

    let zipkin = with_backend(&server, TraceBackend::Zipkin);
    assert!(!zipkin
        .traces()
        .list_services()
        .await
        .unwrap()
        .contains(&"sw-native-a-1fddf66a".to_owned()));
    assert!(!zipkin
        .traces()
        .list_operations("s")
        .await
        .unwrap()
        .contains(&"/native/entry".to_owned()));
    let native = with_backend(&server, TraceBackend::Native);
    assert_eq!(
        native.traces().list_services().await.unwrap(),
        vec!["sw-native-a-1fddf66a", "sw-otlp-1fddf66a"]
    );
    assert_eq!(
        native.traces().list_operations("s").await.unwrap(),
        vec!["/native/entry"]
    );
}

#[tokio::test]
async fn dependencies() {
    let server = MockServer::start().await;
    time_info(&server).await;
    gql(&server, "getGlobalTopology", response("topology.global")).await;
    let c = client(&server);
    let deps = c
        .traces()
        .get_dependencies(DependencyOptions {
            start: Some(SystemTime::now() - Duration::from_secs(900)),
            end: None,
        })
        .await
        .unwrap();
    assert!(!deps.is_empty());
    assert!(deps.iter().all(|d| d.call_count == 0));
    assert!(deps
        .windows(2)
        .all(|w| (&w[0].parent, &w[0].child) <= (&w[1].parent, &w[1].child)));
    let err = with_backend(&server, TraceBackend::Zipkin)
        .traces()
        .get_dependencies(DependencyOptions::default())
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Unsupported);
}

#[tokio::test]
async fn wait_for_trace_native_and_hex() {
    let server = MockServer::start().await;
    mount_traces(&server).await;
    let c = client(&server);
    let opts = WaitForTraceOptions::default()
        .timeout(Duration::from_secs(2))
        .interval(Duration::from_millis(10));
    let t = c.traces().wait_for_trace(NATIVE_ID, opts).await.unwrap();
    assert_eq!(t.trace_id, NATIVE_ID);
    let t = c.traces().wait_for_trace(OTLP_ID, opts).await.unwrap();
    assert_eq!(t.spans.len(), 2);
    let err = c
        .traces()
        .wait_for_trace(
            NATIVE_ID,
            opts.min_spans(99).timeout(Duration::from_millis(50)),
        )
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Timeout);
}

#[tokio::test]
async fn zipkin_query_errors_map() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/zipkin/api/v2/services"))
        .respond_with(ResponseTemplate::new(400).set_body_raw("bad request", "text/plain"))
        .mount(&server)
        .await;
    gql(&server, "listServices", response("listServices.all")).await;
    let err = client(&server).traces().list_services().await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
}
