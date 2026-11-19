// Taken over from sdk-kit 0.1.0: the SkyWalking client and config against mocks of OAP.
//! `SkyWalkingConfig` (defaults, environment, validation, redaction) and `SkyWalkingClient`
//! (health, ping, version, raw GraphQL, log client, tracing, close) against recorded fixtures.

mod support;

use std::time::Duration;

use grow_apache_skywalking as sdk;
use sdk::core::{EnvReader, ErrorCode};
use sdk::logging::{BatchOptions, LogClient, LogInput, Severity};
use sdk::tracing::TraceQueryClient;
use sdk::{
    base_from_graphql_url, collector_to_url, create_log_client, parse_otlp_headers,
    zipkin_query_url, OtlpProtocol, SkyWalkingClient, SkyWalkingConfig as Config,
    SkyWalkingLogClientOptions, TraceBackend,
};
use serde_json::json;
use support::{builder, client, gql, gql_data, response, time_info};
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

fn env(pairs: &[(&str, &str)]) -> EnvReader {
    EnvReader::from_map(pairs.iter().map(|(k, v)| (k.to_string(), v.to_string())))
}

#[test]
fn config_defaults_and_helpers() {
    let c = Config::builder().build().unwrap();
    assert_eq!(c.url().as_str(), "http://127.0.0.1:12800/");
    assert_eq!(c.graphql_path(), "/graphql");
    assert_eq!(c.graphql_url(), "http://127.0.0.1:12800/graphql");
    assert_eq!(c.otlp_endpoint(), "http://127.0.0.1:11800");
    assert_eq!(c.otlp_protocol(), OtlpProtocol::Grpc);
    assert_eq!(c.zipkin_url(), "http://127.0.0.1:9412/zipkin");
    assert_eq!(c.trace_backend(), TraceBackend::Auto);
    assert!(c.authentication().is_none());
    assert!(c.otlp_headers().is_empty());
    assert!(c.known_secrets().is_empty());

    let c = Config::builder()
        .url("https://oap.example:8443/sw/graphql/")
        .graphql_path("/gql")
        .otlp_endpoint("http://collector:4318/")
        .otlp_protocol(OtlpProtocol::HttpProtobuf)
        .otlp_header("X-Scope", "a")
        .otlp_header("Authorization", "Bearer otlp-secret-1")
        .zipkin_url("http://zipkin:9412/zipkin/")
        .authentication("oap-token-secret-1")
        .trace_backend(TraceBackend::Native)
        .build()
        .unwrap();
    assert_eq!(c.url().as_str(), "https://oap.example:8443/sw");
    assert_eq!(c.graphql_url(), "https://oap.example:8443/sw/gql");
    assert_eq!(c.otlp_endpoint(), "http://collector:4318");
    assert_eq!(c.zipkin_url(), "http://zipkin:9412/zipkin");
    assert_eq!(c.otlp_headers()["x-scope"], "a");
    assert_eq!(c.known_secrets().len(), 2);
    let text = format!("{c:?}");
    assert!(
        !text.contains("otlp-secret-1") && !text.contains("oap-token-secret-1"),
        "{text}"
    );
    assert!(text.contains("[REDACTED]") && text.contains("native"));

    let v6 = Config::builder().url("http://[::1]:12800").build().unwrap();
    assert_eq!(v6.otlp_endpoint(), "http://[::1]:11800");
    assert_eq!(v6.zipkin_url(), "http://[::1]:9412/zipkin");

    assert_eq!(
        base_from_graphql_url("http://oap:12800/GraphQL/"),
        "http://oap:12800"
    );
    assert_eq!(
        base_from_graphql_url("http://oap:12800/"),
        "http://oap:12800"
    );
    assert_eq!(collector_to_url("oap:11800,oap2:11800"), "http://oap:11800");
    assert_eq!(collector_to_url("https://oap:11800"), "https://oap:11800");
    assert_eq!(collector_to_url("1x://oap"), "http://1x://oap");
    assert_eq!(
        zipkin_query_url("http://oap:9412"),
        "http://oap:9412/zipkin"
    );
    assert_eq!(
        zipkin_query_url("http://oap:9412/zipkin/"),
        "http://oap:9412/zipkin"
    );
    let h = parse_otlp_headers("A=1, b = x%20y ,,", "src").unwrap();
    assert_eq!(h["a"], "1");
    assert_eq!(h["b"], "x y");
    for bad in ["novalue", "=x", "a=%zz", "a=%e2%28"] {
        let err = parse_otlp_headers(bad, "variable X").unwrap_err();
        assert_eq!(err.code(), ErrorCode::Config);
        assert!(err.message().contains("variable X"));
    }
    assert_eq!(
        "NATIVE".parse::<TraceBackend>().unwrap(),
        TraceBackend::Native
    );
    assert_eq!("zipkin".parse::<TraceBackend>().unwrap().as_str(), "zipkin");
    assert_eq!(TraceBackend::Auto.as_str(), "auto");
    assert_eq!(TraceBackend::Native.as_str(), "native");
    assert!("x".parse::<TraceBackend>().is_err());
    assert_eq!(
        "http/protobuf".parse::<OtlpProtocol>().unwrap().as_str(),
        "http/protobuf"
    );
    assert_eq!(OtlpProtocol::Grpc.as_str(), "grpc");
    assert!("http".parse::<OtlpProtocol>().is_err());
}

