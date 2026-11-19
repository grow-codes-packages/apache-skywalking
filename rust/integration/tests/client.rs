// Integration tests against the real SkyWalking OAP 10.4.0 + BanyanDB (docker/compose.yml).
//! Every public function is called at least once with a round-trip assertion (write → read
//! back). Native traces are seeded over OAP's documented HTTP API (`/v3/segments`) because no
//! SkyWalking agent runs in the test container; OTLP spans come from the package's own
//! `init_tracing`.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime};

use grow_apache_skywalking as sdk;
use grow_apache_skywalking_integration::{load_bootstrap_env, poll, run_id};
use opentelemetry::propagation::TextMapPropagator;
use opentelemetry::trace::{Span as _, TraceContextExt, Tracer as _};
use opentelemetry::{Context, KeyValue};
use sdk::logging::{
    LogClient, LogInput, LogQueryClient, LogRecord, SearchOptions, Severity, TailOptions,
    WaitOptions,
};
use sdk::tracing::{
    DependencyOptions, PropagatorKind, StatusCode, TraceQueryClient, TraceSearchQuery,
    WaitForTraceOptions,
};
use sdk::*;
use serde_json::json;

const LONG: Duration = Duration::from_secs(120);

fn hex(n: usize) -> String {
    let mut out = String::new();
    while out.len() < n {
        out.push_str(&format!("{:016x}", rand_u64()));
    }
    out.truncate(n);
    out
}

fn rand_u64() -> u64 {
    use std::hash::{BuildHasher, Hasher};
    let mut h = std::collections::hash_map::RandomState::new().build_hasher();
    h.write_u128(
        SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .unwrap()
            .as_nanos(),
    );
    h.finish()
}

fn ago(secs: u64) -> SystemTime {
    SystemTime::now() - Duration::from_secs(secs)
}

struct Seeded {
    native_trace_id: String,
    seg_a: String,
}

