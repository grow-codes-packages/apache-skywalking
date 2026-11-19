//! `SkyWalkingQueryClient` against recorded OAP responses.

mod support;

use std::time::{Duration, UNIX_EPOCH};

use grow_apache_skywalking as sdk;
use sdk::core::ErrorCode;
use sdk::{
    service_id, LogOrder, Paging, QueryLogsOptions, QueryTracesOptions, Step, TimeRange,
    TopologyOptions, TraceOrder, TraceState,
};
use serde_json::json;
use support::{client, gql, gql_data, response, time_info, GqlNoVar, GqlVar};
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

fn range() -> TimeRange {
    TimeRange::default()
        .start(UNIX_EPOCH + Duration::from_secs(1_795_000_000))
        .end(UNIX_EPOCH + Duration::from_secs(1_795_003_600))
}

#[tokio::test]
async fn metadata_queries() {
    let server = MockServer::start().await;
    time_info(&server).await;
    Mock::given(method("POST"))
        .and(path("/graphql"))
        .and(support::Gql("listServices"))
        .and(GqlVar("/l", json!("NO_SUCH")))
        .respond_with(response("listServices.layer-unknown"))
        .mount(&server)
        .await;
    gql(&server, "listServices", response("listServices.all")).await;
    Mock::given(method("POST"))
        .and(path("/graphql"))
        .and(support::Gql("findService"))
        .and(GqlVar("/n", json!("missing")))
        .respond_with(response("findService.missing"))
        .mount(&server)
        .await;
    gql(&server, "findService", response("findService.ok")).await;
    Mock::given(method("POST"))
        .and(path("/graphql"))
        .and(support::Gql("findEndpoint"))
        .and(GqlVar("/k", json!("downstream")))
        .and(GqlVar("/l", json!(5)))
        .respond_with(gql_data(json!({
            "findEndpoint": support::body("listEndpoints.keyword")["data"]["findEndpointByName"]
        })))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/graphql"))
        .and(support::Gql("findEndpoint"))
        .and(GqlVar("/k", json!(null)))
        .and(GqlVar("/l", json!(100)))
        .respond_with(response("listEndpoints.ok"))
        .mount(&server)
        .await;
    gql(&server, "listInstances", response("listInstances.ok")).await;
    let q = client(&server).query().clone();

    let all = q.list_services(None, Some(range())).await.unwrap();
    assert_eq!(all.len(), 3);
    assert_eq!(all[0].name, "sw-otlp-1fddf66a");
    assert_eq!(all[0].id, service_id("sw-otlp-1fddf66a"));
    assert_eq!(all[0].layers, vec!["GENERAL"]);
    assert!(all[0].normal);
    assert!(q
        .list_services(Some("NO_SUCH"), None)
        .await
        .unwrap()
        .is_empty());

    let svc = q
        .find_service("sw-native-a-1fddf66a")
        .await
        .unwrap()
        .unwrap();
    assert!(svc.id.ends_with(".1"));
    assert!(q.find_service("missing").await.unwrap().is_none());
    assert_eq!(
        q.find_service(" ").await.unwrap_err().code(),
        ErrorCode::Validation
    );

    let eps = q
        .list_endpoints("sw-native-a-1fddf66a", None, None)
        .await
        .unwrap();
    assert!(!eps.is_empty());
    assert!(eps.iter().all(|e| !e.id.is_empty()));
    let kw = q
        .list_endpoints("sw-native-b-1fddf66a", Some("downstream"), Some(5))
        .await
        .unwrap();
    assert!(kw.iter().any(|e| e.name.contains("downstream")));
    assert_eq!(
        q.list_endpoints("s", None, Some(0))
            .await
            .unwrap_err()
            .code(),
        ErrorCode::Validation
    );
    assert_eq!(
        q.list_endpoints("", None, None).await.unwrap_err().code(),
        ErrorCode::Validation
    );

    let inst = q
        .list_instances("sw-native-a-1fddf66a", Some(range()))
        .await
        .unwrap();
    assert_eq!(inst.len(), 1);
    assert!(!inst[0].name.is_empty());
    assert!(inst[0].attributes.iter().all(|a| !a.name.is_empty()));
    let inst = q
        .list_instances("sw-native-a-1fddf66a", None)
        .await
        .unwrap();
    assert_eq!(inst.len(), 1);

    // variables actually sent
    let reqs = server.received_requests().await.unwrap();
    let bodies: Vec<serde_json::Value> = reqs
        .iter()
        .map(|r| serde_json::from_slice(&r.body).unwrap())
        .collect();
    let li = bodies
        .iter()
        .find(|b| b["query"].as_str().unwrap().contains("listInstances"))
        .unwrap();
    assert_eq!(li["variables"]["s"], service_id("sw-native-a-1fddf66a"));
    assert_eq!(
        li["variables"]["d"],
        json!({"start": "2026-11-18 1106", "end": "2026-11-18 1206", "step": "MINUTE"})
    );
}