#[test]
fn core_config_env_vendor_variables() {
    let c = Config::builder()
        .build_with_env(&env(&[
            ("SW_GRAPHQL_URL", "http://oap:12800/graphql"),
            ("SW_OAP_ADDRESS", "http://ignored:1"),
            ("SW_AGENT_COLLECTOR_BACKEND_SERVICES", "oap:11800"),
            ("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf"),
            (
                "OTEL_EXPORTER_OTLP_HEADERS",
                "Authorization=Bearer%20h-secret,x-a=b",
            ),
            ("SW_AUTHENTICATION", "sw-token"),
            ("SW_ZIPKIN_ADDRESS", "http://oap:9412"),
            ("GROW_SKYWALKING_TRACE_BACKEND", "zipkin"),
            ("GROW_SKYWALKING_INSECURE_SKIP_VERIFY", "true"),
            ("GROW_APACHE_SKYWALKING_REQUEST_TIMEOUT", "5s"),
            ("GROW_APACHE_SKYWALKING_GRAPHQL_PATH", "/q"),
        ]))
        .unwrap();
    assert_eq!(c.url().as_str(), "http://oap:12800/");
    assert_eq!(c.otlp_endpoint(), "http://oap:11800");
    assert_eq!(c.otlp_protocol(), OtlpProtocol::HttpProtobuf);
    assert_eq!(c.otlp_headers()["authorization"], "Bearer h-secret");
    assert_eq!(c.authentication().unwrap().expose_secret(), "sw-token");
    assert_eq!(c.zipkin_url(), "http://oap:9412/zipkin");
    assert_eq!(c.trace_backend(), TraceBackend::Zipkin);
    assert!(c.common().tls().insecure_skip_verify);
    assert_eq!(c.common().request_timeout(), Duration::from_secs(5));
    assert_eq!(c.graphql_path(), "/q");

    // GROW_APACHE_SKYWALKING_* wins over vendor variables; explicit wins over both
    let e = env(&[
        ("GROW_APACHE_SKYWALKING_URL", "http://grow:1"),
        ("SW_GRAPHQL_URL", "http://vendor:2/graphql"),
        ("GROW_APACHE_SKYWALKING_OTLP_ENDPOINT", "http://grow-otlp:3"),
        ("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://otel:4"),
        ("GROW_APACHE_SKYWALKING_ZIPKIN_URL", "http://z:9412/zipkin"),
        ("GROW_APACHE_SKYWALKING_AUTHENTICATION", "grow-token"),
        ("GROW_APACHE_SKYWALKING_OTLP_PROTOCOL", "grpc"),
        ("GROW_APACHE_SKYWALKING_TRACE_BACKEND", "native"),
    ]);
    let c = Config::builder().build_with_env(&e).unwrap();
    assert_eq!(c.url().as_str(), "http://grow:1/");
    assert_eq!(c.otlp_endpoint(), "http://grow-otlp:3");
    assert_eq!(c.authentication().unwrap().expose_secret(), "grow-token");
    assert_eq!(c.trace_backend(), TraceBackend::Native);
    let c = Config::builder()
        .url("http://explicit:5")
        .otlp_endpoint("http://explicit:6")
        .otlp_protocol(OtlpProtocol::HttpProtobuf)
        .otlp_header("a", "b")
        .zipkin_url("http://explicit:7/zipkin")
        .authentication("explicit")
        .trace_backend(TraceBackend::Auto)
        .graphql_path("/g")
        .build_with_env(&e)
        .unwrap();
    assert_eq!(c.url().as_str(), "http://explicit:5/");
    assert_eq!(c.otlp_endpoint(), "http://explicit:6");
    assert_eq!(c.otlp_protocol(), OtlpProtocol::HttpProtobuf);
    assert_eq!(c.zipkin_url(), "http://explicit:7/zipkin");
    assert_eq!(c.authentication().unwrap().expose_secret(), "explicit");
    assert_eq!(c.trace_backend(), TraceBackend::Auto);
    // the OTLP endpoint default follows the URL host
    let c = Config::builder()
        .build_with_env(&env(&[("SW_OAP_ADDRESS", "http://oap:12800")]))
        .unwrap();
    assert_eq!(c.otlp_endpoint(), "http://oap:11800");

    for (var, value, needle) in [
        (
            "OTEL_EXPORTER_OTLP_PROTOCOL",
            "thrift",
            "OTEL_EXPORTER_OTLP_PROTOCOL",
        ),
        (
            "GROW_SKYWALKING_TRACE_BACKEND",
            "jaeger",
            "GROW_SKYWALKING_TRACE_BACKEND",
        ),
        (
            "OTEL_EXPORTER_OTLP_HEADERS",
            "broken",
            "OTEL_EXPORTER_OTLP_HEADERS",
        ),
        (
            "GROW_SKYWALKING_INSECURE_SKIP_VERIFY",
            "maybe",
            "INSECURE_SKIP_VERIFY",
        ),
    ] {
        let err = Config::builder()
            .build_with_env(&env(&[(var, value)]))
            .unwrap_err();
        assert_eq!(err.code(), ErrorCode::Config, "{var}");
        assert!(err.message().contains(needle), "{err}");
        assert!(!err.message().contains(value), "{err}");
    }
}

