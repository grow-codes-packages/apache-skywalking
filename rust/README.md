# grow-apache-skywalking (Rust)

Rust SDK for Apache SkyWalking (OAP 10.4) by [Grow Codes](https://grow.codes): the GraphQL query
API, OpenTelemetry tracing exported over OTLP/gRPC, native logs, `sw8` propagation and the SPEC
trace/log query clients. Async on tokio, with an optional blocking client.

```toml
[dependencies]
grow-apache-skywalking = "0.1.0"
# blocking client:   features = ["blocking"]
# test fakes/runner: features = ["testing"] (dev-dependency)
```

```rust,no_run
use grow_apache_skywalking::tracing::TraceQueryClient;
use grow_apache_skywalking::{tracing_config, SkyWalkingClient, SkyWalkingConfig};

#[tokio::main]
async fn main() -> grow_apache_skywalking::Result<()> {
    // explicit > GROW_APACHE_SKYWALKING_* > vendor variables (SW_GRAPHQL_URL, …) > defaults
    let client = SkyWalkingClient::new(SkyWalkingConfig::from_env()?)?;
    println!("{:?} {}", client.health().await?, client.server_version().await?);
    let tracing = client.init_tracing(tracing_config("checkout"))?; // OTLP/gRPC → :11800
    // … spans via the OpenTelemetry API or grow_apache_skywalking::tracing::traced …
    tracing.force_flush(None).await?;
    println!("{:?}", client.traces().list_services().await?);
    client.close().await; // flushes log clients, shuts tracing down
    Ok(())
}
```

| Entry point | What |
| - | - |
| `SkyWalkingClient` | `health`, `ping`, `server_version`, `graphql`, `log_client`, `init_tracing`, `close` |
| `client.query()` | `list_services`, `find_service`, `list_endpoints`, `list_instances`, `query_traces`, `get_trace`, `query_logs`, `list_log_tag_keys`, `topology`, `time_info`, `capabilities`, `graphql` |
| `client.traces()` | SPEC `TraceQueryClient`; `trace_backend` `auto` (default) / `zipkin` / `native` |
| `client.logs()` | SPEC `LogQueryClient`; query syntax `service=… instance=… endpoint=… trace_id=… key=value free text` |
| `init_tracing`, `Sw8Propagator` | OTLP/gRPC exporter to OAP, `sw8` / `sw8-correlation` (extract by default) |

- OTLP spans are read back through OAP's Zipkin query API (`SW_QUERY_ZIPKIN=default`, port 9412);
  native agent traces through GraphQL with their opaque ids; native span ids are
  `<segmentId>-<spanId>`.
- Errors: one `Error` enum with `code()`, `http_status()`, `request_id()`, `is_retryable()`,
  `retry_after()`; messages never contain credentials. GraphQL resolver errors map to
  `VALIDATION` (`UNSUPPORTED` for `UnsupportedOperationException`).
- Retries with full-jitter backoff and `Retry-After`, connect/request/total timeouts,
  cancellation (drop the future), request hooks, `tracing` debug events.
- MSRV 1.88. Licence Apache-2.0. Documentation: https://grow-codes-packages.github.io/apache-skywalking/

Development: `make deps build lint test coverage license pack docs` (see `Makefile`).
