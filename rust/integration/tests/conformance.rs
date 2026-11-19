// Created by sdk-kit 0.1.0; owned by this repository (SkyWalking: logging + tracing targets).
//! Conformance scenarios against the real OAP. Capabilities: core declares `version`; logging
//! declares `query` (free text is filtered client side because BanyanDB ignores content
//! keywords); tracing declares `query` and `services`. OAP lists a new service ~30–35 s after
//! its first span and the runner calls `listServices` once, so the tracing service is warmed up
//! first (span sent, then `list_services` polled).

use std::sync::Arc;
use std::time::Duration;

use grow_apache_skywalking as sdk;
use grow_apache_skywalking_integration::{conformance_dir, load_bootstrap_env, poll, run_id};
use opentelemetry::trace::{Span as _, Tracer as _};
use sdk::logging::{LogClient, LogQueryClient};
use sdk::testing::logging::{LoggingHandler, LoggingTarget};
use sdk::testing::tracing::{TracingHandler, TracingTarget};
use sdk::testing::{ConformanceReport, ConformanceRunner, CoreHandler};
use sdk::tracing::TraceQueryClient;
use sdk::{
    create_log_client, init_tracing, tracing_config, SkyWalkingClient as Client,
    SkyWalkingConfig as Config, SkyWalkingLogClientOptions,
};

fn check(report: &ConformanceReport) {
    println!("{report}");
    report.assert_success();
    assert_eq!(report.skipped(), 0, "{report}");
    assert!(report.passed() > 0, "{report}");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn conformance_core() {
    load_bootstrap_env();
    let report = ConformanceRunner::new(conformance_dir())
        .capabilities(["version"])
        .handler(CoreHandler::new(Client::from_env))
        .run()
        .await
        .expect("scenarios load");
    check(&report);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn conformance_logging() {
    load_bootstrap_env();
    let client = Client::from_env().expect("client");
    let query: Arc<dyn LogQueryClient> = Arc::new(client.logs().clone());
    let report = ConformanceRunner::new(conformance_dir())
        .capabilities(["query"])
        .handler(LoggingHandler::new(move |batch| {
            let query = Arc::clone(&query);
            async move {
                let options =
                    SkyWalkingLogClientOptions::service(format!("conformance-logs-{}", run_id()))
                        .batch(batch);
                let log: Arc<dyn LogClient> =
                    Arc::new(create_log_client(Config::from_env()?, options)?);
                Ok(LoggingTarget {
                    client: log,
                    query: Some(query),
                })
            }
        }))
        .run()
        .await
        .expect("scenarios load");
    check(&report);
    client.close().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn conformance_tracing() {
    load_bootstrap_env();
    let client = Client::from_env().expect("client");
    let service = format!("conformance-traces-{}", run_id());
    // warm-up: OAP lists a new service only after its periodic name flush
    let warm = init_tracing(tracing_config(service.clone()).with_set_global(false)).expect("init");
    let mut span = warm.tracer("warm-up", None).start("warm-up");
    span.end();
    warm.force_flush(None).await.expect("flush");
    warm.shutdown().await.expect("shutdown");
    poll(
        "warm-up list_services",
        Duration::from_secs(120),
        || client.traces().list_services(),
        |s| s.contains(&service),
    )
    .await;

    let query: Arc<dyn TraceQueryClient> = Arc::new(client.traces().clone());
    let name = service.clone();
    let report = ConformanceRunner::new(conformance_dir())
        .capabilities(["query", "services"])
        .var("serviceName", service.clone())
        .handler(TracingHandler::new(move || {
            let query = Arc::clone(&query);
            let name = name.clone();
            async move {
                Ok(TracingTarget {
                    handle: init_tracing(tracing_config(name).with_set_global(false))?,
                    query: Some(query),
                })
            }
        }))
        .run()
        .await
        .expect("scenarios load");
    check(&report);
    client.close().await;
}