#[test]
fn core_config_service_validation() {
    let cases = [
        Config::builder().graphql_path("graphql").build(),
        Config::builder().otlp_endpoint("ftp://x").build(),
        Config::builder().zipkin_url("not a url").build(),
        Config::builder().authentication("  ").build(),
        Config::builder().url("ftp://x").build(),
    ];
    for c in cases {
        assert_eq!(c.unwrap_err().code(), ErrorCode::Config);
    }
}

#[tokio::test]
async fn client_health_variants() {
    let server = MockServer::start().await;
    Mock::given(path("/healthcheck"))
        .respond_with(response("health.http"))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    let c = client(&server);
    let h = c.health().await.unwrap();
    assert!(h.healthy && h.status == "ok" && h.details.is_empty());

    Mock::given(path("/healthcheck"))
        .respond_with(ResponseTemplate::new(503))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    let h = c.health().await.unwrap();
    assert!(!h.healthy);
    assert_eq!(h.details["httpStatus"], "503");

    // no health checker → GraphQL checkHealth
    Mock::given(path("/healthcheck"))
        .respond_with(ResponseTemplate::new(404))
        .mount(&server)
        .await;
    gql(&server, "checkHealth", response("health.graphql")).await;
    assert!(c.health().await.unwrap().healthy);
    server.reset().await;
    Mock::given(path("/healthcheck"))
        .respond_with(ResponseTemplate::new(404))
        .mount(&server)
        .await;
    gql(
        &server,
        "checkHealth",
        gql_data(json!({"checkHealth": {"score": 2, "details": "storage down"}})),
    )
    .await;
    let h = c.health().await.unwrap();
    assert_eq!(h.status, "unhealthy");
    assert_eq!(h.details["score"], "2");
    assert_eq!(h.details["details"], "storage down");
    server.reset().await;
    Mock::given(path("/healthcheck"))
        .respond_with(ResponseTemplate::new(404))
        .mount(&server)
        .await;
    gql(
        &server,
        "checkHealth",
        gql_data(json!({"checkHealth": null})),
    )
    .await;
    let h = c.health().await.unwrap();
    assert!(!h.healthy && !h.details.contains_key("details"));
    server.reset().await;
    Mock::given(path("/healthcheck"))
        .respond_with(ResponseTemplate::new(404))
        .mount(&server)
        .await;
    Mock::given(path("/graphql"))
        .respond_with(ResponseTemplate::new(500))
        .mount(&server)
        .await;
    let h = c.health().await.unwrap();
    assert!(!h.healthy);
    assert_eq!(h.details["httpStatus"], "500");
}

