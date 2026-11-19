//! Durations and ids, the Zipkin and native span conversions, `sw8` vectors and the propagator,
//! and the tracing setup helpers.

mod support;

use std::collections::HashMap;
use std::time::{Duration, UNIX_EPOCH};

use grow_apache_skywalking as sdk;
use opentelemetry::baggage::BaggageExt;
use opentelemetry::propagation::TextMapPropagator;
use opentelemetry::Context;
use sdk::core::ErrorCode;
use sdk::tracing::{Protocol, SpanKind, StatusCode, Trace, TracingConfig};
use sdk::*;
use serde_json::json;
use support::{body, fixture};

#[test]
fn durations_and_ids() {
    assert_eq!(parse_timezone("+0000"), 0);
    assert_eq!(parse_timezone("-0530"), -330);
    assert_eq!(parse_timezone("+08:00"), 480);
    for bad in ["", "0800", "+8", "+08-00", "+0a00", "+080000"] {
        assert_eq!(parse_timezone(bad), 0, "{bad}");
    }
    let t = 1_795_036_241_775; // 2026-11-18 21:10:41.775 UTC
    assert_eq!(format_duration_time(t, Step::Day, 0), "2026-11-18");
    assert_eq!(format_duration_time(t, Step::Hour, 0), "2026-11-18 21");
    assert_eq!(format_duration_time(t, Step::Minute, 0), "2026-11-18 2110");
    assert_eq!(
        format_duration_time(t, Step::Second, 0),
        "2026-11-18 211041"
    );
    assert_eq!(format_duration_time(t, Step::Hour, 330), "2026-11-19 02");
    assert_eq!(
        format_duration_time(0, Step::Second, -60),
        "1969-12-31 230000"
    );
    assert_eq!(
        format_duration_time(951_782_400_000, Step::Day, 0),
        "2000-02-29"
    );
    assert_eq!(Step::default().as_str(), "MINUTE");
    assert_eq!(Step::Day.as_str(), "DAY");
    assert_eq!(Step::Hour.as_str(), "HOUR");
    assert_eq!(Step::Second.as_str(), "SECOND");

    let now = UNIX_EPOCH + Duration::from_millis(t as u64);
    let r = resolve_range(&TimeRange::default(), now, "op").unwrap();
    assert_eq!(r.end_ms - r.start_ms, 3_600_000);
    assert_eq!(r.step, Step::Minute);
    let d = to_graphql_duration(&r, 0);
    assert_eq!(d.start, "2026-11-18 2010");
    assert_eq!(serde_json::to_value(&d).unwrap()["step"], "MINUTE");
    let err = resolve_range(
        &TimeRange::default()
            .start(now)
            .end(now - Duration::from_secs(1)),
        now,
        "op",
    )
    .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
    let last = TimeRange::last(Duration::from_secs(60)).step(Step::Second);
    assert!(last.start.is_some() && last.end.is_none());
    assert_eq!(epoch_ms(UNIX_EPOCH - Duration::from_millis(5)), -5);
    assert_eq!(from_epoch_ms(-5), UNIX_EPOCH);
    assert_eq!(DEFAULT_RANGE, Duration::from_secs(3600));

    assert_eq!(
        service_id("sw-native-a-1fddf66a"),
        "c3ctbmF0aXZlLWEtMWZkZGY2NmE=.1"
    );
    assert_eq!(service_id_with("db", false), "ZGI=.0");
    assert_eq!(
        endpoint_id("sw-native-a-1fddf66a", "/native/entry"),
        "c3ctbmF0aXZlLWEtMWZkZGY2NmE=.1_L25hdGl2ZS9lbnRyeQ=="
    );
    assert!(instance_id("a", "i").starts_with("YQ==.1_"));
}