#[tokio::test]
async fn timeinfo_capabilities_and_cache() {
    let server = MockServer::start().await;
    gql(
        &server,
        "getTimeInfo",
        gql_data(json!({"getTimeInfo": {"timezone": "+0530", "currentTimestamp": 1_795_000_000_000_i64}})),
    )
    .await;
    gql(
        &server,
        "hasQueryTracesV2Support",
        response("capabilities.ok"),
    )
    .await;
    gql(
        &server,
        "queryLogTagAutocompleteKeys",
        response("queryLogTagAutocompleteKeys.ok"),
    )
    .await;
    let q = client(&server).query().clone();
    let t = q.time_info().await.unwrap();
    assert_eq!(t.timezone, "+0530");
    assert_eq!(t.current_timestamp, 1_795_000_000_000);
    let caps = q.capabilities().await.unwrap();
    assert!(caps.query_traces_v2 && !caps.logs_by_keywords);
    q.capabilities().await.unwrap();
    let keys = q
        .list_log_tag_keys(Some(range().step(Step::Hour)))
        .await
        .unwrap();
    assert!(keys.windows(2).all(|w| w[0] <= w[1]));
    assert!(keys.contains(&"level".to_owned()));
    let reqs = server.received_requests().await.unwrap();
    let caps_calls = reqs
        .iter()
        .filter(|r| String::from_utf8_lossy(&r.body).contains("hasQueryTracesV2Support"))
        .count();
    assert_eq!(caps_calls, 1, "capabilities are cached");
    let tz_calls = reqs
        .iter()
        .filter(|r| String::from_utf8_lossy(&r.body).contains("getTimeInfo"))
        .count();
    assert_eq!(tz_calls, 1, "timezone is cached");
    let last: serde_json::Value = serde_json::from_slice(&reqs.last().unwrap().body).unwrap();
    // +05:30 shifts the window
    assert_eq!(last["variables"]["d"]["start"], "2026-11-18 16");
    assert_eq!(last["variables"]["d"]["step"], "HOUR");
}

#[tokio::test]
async fn time_info_defaults_when_fields_missing() {
    let server = MockServer::start().await;
    gql(
        &server,
        "getTimeInfo",
        gql_data(json!({"getTimeInfo": null})),
    )
    .await;
    let t = client(&server).query().time_info().await.unwrap();
    assert_eq!(t.timezone, "+0000");
    assert!(t.current_timestamp > 0);
}