#[tokio::test]
async fn core_health_unreachable() {
    let c = SkyWalkingClient::new(
        Config::builder()
            .url("http://127.0.0.1:1")
            .max_retries(0)
            .build()
            .unwrap(),
    )
    .unwrap();
    let h = c.health().await.unwrap();
    assert!(!h.healthy);
    assert_eq!(h.status, "unreachable");
}

#[tokio::test]
async fn client_ping_version_graphql() {
    let server = MockServer::start().await;
    time_info(&server).await;
    gql(&server, "{version}", response("serverVersion.ok")).await;
    gql(&server, "nope", response("graphql.validation-error")).await;
    gql(
        &server,
        "getGlobalTopology",
        response("graphql.fetch-error"),
    )
    .await;
    gql(
        &server,
        "queryBasicTraces",
        response("queryBasicTraces.unsupported"),
    )
    .await;
    let c = client(&server);
    assert!(c.ping().await.unwrap() < Duration::from_secs(5));
    assert_eq!(c.server_version().await.unwrap(), "10.4.0-b272da3");
    let raw = c.graphql("{version}", json!(null)).await.unwrap();
    assert_eq!(raw.field("version").unwrap(), "10.4.0-b272da3");
    assert!(raw.errors.is_empty());
    let err = c.graphql("{nope}", json!({})).await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
    assert_eq!(err.http_status(), Some(400));
    // resolver errors come back next to partial data
    let partial = c
        .graphql(
            "query($d:Duration!){getGlobalTopology(duration:$d){nodes{id}}}",
            json!({"d": {}}),
        )
        .await
        .unwrap();
    assert_eq!(partial.errors.len(), 1);
    assert_eq!(
        partial.errors[0].classification.as_deref(),
        Some("DataFetchingException")
    );
    assert_eq!(partial.errors[0].path, vec![json!("getGlobalTopology")]);
    assert!(partial.field("getGlobalTopology").is_none());
    let err = c.graphql("   ", json!({})).await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
    assert!(!c.is_closed());
    assert_eq!(c.config().url().as_str(), format!("{}/", server.uri()));
    assert!(c
        .transport()
        .user_agent()
        .contains("grow-codes-apache-skywalking"));
}

#[tokio::test]
async fn client_server_version_unsupported() {
    let server = MockServer::start().await;
    gql(&server, "{version}", gql_data(json!({"version": ""}))).await;
    let err = client(&server).server_version().await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Unsupported);
}

