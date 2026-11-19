//! Native log exporter, record mapping, query syntax and the `LogQueryClient` paging.

mod support;

use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use grow_apache_skywalking as sdk;
use sdk::core::ErrorCode;
use sdk::logging::{
    attrs, LogClient, LogQueryClient, LogRecord, SearchOptions, Severity, SortOrder, TailOptions,
    WaitOptions,
};
use sdk::{
    log_to_record, matches_text, parse_log_query, record_to_log_data, tag_text, KeyValue,
    NativeLog, SkyWalkingLogClientOptions,
};
use serde_json::{json, Value};
use support::{client, gql, gql_data, time_info};
use wiremock::matchers::path;
use wiremock::{Mock, MockServer, Request, ResponseTemplate};

fn log(content: &str, ts: i64, tags: &[(&str, &str)]) -> Value {
    json!({
        "serviceName": "svc", "serviceId": "c3Zj.1", "serviceInstanceName": "", "endpointName": "",
        "traceId": "", "timestamp": ts, "contentType": "TEXT", "content": content,
        "tags": tags.iter().map(|(k, v)| json!({"key": k, "value": v})).collect::<Vec<_>>(),
    })
}

#[test]
fn record_mapping_both_ways() {
    let mut r = LogRecord::new(Severity::Error, "boom")
        .with_attribute("n", 1)
        .with_attribute("nested", json!({"a": [1, 2]}))
        .with_attribute("level", "ignored")
        .with_trace("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7");
    r.resource.insert("service.name".into(), "svc".into());
    r.resource
        .insert("service.instance.id".into(), "i-1".into());
    r.timestamp = UNIX_EPOCH + Duration::from_millis(1_795_000_000_123);
    let data = record_to_log_data(&r, "fallback");
    assert_eq!(data["timestamp"], 1_795_000_000_123_i64);
    assert_eq!(data["service"], "svc");
    assert_eq!(data["serviceInstance"], "i-1");
    assert_eq!(data["layer"], "GENERAL");
    assert_eq!(
        data["traceContext"]["traceId"],
        "4bf92f3577b34da6a3ce929d0e0e4736"
    );
    let tags = data["tags"]["data"].as_array().unwrap();
    assert_eq!(tags[0], json!({"key": "level", "value": "ERROR"}));
    assert!(tags.contains(&json!({"key": "n", "value": "1"})));
    assert!(tags.contains(&json!({"key": "nested", "value": "{\"a\":[1,2]}"})));
    assert!(tags.contains(&json!({"key": "span_id", "value": "00f067aa0ba902b7"})));
    assert_eq!(tags.iter().filter(|t| t["key"] == "level").count(), 1);

    let mut bare = LogRecord::new(Severity::Warn, "x");
    bare.severity_text = String::new();
    let data = record_to_log_data(&bare, "fallback");
    assert_eq!(data["service"], "fallback");
    assert_eq!(data["tags"]["data"][0]["value"], "WARN");
    assert!(data.get("traceContext").is_none() && data.get("serviceInstance").is_none());
    assert_eq!(tag_text(&json!("s")), "s");
    assert_eq!(tag_text(&json!(true)), "true");

    let native = NativeLog {
        service_name: "svc".into(),
        service_instance_name: "i-1".into(),
        endpoint_name: "/e".into(),
        trace_id: "4BF92F3577B34DA6A3CE929D0E0E4736".into(),
        timestamp: 1_795_000_000_123,
        content: "boom".into(),
        tags: vec![
            KeyValue::new("level", "warn"),
            KeyValue::new("span_id", "00f067aa0ba902b7"),
            KeyValue::new("run", "r1"),
        ],
        ..Default::default()
    };
    let back = log_to_record(&native);
    assert_eq!(back.severity, Severity::Warn);
    assert_eq!(back.severity_text, "warn");
    assert_eq!(
        back.trace_id.as_deref(),
        Some("4bf92f3577b34da6a3ce929d0e0e4736")
    );
    assert_eq!(back.span_id.as_deref(), Some("00f067aa0ba902b7"));
    assert_eq!(back.attributes["run"], "r1");
    assert_eq!(back.resource["service.instance.id"], "i-1");
    assert_eq!(back.resource["sw.endpoint"], "/e");
    assert_eq!(
        back.timestamp,
        UNIX_EPOCH + Duration::from_millis(1_795_000_000_123)
    );

    let native_trace = NativeLog {
        trace_id: "abc.1.2".into(),
        tags: vec![
            KeyValue::new("level", "nonsense"),
            KeyValue::new("span_id", "7"),
        ],
        ..Default::default()
    };
    let back = log_to_record(&native_trace);
    assert_eq!(back.severity, Severity::Info);
    assert!(back.trace_id.is_none() && back.span_id.is_none());
    assert_eq!(back.attributes["sw.trace_id"], "abc.1.2");
    assert_eq!(back.attributes["span_id"], "7");
    let plain = log_to_record(&NativeLog::default());
    assert_eq!(plain.severity_text, "INFO");
    assert!(plain.attributes.is_empty());
    assert!(!plain.resource.contains_key("sw.endpoint"));
}

