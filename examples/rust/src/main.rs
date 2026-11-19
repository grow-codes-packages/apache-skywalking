// Created by sdk-kit 0.1.0; owned by this repository (Apache SkyWalking tour).
//! Apache SkyWalking Rust SDK tour: configuration from the environment, health/version,
//! OpenTelemetry tracing over OTLP/gRPC, native logs correlated with the trace, and reading
//! everything back (Zipkin query API for OTLP spans, GraphQL for logs, services and topology).
//!
//! ```sh
//! GROW_APACHE_SKYWALKING_URL=http://127.0.0.1:12800 cargo run
//! ```
//!
//! OAP needs `SW_RECEIVER_ZIPKIN=default` and `SW_QUERY_ZIPKIN=default` (port 9412) for OTLP
//! spans to be readable; the OTLP receiver listens on gRPC port 11800.

use std::time::Duration;

use grow_apache_skywalking as sdk;
use sdk::logging::{attrs, LogClient, LogQueryClient, SearchOptions};
use sdk::tracing::{current_trace_id, traced, TraceQueryClient, WaitForTraceOptions};
use sdk::{
    QueryLogsOptions, SkyWalkingClient, SkyWalkingConfig, SkyWalkingLogClientOptions,
    TopologyOptions,
};

#[tokio::main]
async fn main() {
    if let Err(err) = run().await {
        // Canonical code + HTTP status; the message never contains credentials.
        eprintln!("error [{}] {:?}: {err}", err.code(), err.http_status());
        std::process::exit(1);
    }
}

async fn run() -> sdk::Result<()> {
    // explicit > GROW_APACHE_SKYWALKING_* > vendor variables (SW_GRAPHQL_URL, …) > defaults
    let config = SkyWalkingConfig::from_env()?;
    println!("config: {config:?}"); // the OAP token prints as [REDACTED]
    let client = SkyWalkingClient::new(config)?;
    let health = client.health().await?; // never fails for an unreachable service
    println!("health: {health:?}");
    if !health.healthy {
        std::process::exit(1);
    }
    println!("server version: {}", client.server_version().await?);

    // 1. Traces: OTLP/gRPC to the OAP receiver (11800), installed globally.
    let tracing =
        client.init_tracing(sdk::tracing_config("example-checkout").with_environment("dev"))?;
    let logs = client.log_client(SkyWalkingLogClientOptions::service("example-checkout"))?;
    let log = logs.logger(Some("checkout"), attrs! {});
    let trace_id = traced("GET /checkout", async {
        traced("select orders", async {
            tokio::time::sleep(Duration::from_millis(20)).await;
            Ok::<_, sdk::Error>(())
        })
        .await?;
        // 2. Logs: native /v3/logs, correlated with the active span (trace id + span id).
        log.info("checkout finished", attrs! { "items" => 3 });
        Ok::<_, sdk::Error>(current_trace_id().unwrap_or_default())
    })
    .await?;
    tracing.force_flush(None).await?;
    logs.flush(None).await?;

    // 3. Read back. OAP stores span names lower-cased; services are listed ~30 s after first data.
    let trace = client
        .traces()
        .wait_for_trace(
            &trace_id,
            WaitForTraceOptions::default()
                .timeout(Duration::from_secs(60))
                .min_spans(2),
        )
        .await?;
    for span in &trace.spans {
        println!("span {} {} µs", span.name, span.duration_micros);
    }
    let records = client
        .logs()
        .search(&format!("trace_id={trace_id}"), SearchOptions::default())
        .await?;
    for r in &records.records {
        println!("correlated log: {} {}", r.severity_text, r.body);
    }
    let warnings = client
        .query()
        .query_logs(&QueryLogsOptions::service("example-checkout").tag("level", "WARN"))
        .await?;
    println!("warnings in the last hour: {}", warnings.logs.len());
    let services = client.query().list_services(None, None).await?;
    println!(
        "services: {:?}",
        services.iter().map(|s| &s.name).collect::<Vec<_>>()
    );
    let topology = client
        .query()
        .topology(None, &TopologyOptions::default())
        .await?;
    println!(
        "topology: {} nodes, {} calls",
        topology.nodes.len(),
        topology.calls.len()
    );
    client.close().await; // flushes the log client and shuts tracing down
    Ok(())
}