#[test]
fn zipkin_conversion() {
    let spans = zipkin_spans_from_body(&body("getTrace.zipkin"));
    assert_eq!(spans.len(), 2);
    let trace = zipkin_trace_to_trace(&spans).unwrap();
    assert_eq!(trace.spans[0].kind, SpanKind::Server);
    assert_eq!(trace.spans[1].kind, SpanKind::Client);
    assert!(zipkin_trace_to_trace(&[]).is_none());
    assert!(zipkin_spans_from_body(&json!({"x": 1})).is_empty());
    assert!(zipkin_spans_from_body(&json!([{"traceId": 1, "id": "a"}])).is_empty());
    let many = zipkin_traces_from_body(&json!([[], body("getTrace.zipkin")]));
    assert_eq!(many.len(), 1);
    assert_eq!(strings_from_body(&json!(["a", 1, "b"])), vec!["a", "b"]);

    let mut s = ZipkinSpan {
        trace_id: "ABC".into(),
        id: "00F0".into(),
        parent_id: Some(String::new()),
        kind: Some("INTERNAL".into()),
        timestamp: Some(10),
        duration: Some(5),
        ..Default::default()
    };
    s.tags
        .insert("otel.status_code".into(), "STATUS_CODE_ERROR".into());
    s.tags
        .insert("otel.status_description".into(), "bad".into());
    s.tags.insert("error".into(), "true".into());
    s.tags.insert("w3c.tracestate".into(), String::new());
    s.tags.insert("k".into(), "v".into());
    let span = zipkin_span_to_span(&s);
    assert_eq!(span.trace_id, "00000000000000000000000000000abc");
    assert_eq!(span.span_id, "00f0");
    assert!(span.parent_span_id.is_none());
    assert_eq!(span.kind, SpanKind::Unspecified);
    assert_eq!(span.status.code, StatusCode::Error);
    assert_eq!(span.status.message.as_deref(), Some("bad"));
    assert_eq!(span.attributes.len(), 1);
    assert_eq!(span.end_micros, 15);
    s.tags
        .insert("otel.status_description".into(), String::new());
    assert!(zipkin_span_to_span(&s).status.message.is_none());
    s.tags.remove("otel.status_code");
    assert_eq!(zipkin_span_to_span(&s).status.code, StatusCode::Error);
    s.tags.remove("error");
    assert_eq!(zipkin_span_to_span(&s).status.code, StatusCode::Unset);
    s.tags.insert("otel.status_code".into(), "ok".into());
    assert_eq!(zipkin_span_to_span(&s).status.code, StatusCode::Ok);
    s.trace_id = "not-hex".into();
    s.kind = None;
    let span = zipkin_span_to_span(&s);
    assert_eq!(span.trace_id, "not-hex");
    assert_eq!(span.kind, SpanKind::Internal);
    assert!(span.service_name.is_empty() && span.name.is_empty());
    assert_eq!(zipkin_trace_to_trace(&[s]).unwrap().trace_id, "not-hex");

    let ev = annotation_to_event(&ZipkinAnnotation {
        timestamp: 7,
        value: "cache.miss||0".into(),
    });
    assert_eq!((ev.name.as_str(), ev.attributes.len()), ("cache.miss", 0));
    for raw in ["plain", "|x|", "a|not json|0", "a|[1]|0"] {
        let ev = annotation_to_event(&ZipkinAnnotation {
            timestamp: 1,
            value: raw.into(),
        });
        assert_eq!(ev.name, raw);
    }
    assert!(is_bookkeeping_tag("otel.library.name"));
    assert!(!is_bookkeeping_tag("http.method"));
    assert!(is_hex_trace_id("00f067aa0ba902b7"));
    assert!(!is_hex_trace_id(&format!("{}.1.2", "a".repeat(32))));
    assert_eq!(
        zipkin_trace_id(" ABCDEF0123456789 ", "op").unwrap(),
        "abcdef0123456789"
    );
    assert!(zipkin_trace_id("xyz", "op").is_err());

    // summarize falls back to the earliest span when every span has a present parent
    let mut a = zipkin_span_to_span(&ZipkinSpan {
        trace_id: "1".into(),
        id: "a".into(),
        parent_id: Some("b".into()),
        timestamp: Some(1),
        name: Some("first".into()),
        ..Default::default()
    });
    let mut b = a.clone();
    b.span_id = "b".into();
    b.parent_span_id = Some("a".into());
    b.start_micros = 2;
    b.name = "second".into();
    a.end_micros = 3;
    let s = summarize(&Trace::new("1", vec![b, a]));
    assert_eq!(s.root_name.as_deref(), Some("first"));
    assert_eq!(s.span_count, 2);
}