#[test]
fn query_syntax() {
    let p = parse_log_query(
        r#"service=checkout instance="i 1" endpoint=/pay trace_id=abc level=ERROR "payment failed" Timeout"#,
    );
    assert_eq!(p.service.as_deref(), Some("checkout"));
    assert_eq!(p.instance.as_deref(), Some("i 1"));
    assert_eq!(p.endpoint.as_deref(), Some("/pay"));
    assert_eq!(p.trace_id.as_deref(), Some("abc"));
    assert_eq!(p.tags["level"], "ERROR");
    assert_eq!(p.text, vec!["payment failed", "Timeout"]);
    assert_eq!(parse_log_query("traceId=x").trace_id.as_deref(), Some("x"));
    assert_eq!(parse_log_query(r#""""#).text, Vec::<String>::new());
    assert_eq!(parse_log_query("").text, Vec::<String>::new());

    let l: NativeLog = NativeLog {
        content: "Payment FAILED".into(),
        tags: vec![KeyValue::new("run", "R-77")],
        ..Default::default()
    };
    assert!(matches_text(&l, &[]));
    assert!(matches_text(&l, &["payment".into(), "r-77".into()]));
    assert!(!matches_text(&l, &["missing".into()]));
}

/// Serves `total` synthetic logs (newest first), paged by the request's `paging`.
fn paged_logs(total: usize, base_ms: i64) -> impl Fn(&Request) -> ResponseTemplate {
    move |req: &Request| {
        let body: Value = serde_json::from_slice(&req.body).unwrap();
        let c = &body["variables"]["c"];
        let num = c["paging"]["pageNum"].as_u64().unwrap() as usize;
        let size = c["paging"]["pageSize"].as_u64().unwrap() as usize;
        let logs: Vec<Value> = (0..total)
            .skip((num - 1) * size)
            .take(size)
            .map(|i| {
                let word = if i % 3 == 0 { "needle" } else { "hay" };
                log(
                    &format!("{word} {i}"),
                    base_ms - i as i64,
                    &[("level", "INFO")],
                )
            })
            .collect();
        ResponseTemplate::new(200)
            .set_body_json(json!({"data": {"queryLogs": {"errorReason": null, "logs": logs}}}))
    }
}

#[tokio::test]
async fn search_pages_with_cursor() {
    let server = MockServer::start().await;
    time_info(&server).await;
    let now = SystemTime::now();
    let now_ms = sdk::epoch_ms(now);
    Mock::given(path("/graphql"))
        .and(support::Gql("queryLogs"))
        .respond_with(paged_logs(5, now_ms - 1000))
        .mount(&server)
        .await;
    let c = client(&server);
    let logs = c.logs();
    let opts = SearchOptions::default().limit(2).range(None, Some(now));
    let p1 = logs.search("level=INFO", opts.clone()).await.unwrap();
    assert_eq!(p1.records.len(), 2);
    assert_eq!(p1.next_cursor.as_deref(), Some("2:0:2"));
    let p2 = logs
        .search("level=INFO", opts.clone().cursor("2:0:2"))
        .await
        .unwrap();
    assert_eq!(p2.records[0].body, "hay 2");
    let p3 = logs
        .search("level=INFO", opts.clone().cursor(p2.next_cursor.unwrap()))
        .await
        .unwrap();
    assert_eq!(p3.records.len(), 1);
    assert!(p3.next_cursor.is_none());
    // cursor inside a page
    let mid = logs
        .search(
            "level=INFO",
            SearchOptions::default().limit(1).cursor("1:1:2"),
        )
        .await
        .unwrap();
    assert_eq!(mid.records[0].body, "hay 1");
    assert_eq!(mid.next_cursor.as_deref(), Some("2:0:2"));
    // first record in a page that has more → offset cursor
    let first = logs
        .search(
            "level=INFO",
            SearchOptions::default().limit(1).cursor("1:0:2"),
        )
        .await
        .unwrap();
    assert_eq!(first.next_cursor.as_deref(), Some("1:1:2"));

    // free text: raw pages of 1000, filtered client side, ascending order requested
    let text = logs
        .search("needle", SearchOptions::default().order(SortOrder::Asc))
        .await
        .unwrap();
    assert_eq!(
        text.records
            .iter()
            .map(|r| r.body.as_str())
            .collect::<Vec<_>>(),
        vec!["needle 0", "needle 3"]
    );
    let reqs = server.received_requests().await.unwrap();
    let last: Value = serde_json::from_slice(&reqs.last().unwrap().body).unwrap();
    assert_eq!(last["variables"]["c"]["paging"]["pageSize"], 1000);
    assert_eq!(last["variables"]["c"]["queryOrder"], "ASC");
    assert_eq!(last["variables"]["c"]["tags"], Value::Null);

    for (q, o) in [
        ("x", SearchOptions::default().limit(0)),
        ("x", SearchOptions::default().limit(10_001)),
        ("x", SearchOptions::default().cursor("bad")),
        ("x", SearchOptions::default().cursor("0:0:1")),
        ("x", SearchOptions::default().cursor("1:0:0")),
        ("x", SearchOptions::default().cursor("1::1")),
        (
            "x",
            SearchOptions::default().cursor("99999999999999999999999:0:1"),
        ),
        (
            "x",
            SearchOptions::default().range(Some(now), Some(now - Duration::from_secs(1))),
        ),
    ] {
        assert_eq!(
            logs.search(q, o).await.unwrap_err().code(),
            ErrorCode::Validation
        );
    }
}

#[tokio::test]
async fn search_scan_budget_and_window() {
    let server = MockServer::start().await;
    time_info(&server).await;
    let now_ms = sdk::epoch_ms(SystemTime::now());
    Mock::given(path("/graphql"))
        .and(support::Gql("queryLogs"))
        .respond_with(paged_logs(2600, now_ms - 1000))
        .mount(&server)
        .await;
    let c = client(&server);
    let logs = c.logs().with_max_scan_pages(2).unwrap();
    assert!(c.logs().with_max_scan_pages(0).is_err());
    // limit not reached within 2 raw pages → cursor to the third page
    let res = logs
        .search("needle", SearchOptions::default().limit(5000))
        .await
        .unwrap();
    assert_eq!(res.records.len(), 667);
    assert_eq!(res.next_cursor.as_deref(), Some("3:0:1000"));
    // exact limit hit on the last log of a full page → next page cursor
    let res = logs
        .search(
            "needle",
            SearchOptions::default().limit(1).cursor("1:999:1000"),
        )
        .await
        .unwrap();
    assert_eq!(res.records[0].body, "needle 999");
    assert_eq!(res.next_cursor.as_deref(), Some("2:0:1000"));
    // logs outside the requested window are dropped
    let old = logs
        .search(
            "needle",
            SearchOptions::default()
                .range(Some(UNIX_EPOCH), Some(UNIX_EPOCH + Duration::from_secs(1))),
        )
        .await
        .unwrap();
    assert!(old.records.is_empty());
}

#[tokio::test]
async fn streams_labels_wait_tail() {
    let server = MockServer::start().await;
    time_info(&server).await;
    gql(
        &server,
        "listServices",
        gql_data(json!({"listServices": [
            {"name": "b", "normal": true}, {"name": "a", "normal": true},
            {"name": "a", "normal": true}, {"name": "v", "normal": false}
        ]})),
    )
    .await;
    gql(
        &server,
        "queryLogTagAutocompleteKeys",
        gql_data(json!({"queryLogTagAutocompleteKeys": ["run_id", "level"]})),
    )
    .await;
    Mock::given(path("/graphql"))
        .and(support::Gql("queryLogs"))
        .respond_with(move |_: &Request| {
            let ts = sdk::epoch_ms(SystemTime::now()) - 1;
            gql_data(json!({"queryLogs": {"logs": [log("tail me", ts, &[("level", "ERROR")])]}}))
        })
        .mount(&server)
        .await;
    let c = client(&server);
    assert_eq!(c.logs().list_streams().await.unwrap(), vec!["a", "b"]);
    assert_eq!(
        c.logs().list_labels().await.unwrap(),
        vec!["level", "run_id"]
    );
    let found = c
        .logs()
        .wait_for_log(
            "tail",
            WaitOptions::default()
                .timeout(Duration::from_secs(2))
                .interval(Duration::from_millis(10))
                .predicate(|r| r.severity == Severity::Error),
        )
        .await
        .unwrap();
    assert_eq!(found.body, "tail me");

    let got: Arc<Mutex<Vec<String>>> = Arc::default();
    let sink = Arc::clone(&got);
    tokio::time::sleep(Duration::from_millis(5)).await;
    let handle = c
        .logs()
        .tail(
            "tail",
            Arc::new(move |r: LogRecord| sink.lock().unwrap().push(r.body)),
            TailOptions {
                interval: Duration::from_millis(20),
                start: None,
            },
        )
        .await
        .unwrap();
    for _ in 0..100 {
        if !got.lock().unwrap().is_empty() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    handle.stop();
    assert_eq!(
        got.lock().unwrap().first().map(String::as_str),
        Some("tail me")
    );
}

#[tokio::test]
async fn exporter_errors_surface() {
    let server = MockServer::start().await;
    Mock::given(path("/v3/logs"))
        .respond_with(ResponseTemplate::new(400).set_body_raw("bad", "text/plain"))
        .mount(&server)
        .await;
    let c = client(&server);
    let errors: Arc<Mutex<Vec<String>>> = Arc::default();
    let sink = Arc::clone(&errors);
    let mut batch = sdk::logging::BatchOptions::default().flush_interval(Duration::from_secs(60));
    batch.on_error = Some(Arc::new(move |e, _| {
        sink.lock().unwrap().push(e.code().to_string())
    }));
    batch.max_export_retries = 0;
    let lc = c
        .log_client(SkyWalkingLogClientOptions::service("svc").batch(batch))
        .unwrap();
    lc.logger(Some("app"), attrs! {"k" => "v"})
        .error("x", attrs! {});
    let _ = lc.flush(Some(Duration::from_secs(5))).await;
    assert_eq!(lc.stats().failed, 1);
    assert_eq!(errors.lock().unwrap().as_slice(), ["VALIDATION"]);
    // an empty batch is a no-op
    lc.flush(None).await.unwrap();
    c.close().await;
}