/// Seeds a native A → B trace; B's ref is built by decoding the sw8 header of A's exit span.
async fn seed_native_trace(client: &SkyWalkingClient, run: &str, a: &str, b: &str) -> Seeded {
    let now = epoch_ms(SystemTime::now());
    let trace_id = format!("{}.1.{now}", hex(32));
    let seg_a = format!("{}.1.{now}", hex(32));
    let seg_b = format!("{}.2.{now}", hex(32));
    let header = encode_sw8(&Sw8Context {
        sample: true,
        trace_id: trace_id.clone(),
        parent_trace_segment_id: seg_a.clone(),
        parent_span_id: 1,
        parent_service: a.to_owned(),
        parent_service_instance: format!("inst-a-{run}"),
        parent_endpoint: "/it/entry".into(),
        network_address_used_at_peer: format!("{b}:8080"),
    });
    let r = decode_sw8(&header).expect("own sw8 header decodes");
    let segments = json!([
        {
            "traceId": trace_id, "traceSegmentId": seg_a, "service": a,
            "serviceInstance": format!("inst-a-{run}"),
            "spans": [
                {"spanId": 1, "parentSpanId": 0, "startTime": now - 80, "endTime": now - 20,
                 "operationName": "/it/downstream", "peer": format!("{b}:8080"), "spanType": "Exit",
                 "spanLayer": "Http", "componentId": 2, "isError": false,
                 "tags": [{"key": "http.method", "value": "GET"}]},
                {"spanId": 0, "parentSpanId": -1, "startTime": now - 100, "endTime": now,
                 "operationName": "/it/entry", "peer": "", "spanType": "Entry", "spanLayer": "Http",
                 "componentId": 1, "isError": false,
                 "tags": [{"key": "http.method", "value": "GET"}, {"key": "run_id", "value": run}]}
            ]
        },
        {
            "traceId": r.trace_id, "traceSegmentId": seg_b, "service": b,
            "serviceInstance": format!("inst-b-{run}"),
            "spans": [
                {"spanId": 0, "parentSpanId": -1, "startTime": now - 70, "endTime": now - 30,
                 "operationName": "/it/downstream", "peer": "", "spanType": "Entry",
                 "spanLayer": "Http", "componentId": 1, "isError": true,
                 "refs": [{"refType": "CrossProcess", "traceId": r.trace_id,
                           "parentTraceSegmentId": r.parent_trace_segment_id,
                           "parentSpanId": r.parent_span_id, "parentService": r.parent_service,
                           "parentServiceInstance": r.parent_service_instance,
                           "parentEndpoint": r.parent_endpoint,
                           "networkAddressUsedAtPeer": r.network_address_used_at_peer}],
                 "tags": [{"key": "run_id", "value": run}],
                 "logs": [{"time": now - 40, "data": [{"key": "event", "value": "error"},
                                                     {"key": "message", "value": "boom"}]}]}
            ]
        }
    ]);
    let resp = client
        .transport()
        .post("/v3/segments")
        .operation("seed segments")
        .json(&segments)
        .send()
        .await
        .expect("seed native segments");
    assert_eq!(resp.status(), 200);
    Seeded {
        native_trace_id: trace_id,
        seg_a,
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn round_trip_every_function() {
    load_bootstrap_env();
    assert!(
        std::env::var("GROW_APACHE_SKYWALKING_URL").is_ok(),
        "GROW_APACHE_SKYWALKING_URL must be set"
    );
    let run = run_id();
    let svc_a = format!("it-native-a-{run}");
    let svc_b = format!("it-native-b-{run}");
    let svc_otlp = format!("it-otlp-{run}");
    let svc_logs = format!("it-logs-{run}");
    let client = SkyWalkingClient::from_env().expect("client");
    let seeded = seed_native_trace(&client, &run, &svc_a, &svc_b).await;
    let native_id = seeded.native_trace_id.clone();

    // OTLP spans through init_tracing (Zipkin store)
    let handle = client
        .init_tracing(
            tracing_config(svc_otlp.clone())
                .with_set_global(false)
                .with_propagators(vec![
                    PropagatorKind::TraceContext,
                    PropagatorKind::Baggage,
                    PropagatorKind::Other("sw8".into()),
                ]),
        )
        .expect("init_tracing");
    let tracer = handle.tracer("it", None);
    let root = tracer
        .span_builder("GET /checkout")
        .with_attributes(vec![
            KeyValue::new("run_id", run.clone()),
            KeyValue::new("retries", 2_i64),
        ])
        .start(&tracer);
    let root_cx = Context::new().with_span(root);
    let otlp_id = root_cx.span().span_context().trace_id().to_string();
    let root_span_id = root_cx.span().span_context().span_id().to_string();
    let mut child = tracer
        .span_builder("select orders")
        .start_with_context(&tracer, &root_cx);
    child.add_event("cache.miss", vec![KeyValue::new("k", "v")]);
    child.end();
    root_cx.span().end();
    handle
        .force_flush(Some(Duration::from_secs(30)))
        .await
        .expect("flush spans");

    // native logs through the log client
    let logs = client
        .log_client(
            SkyWalkingLogClientOptions::service(svc_logs.clone())
                .instance(format!("inst-{run}"))
                .batch(
                    sdk::logging::BatchOptions::default()
                        .flush_interval(Duration::from_millis(200)),
                ),
        )
        .expect("log client");
    logs.send(
        LogInput::new(format!("order created native-log-{run}"))
            .severity(Severity::Warn)
            .attribute("run_id", run.clone())
            .attribute("n", 1)
            .into(),
    );
    logs.send(
        LogInput::new(format!("payment failed native-log-{run}"))
            .severity(Severity::Error)
            .attribute("run_id", run.clone())
            .trace(otlp_id.clone(), root_span_id.clone())
            .into(),
    );
    logs.flush(None).await.expect("flush logs");
    assert_eq!(logs.stats().sent, 2);

    // health, ping, serverVersion, graphql, timeInfo, capabilities
    let health = client.health().await.unwrap();
    assert!(health.healthy && health.status == "ok", "{health:?}");
    assert!(client.ping().await.unwrap() < Duration::from_secs(30));
    let version = client.server_version().await.unwrap();
    assert!(version.starts_with("10.4."), "{version}");
    let raw = client.graphql("{version}", json!({})).await.unwrap();
    assert_eq!(
        raw.field("version").and_then(|v| v.as_str()),
        Some(version.as_str())
    );
    let via_query = client
        .query()
        .graphql("query{version}", json!(null))
        .await
        .unwrap();
    assert_eq!(via_query.data, raw.data);
    assert_eq!(
        client
            .graphql("{nope}", json!({}))
            .await
            .unwrap_err()
            .code(),
        ErrorCode::Validation
    );
    let time = client.query().time_info().await.unwrap();
    assert!((time.current_timestamp - epoch_ms(SystemTime::now())).abs() < 300_000);
    let caps = client.query().capabilities().await.unwrap();
    assert!(caps.query_traces_v2);
    assert!(!caps.logs_by_keywords);

    // traces (Zipkin store): wait_for_trace / get_trace round-trip the OTLP spans
    let wait = WaitForTraceOptions::default()
        .timeout(Duration::from_secs(90))
        .interval(Duration::from_secs(2));
    let trace = client
        .traces()
        .wait_for_trace(&otlp_id, wait.min_spans(2))
        .await
        .expect("OTLP trace readable");
    assert_eq!(trace.trace_id, otlp_id);
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
    assert_eq!(root.service_name, svc_otlp);
    assert_eq!(root.attributes["run_id"], json!(run));
    assert_eq!(child.parent_span_id.as_deref(), Some(root.span_id.as_str()));
    assert_eq!(child.events[0].attributes["k"], "v");
    assert_eq!(
        client
            .traces()
            .get_trace(&otlp_id)
            .await
            .unwrap()
            .unwrap()
            .spans
            .len(),
        2
    );
    assert!(client
        .traces()
        .get_trace("0000000000000000000000000000beef")
        .await
        .unwrap()
        .is_none());

    // traces.search_traces finds the OTLP trace by service and tag
    let query = TraceSearchQuery {
        start: Some(ago(600)),
        ..TraceSearchQuery::service(svc_otlp.clone()).tag("run_id", run.clone())
    };
    let hits = poll(
        "search_traces",
        LONG,
        || client.traces().search_traces(&query),
        |h| h.iter().any(|x| x.trace_id == otlp_id),
    )
    .await;
    let hit = hits.iter().find(|x| x.trace_id == otlp_id).unwrap();
    assert_eq!(hit.root_name.as_deref(), Some("get /checkout"));
    assert_eq!(hit.span_count, 2);

    // traces.list_services / list_operations list the OTLP and native services
    let services = poll(
        "traces.list_services",
        LONG,
        || client.traces().list_services(),
        |s| s.contains(&svc_otlp) && s.contains(&svc_a),
    )
    .await;
    assert!(services.contains(&svc_b));
    let ops = poll(
        "traces.list_operations",
        LONG,
        || client.traces().list_operations(&svc_otlp),
        |o| o.iter().any(|x| x == "get /checkout"),
    )
    .await;
    assert!(ops.iter().any(|x| x == "select orders"));
    poll(
        "traces.list_operations native",
        LONG,
        || client.traces().list_operations(&svc_a),
        |o| o.iter().any(|x| x == "/it/entry"),
    )
    .await;

    // traces: native trace by native id, sw8-linked parent, dependencies
    let native = client
        .traces()
        .wait_for_trace(&native_id, wait.min_spans(3))
        .await
        .expect("native trace readable");
    assert_eq!(native.trace_id, native_id);
    let b = native
        .spans
        .iter()
        .find(|s| s.service_name == svc_b)
        .unwrap();
    assert_eq!(
        b.parent_span_id.as_deref(),
        Some(format!("{}-1", seeded.seg_a).as_str())
    );
    assert_eq!(b.status.code, StatusCode::Error);
    assert_eq!(b.events[0].name, "error");
    let deps = poll(
        "get_dependencies",
        LONG,
        || {
            client.traces().get_dependencies(DependencyOptions {
                start: Some(ago(900)),
                end: None,
            })
        },
        |d| d.iter().any(|l| l.parent == svc_a && l.child == svc_b),
    )
    .await;
    assert_eq!(
        deps.iter().find(|l| l.parent == svc_a).unwrap().call_count,
        0
    );

    // query: services, endpoints, instances
    let found = poll(
        "query.list_services",
        LONG,
        || client.query().list_services(Some("GENERAL"), None),
        |s| s.iter().any(|x| x.name == svc_a),
    )
    .await;
    assert_eq!(
        found.iter().find(|x| x.name == svc_a).unwrap().id,
        service_id(&svc_a)
    );
    assert!(client
        .query()
        .list_services(
            Some("NO_SUCH_LAYER"),
            Some(TimeRange::last(Duration::from_secs(60)))
        )
        .await
        .unwrap()
        .is_empty());
    assert_eq!(
        client
            .query()
            .find_service(&svc_a)
            .await
            .unwrap()
            .unwrap()
            .name,
        svc_a
    );
    assert!(client
        .query()
        .find_service(&format!("no-such-{run}"))
        .await
        .unwrap()
        .is_none());
    poll(
        "list_endpoints",
        LONG,
        || client.query().list_endpoints(&svc_a, None, None),
        |e| e.iter().any(|x| x.name == "/it/entry"),
    )
    .await;
    let kw = client
        .query()
        .list_endpoints(&svc_b, Some("downstream"), Some(5))
        .await
        .unwrap();
    assert_eq!(
        kw.iter().map(|e| e.name.as_str()).collect::<Vec<_>>(),
        ["/it/downstream"]
    );
    let inst = poll(
        "list_instances",
        LONG,
        || {
            client
                .query()
                .list_instances(&svc_a, Some(TimeRange::default().start(ago(900))))
        },
        |i| !i.is_empty(),
    )
    .await;
    assert_eq!(inst[0].name, format!("inst-a-{run}"));

    // query: query_traces, get_trace, topology
    let window = TimeRange::default().start(ago(900));
    let by_tag = QueryTracesOptions::service(svc_a.clone())
        .tag("run_id", run.clone())
        .range(window);
    let res = poll(
        "query_traces",
        LONG,
        || client.query().query_traces(&by_tag),
        |r| r.traces.iter().any(|t| t.trace_id == native_id),
    )
    .await;
    assert_eq!(
        res.traces
            .iter()
            .find(|t| t.trace_id == native_id)
            .unwrap()
            .spans
            .len(),
        3
    );
    let errors = client
        .query()
        .query_traces(
            &QueryTracesOptions::service(svc_b.clone())
                .trace_state(TraceState::Error)
                .range(window),
        )
        .await
        .unwrap();
    assert!(errors.traces.iter().any(|t| t.trace_id == native_id));
    let none = client
        .query()
        .query_traces(&QueryTracesOptions {
            min_duration_ms: Some(60_000),
            ..QueryTracesOptions::service(svc_a.clone()).range(window)
        })
        .await
        .unwrap();
    assert!(none.traces.is_empty());
    let raw_native = client.query().get_trace(&native_id).await.unwrap().unwrap();
    let b_span = raw_native
        .spans
        .iter()
        .find(|s| s.service_code == svc_b)
        .unwrap();
    assert_eq!(b_span.refs[0].parent_segment_id, seeded.seg_a);
    assert_eq!(b_span.refs[0].ref_type, "CROSS_PROCESS");
    assert!(client.query().get_trace(&otlp_id).await.unwrap().is_none());
    let no_filter = TopologyOptions::default();
    let topo = poll(
        "topology",
        LONG,
        || client.query().topology(Some(window), &no_filter),
        |t| {
            t.calls
                .iter()
                .any(|c| c.source == service_id(&svc_a) && c.target == service_id(&svc_b))
        },
    )
    .await;
    assert!(topo.nodes.iter().any(|n| n.name == svc_b));
    let svc_topo = client
        .query()
        .topology(
            Some(window),
            &TopologyOptions {
                service: Some(svc_a.clone()),
                layer: None,
            },
        )
        .await
        .unwrap();
    assert!(svc_topo.nodes.iter().any(|n| n.name == svc_b));

    // query: query_logs by service, tag, related trace, keywords; list_log_tag_keys
    let logs_of_service = QueryLogsOptions::service(svc_logs.clone());
    let by_service = poll(
        "query_logs",
        LONG,
        || client.query().query_logs(&logs_of_service),
        |r| r.logs.len() >= 2,
    )
    .await;
    assert!(by_service.logs.iter().all(|l| l.service_name == svc_logs));
    let by_tag = client
        .query()
        .query_logs(&QueryLogsOptions {
            order: LogOrder::Asc,
            ..QueryLogsOptions::default().tag("run_id", run.clone())
        })
        .await
        .unwrap();
    assert_eq!(by_tag.logs.len(), 2);
    assert!(by_tag.logs[0].timestamp <= by_tag.logs[1].timestamp);
    let related = client
        .query()
        .query_logs(&QueryLogsOptions::trace_id(otlp_id.clone()))
        .await
        .unwrap();
    assert_eq!(related.logs.len(), 1);
    assert!(related.logs[0].content.contains("payment failed"));
    let kw = client
        .query()
        .query_logs(&QueryLogsOptions {
            keywords: vec!["payment".into()],
            excluding_keywords: vec!["order".into()],
            ..QueryLogsOptions::service(svc_logs.clone())
        })
        .await
        .unwrap();
    assert_eq!(
        kw.logs
            .iter()
            .map(|l| l.content.clone())
            .collect::<Vec<_>>(),
        vec![format!("payment failed native-log-{run}")]
    );
    let keys = poll(
        "list_log_tag_keys",
        LONG,
        || client.query().list_log_tag_keys(None),
        |k| k.iter().any(|x| x == "run_id"),
    )
    .await;
    assert!(keys.iter().any(|x| x == "level"));

    // logs (LogQueryClient): search, wait_for_log, streams, labels, tail
    let found = client
        .logs()
        .wait_for_log(
            &format!("payment native-log-{run}"),
            WaitOptions::default()
                .timeout(Duration::from_secs(60))
                .interval(Duration::from_secs(2)),
        )
        .await
        .unwrap();
    assert_eq!(found.severity, Severity::Error);
    assert_eq!(found.trace_id.as_deref(), Some(otlp_id.as_str()));
    assert_eq!(found.span_id.as_deref(), Some(root_span_id.as_str()));
    assert_eq!(found.attributes["run_id"], json!(run));
    assert_eq!(found.resource["service.name"], json!(svc_logs));
    let page1 = client
        .logs()
        .search(&format!("run_id={run}"), SearchOptions::default().limit(1))
        .await
        .unwrap();
    assert_eq!(page1.records.len(), 1);
    let cursor = page1.next_cursor.clone().expect("next cursor");
    let page2 = client
        .logs()
        .search(
            &format!("run_id={run}"),
            SearchOptions::default().limit(1).cursor(cursor),
        )
        .await
        .unwrap();
    assert_eq!(page2.records.len(), 1);
    assert_ne!(page2.records[0].body, page1.records[0].body);
    let by_trace = client
        .logs()
        .search(&format!("trace_id={otlp_id}"), SearchOptions::default())
        .await
        .unwrap();
    assert_eq!(by_trace.records.len(), 1);
    let by_svc = client
        .logs()
        .search(
            &format!("service={svc_logs} instance=inst-{run} created"),
            SearchOptions::default(),
        )
        .await
        .unwrap();
    assert_eq!(
        by_svc
            .records
            .iter()
            .map(|r| r.body.clone())
            .collect::<Vec<_>>(),
        vec![format!("order created native-log-{run}")]
    );
    assert!(client
        .logs()
        .list_streams()
        .await
        .unwrap()
        .contains(&svc_logs));
    assert!(client
        .logs()
        .list_labels()
        .await
        .unwrap()
        .iter()
        .any(|x| x == "run_id"));

    let seen: Arc<Mutex<Vec<LogRecord>>> = Arc::default();
    let sink = Arc::clone(&seen);
    let tail = client
        .logs()
        .tail(
            &format!("tail-{run}"),
            Arc::new(move |r| sink.lock().unwrap().push(r)),
            TailOptions {
                interval: Duration::from_secs(1),
                start: None,
            },
        )
        .await
        .unwrap();
    let writer = create_log_client(
        SkyWalkingConfig::from_env().unwrap(),
        SkyWalkingLogClientOptions::service(svc_logs.clone()).batch(
            sdk::logging::BatchOptions::default().flush_interval(Duration::from_millis(100)),
        ),
    )
    .unwrap();
    writer.send(LogInput::new(format!("tail-{run} live")).into());
    writer.flush(None).await.unwrap();
    let deadline = std::time::Instant::now() + Duration::from_secs(60);
    while seen.lock().unwrap().is_empty() {
        assert!(
            std::time::Instant::now() < deadline,
            "tail delivered nothing"
        );
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    tail.stop();
    writer.close().await.unwrap();
    assert_eq!(seen.lock().unwrap()[0].body, format!("tail-{run} live"));

    // standalone init_tracing (environment) + sw8 propagator against OAP data
    let standalone = init_tracing(
        tracing_config(format!("{svc_otlp}-standalone"))
            .with_set_global(false)
            .with_propagators(vec![PropagatorKind::Other("sw8".into())]),
    )
    .unwrap();
    let mut span = standalone.tracer("it", None).start("standalone");
    let sid = span.span_context().trace_id().to_string();
    span.end();
    standalone.force_flush(None).await.unwrap();
    standalone.shutdown().await.unwrap();
    let t = client.traces().wait_for_trace(&sid, wait).await.unwrap();
    assert_eq!(t.spans[0].name, "standalone");

    let exit = raw_native
        .spans
        .iter()
        .find(|s| s.span_type == "Exit")
        .unwrap();
    let sw8 = Sw8Context {
        sample: true,
        trace_id: exit.trace_id.clone(),
        parent_trace_segment_id: exit.segment_id.clone(),
        parent_span_id: exit.span_id,
        parent_service: exit.service_code.clone(),
        parent_service_instance: exit.service_instance_name.clone(),
        parent_endpoint: "/it/entry".into(),
        network_address_used_at_peer: exit.peer.clone(),
    };
    let p = Sw8Propagator::new();
    let mut carrier: HashMap<String, String> = HashMap::new();
    p.inject_context(
        &with_sw8_context(
            &Context::new(),
            Sw8InjectContext {
                sw8: sw8.clone(),
                correlation: vec![("run".into(), run.clone())],
            },
        ),
        &mut carrier,
    );
    assert_eq!(carrier[SW8_HEADER], encode_sw8(&sw8));
    assert_eq!(
        carrier[SW8_CORRELATION_HEADER],
        encode_sw8_correlation(&[("run", run.as_str())])
    );
    assert_eq!(
        decode_sw8_correlation(&carrier[SW8_CORRELATION_HEADER]),
        vec![("run".to_owned(), run.clone())]
    );
    let extracted = sw8_context(&p.extract_with_context(&Context::new(), &carrier)).unwrap();
    assert_eq!(extracted, sw8);
    assert_eq!(
        sw8_attributes(&sw8)["sw8.parent.segment_id"],
        json!(seeded.seg_a)
    );

    client.close().await;
    client.close().await;
    assert!(handle.is_shutdown());
}

#[test]
fn blocking_round_trip() {
    load_bootstrap_env();
    let c = sdk::blocking::SkyWalkingClient::from_env().expect("client");
    assert!(c.health().expect("health").healthy);
    c.ping().expect("ping");
    assert!(c.server_version().unwrap().starts_with("10.4."));
    assert!(c.query().capabilities().unwrap().query_traces_v2);
    let run = run_id();
    let svc = format!("it-blocking-{run}");
    let logs = c
        .log_client(SkyWalkingLogClientOptions::service(svc.clone()))
        .unwrap();
    logs.send(LogInput::new(format!("blocking {run}")).into());
    c.block_on(logs.flush(None)).unwrap();
    let found = c
        .logs()
        .wait_for_log(
            &format!("service={svc} blocking"),
            WaitOptions::default().timeout(Duration::from_secs(60)),
        )
        .unwrap();
    assert_eq!(found.body, format!("blocking {run}"));
    c.close();
}