#[tokio::test]
async fn native_traces() {
    let server = MockServer::start().await;
    time_info(&server).await;
    Mock::given(method("POST"))
        .and(path("/graphql"))
        .and(support::Gql("queryTraces"))
        .and(GqlVar("/c/traceState", json!("ERROR")))
        .respond_with(response("queryTraces.error-state"))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/graphql"))
        .and(support::Gql("queryTraces"))
        .and(GqlVar("/c/minTraceDuration", json!(60000)))
        .respond_with(response("queryTraces.min-duration-no-match"))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/graphql"))
        .and(support::Gql("queryTraces"))
        .and(GqlNoVar("/c/queryDuration"))
        .respond_with(response("queryTraces.by-trace-id"))
        .mount(&server)
        .await;
    gql(&server, "queryTraces", response("queryTraces.by-tag")).await;
    Mock::given(method("POST"))
        .and(path("/graphql"))
        .and(support::Gql("queryTrace("))
        .and(GqlVar("/id", json!("unknown")))
        .respond_with(response("getTrace.native-missing"))
        .mount(&server)
        .await;
    gql(&server, "queryTrace(", response("getTrace.native")).await;
    let q = client(&server).query().clone();

    let res = q
        .query_traces(
            &QueryTracesOptions::service("sw-native-a-1fddf66a")
                .tag("run_id", "1fddf66a")
                .range(range()),
        )
        .await
        .unwrap();
    assert_eq!(res.traces.len(), 1);
    assert!(res.traces[0].spans.len() >= 2);
    let errors = q
        .query_traces(&QueryTracesOptions::service("b").trace_state(TraceState::Error))
        .await
        .unwrap();
    assert!(!errors.traces.is_empty());
    let none = q
        .query_traces(&QueryTracesOptions {
            service: Some("a".into()),
            instance: Some("i".into()),
            endpoint: Some("/e".into()),
            min_duration_ms: Some(60_000),
            max_duration_ms: Some(120_000),
            order: TraceOrder::ByDuration,
            paging: Paging::new(2, 5),
            ..Default::default()
        })
        .await
        .unwrap();
    assert!(none.traces.is_empty());
    let trace_id = res.traces[0].trace_id.clone();
    let by_id = q
        .query_traces(&QueryTracesOptions {
            trace_id: Some(trace_id.clone()),
            ..Default::default()
        })
        .await
        .unwrap();
    assert_eq!(by_id.traces[0].trace_id, trace_id);
    for bad in [
        QueryTracesOptions {
            instance: Some("i".into()),
            ..Default::default()
        },
        QueryTracesOptions {
            paging: Paging::new(0, 1),
            ..Default::default()
        },
        QueryTracesOptions {
            paging: Paging::new(1, 10_001),
            ..Default::default()
        },
        QueryTracesOptions {
            trace_id: Some(" ".into()),
            ..Default::default()
        },
    ] {
        assert_eq!(
            q.query_traces(&bad).await.unwrap_err().code(),
            ErrorCode::Validation
        );
    }
    // the request carries derived ids and the condition fields
    let reqs = server.received_requests().await.unwrap();
    let cond = reqs
        .iter()
        .map(|r| serde_json::from_slice::<serde_json::Value>(&r.body).unwrap())
        .find(|b| b["variables"]["c"]["queryOrder"] == "BY_DURATION")
        .unwrap();
    let c = &cond["variables"]["c"];
    assert_eq!(c["serviceId"], service_id("a"));
    assert_eq!(c["serviceInstanceId"], sdk::instance_id("a", "i"));
    assert_eq!(c["endpointId"], sdk::endpoint_id("a", "/e"));
    assert_eq!(c["paging"], json!({"pageNum": 2, "pageSize": 5}));
    assert_eq!(c["maxTraceDuration"], 120_000);

    let t = q
        .get_trace("1fddf66a1c64db2c1345419627769f1f.1.1795036241775")
        .await
        .unwrap()
        .unwrap();
    assert_eq!(
        t.trace_id,
        "1fddf66a1c64db2c1345419627769f1f.1.1795036241775"
    );
    let b = t.spans.iter().find(|s| !s.refs.is_empty()).unwrap();
    assert_eq!(b.refs[0].ref_type, "CROSS_PROCESS");
    assert!(q.get_trace("unknown").await.unwrap().is_none());
    assert_eq!(
        q.get_trace("").await.unwrap_err().code(),
        ErrorCode::Validation
    );
}