#[test]
fn native_conversion() {
    let raw = body("getTrace.native");
    let trace = native_trace_from_spans(raw["data"]["queryTrace"].get("spans")).unwrap();
    let spec = native_trace_to_trace(&trace).unwrap();
    assert_eq!(spec.trace_id, trace.trace_id);
    let entry = spec
        .spans
        .iter()
        .find(|s| s.kind == SpanKind::Server && s.parent_span_id.is_none());
    assert!(entry.is_some(), "{spec:?}");
    assert!(native_trace_to_trace(&NativeTrace::default()).is_none());
    assert!(native_trace_from_spans(Some(&json!([]))).is_none());
    assert!(native_trace_from_spans(None).is_none());

    let span = decode_native_span(&json!({
        "traceId": "t.1.2", "segmentId": "seg-b", "spanId": 0, "parentSpanId": -1,
        "refs": [
            {"traceId": "t.1.2", "parentSegmentId": "seg-a", "parentSpanId": 1, "type": "CROSS_PROCESS"},
            {"traceId": "t.1.2", "parentSegmentId": "seg-c", "parentSpanId": 2.0, "type": "CROSS_THREAD"}
        ],
        "serviceCode": "b", "serviceInstanceName": "i-b", "startTime": 100, "endTime": 90,
        "endpointName": "/x", "type": "Local", "peer": "db:5432", "component": "JDBC",
        "isError": true, "layer": "Database",
        "tags": [{"key": "a", "value": 1}, {"key": "n", "value": null}],
        "logs": [{"time": 95, "data": [{"key": "message", "value": "m"}]}]
    }));
    assert_eq!(span.refs[1].parent_span_id, 2);
    let s = native_span_to_span(&span);
    assert_eq!(s.span_id, "seg-b-0");
    assert_eq!(s.parent_span_id.as_deref(), Some("seg-a-1"));
    assert_eq!(s.links.len(), 1);
    assert_eq!(s.links[0].span_id, "seg-c-2");
    assert_eq!(s.links[0].attributes["sw.ref_type"], "CROSS_THREAD");
    assert_eq!(s.kind, SpanKind::Internal);
    assert_eq!(s.status.code, StatusCode::Error);
    assert_eq!(
        (s.start_micros, s.end_micros, s.duration_micros),
        (100_000, 100_000, 0)
    );
    assert_eq!(s.attributes["a"], "1");
    assert_eq!(s.attributes["n"], "");
    assert_eq!(s.attributes["sw.peer"], "db:5432");
    assert_eq!(s.attributes["sw.service_instance"], "i-b");
    assert_eq!(s.events[0].name, "log");
    assert_eq!(s.events[0].time_micros, 95_000);
    let mut child = span.clone();
    child.parent_span_id = 0;
    child.span_id = 1;
    child.span_type = "Exit".into();
    let s = native_span_to_span(&child);
    assert_eq!(s.parent_span_id.as_deref(), Some("seg-b-0"));
    assert_eq!(s.links.len(), 2);
    assert_eq!(s.kind, SpanKind::Client);
    child.span_type = "Other".into();
    child.refs.clear();
    child.parent_span_id = -1;
    let s = native_span_to_span(&child);
    assert_eq!(s.kind, SpanKind::Unspecified);
    assert!(s.parent_span_id.is_none());
    let empty = decode_native_span(&json!("x"));
    assert_eq!(empty.parent_span_id, -1);
    assert_eq!(native_span_key("s", 3), "s-3");
    assert!(key_values(Some(&json!("no"))).is_empty());
    assert!(NATIVE_SPAN_FIELDS.contains("refs{"));

    let topo = decode_topology(&body("topology.global")["data"]["getGlobalTopology"]);
    let deps = topology_to_dependencies(&topo);
    assert_eq!(deps.len(), topo.calls.len());
    let unknown = topology_to_dependencies(&Topology {
        nodes: vec![],
        calls: vec![TopologyCall {
            source: "s".into(),
            target: "t".into(),
            ..Default::default()
        }],
    });
    assert_eq!(
        (unknown[0].parent.as_str(), unknown[0].child.as_str()),
        ("s", "t")
    );
    let l = decode_native_log(&json!({"content": "c", "timestamp": 1.5}));
    assert_eq!(l.timestamp, 1);
}