#[tokio::test]
async fn graphql_non_json_and_405() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/graphql"))
        .respond_with(ResponseTemplate::new(200).set_body_raw("<html>", "text/html"))
        .mount(&server)
        .await;
    let err = client(&server).server_version().await.unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
    server.reset().await;
    Mock::given(path("/graphql"))
        .respond_with(response("graphql.get-405"))
        .mount(&server)
        .await;
    assert_eq!(
        client(&server).ping().await.unwrap_err().code(),
        ErrorCode::Validation
    );
}

#[tokio::test]
async fn client_log_client_flushes_on_close() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/v3/logs"))
        .respond_with(response("reportLogs.ok"))
        .mount(&server)
        .await;
    let c = client(&server);
    let logs = c
        .log_client(
            SkyWalkingLogClientOptions::service("svc")
                .instance("i-1")
                .resource_attribute("team", "core")
                .batch(BatchOptions::default().flush_interval(Duration::from_secs(60))),
        )
        .unwrap();
    assert!(format!("{:?}", SkyWalkingLogClientOptions::default()).contains("service_name"));
    logs.send(LogInput::new("hello").severity(Severity::Warn).into());
    c.close().await;
    c.close().await;
    assert!(c.is_closed());
    assert_eq!(c.ping().await.unwrap_err().code(), ErrorCode::Cancelled);
    assert_eq!(
        c.query()
            .list_services(None, None)
            .await
            .unwrap_err()
            .code(),
        ErrorCode::Cancelled
    );
    assert_eq!(
        c.traces().list_services().await.unwrap_err().code(),
        ErrorCode::Cancelled
    );
    let reqs = server.received_requests().await.unwrap();
    let body: serde_json::Value = serde_json::from_slice(&reqs[0].body).unwrap();
    assert_eq!(body[0]["service"], "svc");
    assert_eq!(body[0]["serviceInstance"], "i-1");
    assert_eq!(body[0]["body"]["text"]["text"], "hello");
    assert_eq!(
        body[0]["tags"]["data"][0],
        json!({"key": "level", "value": "WARN"})
    );
    assert_eq!(logs.stats().sent, 1);
    assert_eq!(c.ping().await.unwrap_err().code(), ErrorCode::Cancelled);
}

#[tokio::test]
async fn standalone_log_client_owns_transport() {
    let server = MockServer::start().await;
    Mock::given(path("/v3/logs"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&server)
        .await;
    let logs = create_log_client(
        builder(&server).build().unwrap(),
        SkyWalkingLogClientOptions::default(),
    )
    .unwrap();
    logs.send("no service".into());
    logs.flush(None).await.unwrap();
    logs.close().await.unwrap();
    logs.close().await.unwrap();
    let reqs = server.received_requests().await.unwrap();
    let body: serde_json::Value = serde_json::from_slice(&reqs[0].body).unwrap();
    assert_eq!(body[0]["service"], "unknown_service");
    assert!(body[0].get("serviceInstance").is_none());
    let bad = Config::builder()
        .ca_cert_path("/nonexistent")
        .build()
        .unwrap();
    assert_eq!(
        create_log_client(bad.clone(), SkyWalkingLogClientOptions::default())
            .unwrap_err()
            .code(),
        ErrorCode::Config
    );
    assert_eq!(
        SkyWalkingClient::new(bad).unwrap_err().code(),
        ErrorCode::Config
    );
    let _ = SkyWalkingClient::from_env();
}

#[tokio::test]
async fn client_init_tracing_shutdown_on_close() {
    let server = MockServer::start().await;
    let c = client(&server);
    let handle = c
        .init_tracing(sdk::tracing_config("svc").with_set_global(false))
        .unwrap();
    assert_eq!(handle.service_name(), "svc");
    let mut registered = false;
    c.on_close(|| Box::pin(async {}));
    c.close().await;
    assert!(handle.is_shutdown());
    registered |= c.is_closed();
    assert!(registered);
    let err = c
        .init_tracing(sdk::tracing::TracingConfig::new(" "))
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Config);
}