#[tokio::test]
async fn retrieved_time_range_and_unsupported() {
    let server = MockServer::start().await;
    time_info(&server).await;
    gql(
        &server,
        "queryTraces",
        gql_data(json!({"queryTraces": {"traces": [{"spans": []}], "retrievedTimeRange": {"startTime": 1, "endTime": 2}}})),
    )
    .await;
    let q = client(&server).query().clone();
    let r = q
        .query_traces(&QueryTracesOptions::default())
        .await
        .unwrap();
    assert!(r.traces.is_empty());
    assert_eq!(r.retrieved_time_range, Some((1, 2)));
    server.reset().await;
    time_info(&server).await;
    gql(
        &server,
        "queryTraces",
        response("queryBasicTraces.unsupported"),
    )
    .await;
    let err = q
        .query_traces(&QueryTracesOptions::default())
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Unsupported);
    assert!(err.message().starts_with("query.queryTraces: "));
}

#[tokio::test]
async fn logs_queries() {
    let server = MockServer::start().await;
    time_info(&server).await;
    gql(
        &server,
        "hasQueryTracesV2Support",
        response("capabilities.ok"),
    )
    .await;
    Mock::given(method("POST"))
        .and(path("/graphql"))
        .and(support::Gql("queryLogs"))
        .and(GqlNoVar("/c/queryDuration"))
        .respond_with(response("queryLogs.otel-trace-via-native"))
        .mount(&server)
        .await;
    gql(&server, "queryLogs", response("queryLogs.by-service")).await;
    let q = client(&server).query().clone();
    let res = q
        .query_logs(&QueryLogsOptions::service("sw-logs-1fddf66a").range(range()))
        .await
        .unwrap();
    assert!(!res.logs.is_empty());
    let first = &res.logs[0];
    assert!(!first.service_name.is_empty());
    assert!(first.timestamp > 0);
    assert!(first.tag("level").is_some());
    assert!(first.tag("nope").is_none());
    let contents: Vec<String> = res.logs.iter().map(|l| l.content.clone()).collect();

    // keywords are filtered client side on BanyanDB
    let word = contents[0]
        .split_whitespace()
        .next()
        .unwrap()
        .to_uppercase();
    let filtered = q
        .query_logs(&QueryLogsOptions {
            service: Some("sw-logs-1fddf66a".into()),
            keywords: vec![word.clone()],
            excluding_keywords: vec!["zzz-never".into()],
            ..Default::default()
        })
        .await
        .unwrap();
    assert!(!filtered.logs.is_empty());
    assert!(filtered
        .logs
        .iter()
        .all(|l| l.content.to_uppercase().contains(&word)));
    let excluded = q
        .query_logs(&QueryLogsOptions {
            excluding_keywords: vec![word.clone()],
            ..Default::default()
        })
        .await
        .unwrap();
    assert!(excluded
        .logs
        .iter()
        .all(|l| !l.content.to_uppercase().contains(&word)));

    let related = q
        .query_logs(&QueryLogsOptions {
            trace_id: Some("4bf92f3577b34da6a3ce929d0e0e4736".into()),
            segment_id: Some("seg".into()),
            span_id: Some(1),
            order: LogOrder::Asc,
            ..QueryLogsOptions::default().tag("level", "ERROR")
        })
        .await
        .unwrap();
    assert!(!related.logs.is_empty());
    let reqs = server.received_requests().await.unwrap();
    let body: serde_json::Value = serde_json::from_slice(&reqs.last().unwrap().body).unwrap();
    let c = &body["variables"]["c"];
    assert_eq!(
        c["relatedTrace"],
        json!({"traceId": "4bf92f3577b34da6a3ce929d0e0e4736", "segmentId": "seg", "spanId": 1})
    );
    assert_eq!(c["tags"], json!([{"key": "level", "value": "ERROR"}]));
    assert_eq!(c["queryOrder"], "ASC");
    assert!(c.get("keywordsOfContent").is_none());
    let _ = QueryLogsOptions::trace_id("x");
    assert_eq!(
        q.query_logs(&QueryLogsOptions {
            endpoint: Some("/e".into()),
            ..Default::default()
        })
        .await
        .unwrap_err()
        .code(),
        ErrorCode::Validation
    );
}