#[test]
fn sw8_vectors() {
    let v = fixture("sw8.vectors");
    let header = v["sw8"]["header"].as_str().unwrap();
    let decoded: Sw8Context = serde_json::from_value(v["sw8"]["decoded"].clone()).unwrap();
    assert_eq!(decode_sw8(header).unwrap(), decoded);
    assert_eq!(encode_sw8(&decoded), header);
    for bad in v["sw8"]["invalid"].as_array().unwrap() {
        assert!(decode_sw8(bad.as_str().unwrap()).is_none(), "{bad}");
    }
    let unsampled = Sw8Context {
        sample: false,
        ..decoded.clone()
    };
    assert!(encode_sw8(&unsampled).starts_with("0-"));
    assert!(!decode_sw8(&encode_sw8(&unsampled)).unwrap().sample);
    let empty_trace = Sw8Context {
        trace_id: String::new(),
        ..decoded.clone()
    };
    assert!(decode_sw8(&encode_sw8(&empty_trace)).is_none());
    assert!(decode_sw8("1-YQ==-YQ==-1-!!-YQ==-YQ==-YQ==").is_none());
    assert!(decode_sw8("1-/w==-YQ==-1-YQ==-YQ==-YQ==-YQ==").is_none());

    let ch = v["sw8Correlation"]["header"].as_str().unwrap();
    let pairs = decode_sw8_correlation(ch);
    assert_eq!(
        pairs,
        vec![
            ("tenant".to_owned(), "acme".to_owned()),
            ("run".to_owned(), "1fddf66a".to_owned())
        ]
    );
    assert_eq!(encode_sw8_correlation(&pairs), ch);
    assert!(decode_sw8_correlation("a:b:c,!!:YQ==,:YQ==,YQ==").is_empty());
    assert!(decode_sw8_correlation("").is_empty());

    let attrs = sw8_attributes(&decoded);
    assert_eq!(attrs["sw8.parent.span_id"], 1);
    assert_eq!(attrs["sw8.peer"], "sw-native-b-1fddf66a:8080");
}

#[test]
fn sw8_propagator() {
    let p = Sw8Propagator::default();
    assert_eq!(
        p.fields().collect::<Vec<_>>(),
        vec![SW8_HEADER, SW8_CORRELATION_HEADER]
    );
    let mut carrier: HashMap<String, String> = HashMap::new();
    p.inject_context(&Context::new(), &mut carrier);
    assert!(
        carrier.is_empty(),
        "nothing to inject without native metadata"
    );

    let sw8 = Sw8Context {
        sample: true,
        trace_id: "t.1.2".into(),
        parent_trace_segment_id: "seg".into(),
        parent_span_id: 1,
        parent_service: "a".into(),
        parent_service_instance: "i".into(),
        parent_endpoint: "/e".into(),
        network_address_used_at_peer: "b:80".into(),
    };
    let cx = with_sw8_context(
        &Context::new(),
        Sw8InjectContext {
            sw8: sw8.clone(),
            correlation: vec![("run".into(), "r1".into())],
        },
    );
    p.inject_context(&cx, &mut carrier);
    assert_eq!(carrier[SW8_HEADER], encode_sw8(&sw8));
    assert_eq!(
        carrier[SW8_CORRELATION_HEADER],
        encode_sw8_correlation(&[("run", "r1")])
    );
    let mut no_corr: HashMap<String, String> = HashMap::new();
    p.inject_context(
        &with_sw8_context(
            &Context::new(),
            Sw8InjectContext {
                sw8: sw8.clone(),
                correlation: vec![],
            },
        ),
        &mut no_corr,
    );
    assert!(!no_corr.contains_key(SW8_CORRELATION_HEADER));

    let base = Context::new().with_baggage(vec![opentelemetry::KeyValue::new("keep", "1")]);
    let out = p.extract_with_context(&base, &carrier);
    assert_eq!(sw8_context(&out).unwrap(), sw8);
    assert_eq!(
        out.baggage()
            .get("run")
            .map(|v| v.as_str().to_owned())
            .as_deref(),
        Some("r1")
    );
    assert_eq!(
        out.baggage()
            .get("keep")
            .map(|v| v.as_str().to_owned())
            .as_deref(),
        Some("1")
    );
    let nothing = p.extract_with_context(&Context::new(), &HashMap::<String, String>::new());
    assert!(sw8_context(&nothing).is_none());
    assert_eq!(SW8_PROPAGATOR, "sw8");
}

#[tokio::test]
async fn tracing_setup_helpers() {
    let sw = SkyWalkingConfig::builder()
        .url("http://oap:12800")
        .otlp_header("x-a", "1")
        .authentication("tok")
        .build()
        .unwrap();
    let c = apply_skywalking_defaults(TracingConfig::new("svc").with_header("x-a", "2"), &sw);
    assert_eq!(c.endpoint.as_deref(), Some("http://oap:11800"));
    assert_eq!(c.protocol, Protocol::Grpc);
    assert_eq!(c.headers["x-a"], "2");
    assert_eq!(c.headers[AUTHENTICATION_HEADER], "tok");
    assert!(c.tls.is_some());
    let explicit = apply_skywalking_defaults(
        TracingConfig::new("svc").with_endpoint("http://collector:4318"),
        &SkyWalkingConfig::builder()
            .otlp_protocol(OtlpProtocol::HttpProtobuf)
            .build()
            .unwrap(),
    );
    assert_eq!(explicit.endpoint.as_deref(), Some("http://collector:4318"));
    assert_eq!(explicit.protocol, Protocol::HttpProtobuf);
    // The template default http/protobuf counts as unset: the SkyWalking protocol (grpc) applies,
    // also with an explicit endpoint; an explicit grpc is kept.
    let default_protocol = apply_skywalking_defaults(
        TracingConfig::new("svc").with_endpoint("http://oap:11800"),
        &sw,
    );
    assert_eq!(default_protocol.protocol, Protocol::Grpc);
    let kept = apply_skywalking_defaults(
        TracingConfig::new("svc").with_protocol(Protocol::Grpc),
        &SkyWalkingConfig::builder()
            .otlp_protocol(OtlpProtocol::HttpProtobuf)
            .build()
            .unwrap(),
    );
    assert_eq!(kept.protocol, Protocol::Grpc);
    let http = apply_skywalking_defaults(
        TracingConfig::new("svc"),
        &SkyWalkingConfig::builder()
            .otlp_protocol(OtlpProtocol::HttpProtobuf)
            .build()
            .unwrap(),
    );
    assert_eq!(http.protocol, Protocol::HttpProtobuf);
    assert_eq!(tracing_config("x").protocol, Protocol::Grpc);
    assert!(skywalking_propagators().contains_key("sw8"));
    assert_eq!(
        skywalking_span_exporter(&TracingConfig::new("x").with_protocol(Protocol::Native))
            .unwrap_err()
            .code(),
        ErrorCode::Config
    );
    assert!(skywalking_span_exporter(&http).is_ok());

    let handle = init_tracing_with_config(
        tracing_config("svc")
            .with_set_global(false)
            .with_propagators(vec![
                "tracecontext".parse().unwrap(),
                "sw8".parse().unwrap(),
            ]),
        &sw,
    )
    .unwrap();
    handle.shutdown().await.unwrap();
    let err = init_tracing_with_config(
        tracing_config("svc")
            .with_set_global(false)
            .with_propagators(vec!["datadog".parse().unwrap()]),
        &sw,
    )
    .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Config);
    // from the process environment (defaults when unset)
    let h = init_tracing(tracing_config("env").with_set_global(false));
    if let Ok(h) = h {
        h.shutdown().await.unwrap();
    }
}