#[tokio::test]
async fn logs_keywords_sent_when_supported() {
    let server = MockServer::start().await;
    time_info(&server).await;
    gql(
        &server,
        "hasQueryTracesV2Support",
        gql_data(json!({"hasQueryTracesV2Support": true, "supportQueryLogsByKeywords": true})),
    )
    .await;
    gql(
        &server,
        "queryLogs",
        gql_data(json!({"queryLogs": {"errorReason": "partial", "logs": [{"content": "anything", "timestamp": 5}]}})),
    )
    .await;
    let q = client(&server).query().clone();
    let res = q
        .query_logs(&QueryLogsOptions {
            keywords: vec!["x".into()],
            excluding_keywords: vec!["y".into()],
            ..Default::default()
        })
        .await
        .unwrap();
    assert_eq!(
        res.logs.len(),
        1,
        "no client-side filter when the server filters"
    );
    assert_eq!(res.error_reason.as_deref(), Some("partial"));
    let reqs = server.received_requests().await.unwrap();
    let body: serde_json::Value = serde_json::from_slice(&reqs.last().unwrap().body).unwrap();
    assert_eq!(body["variables"]["c"]["keywordsOfContent"], json!(["x"]));
    assert_eq!(
        body["variables"]["c"]["excludingKeywordsOfContent"],
        json!(["y"])
    );
}

#[tokio::test]
async fn topology_queries() {
    let server = MockServer::start().await;
    time_info(&server).await;
    gql(&server, "getGlobalTopology", response("topology.global")).await;
    gql(&server, "getServiceTopology", response("topology.service")).await;
    let q = client(&server).query().clone();
    let global = q
        .topology(Some(range()), &TopologyOptions::default())
        .await
        .unwrap();
    assert!(global.nodes.iter().any(|n| n.name == "User" && !n.is_real));
    assert!(!global.calls.is_empty());
    assert!(!global.calls[0].detect_points.is_empty());
    let svc = q
        .topology(
            None,
            &TopologyOptions {
                service: Some("sw-native-a-1fddf66a".into()),
                layer: None,
            },
        )
        .await
        .unwrap();
    assert!(!svc.nodes.is_empty());
    let layered = q
        .topology(
            None,
            &TopologyOptions {
                service: None,
                layer: Some("GENERAL".into()),
            },
        )
        .await
        .unwrap();
    assert_eq!(layered, global);
    let reqs = server.received_requests().await.unwrap();
    let body: serde_json::Value = serde_json::from_slice(&reqs.last().unwrap().body).unwrap();
    assert_eq!(body["variables"]["l"], "GENERAL");
    let err = q
        .topology(
            Some(
                TimeRange::default()
                    .start(UNIX_EPOCH + Duration::from_secs(10))
                    .end(UNIX_EPOCH),
            ),
            &TopologyOptions::default(),
        )
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
}

#[tokio::test]
async fn timezone_failure_is_not_cached() {
    let server = MockServer::start().await;
    Mock::given(path("/graphql"))
        .respond_with(
            ResponseTemplate::new(400).set_body_json(json!({"errors": [{"message": "bad"}]})),
        )
        .up_to_n_times(1)
        .mount(&server)
        .await;
    time_info(&server).await;
    gql(
        &server,
        "listInstances",
        gql_data(json!({"listInstances": []})),
    )
    .await;
    let q = client(&server).query().clone();
    assert_eq!(
        q.list_instances("a", None).await.unwrap_err().code(),
        ErrorCode::Validation
    );
    assert!(q.list_instances("a", None).await.unwrap().is_empty());
}
