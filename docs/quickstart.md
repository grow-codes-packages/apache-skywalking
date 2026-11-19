# Quickstart

Connect to an Apache SkyWalking OAP server, check that it is healthy, send a traced request with a
correlated log and read both back. The client reads `GROW_APACHE_SKYWALKING_URL` (or the vendor
variables `SW_GRAPHQL_URL` / `SW_OAP_ADDRESS`, see [Configuration](configuration.md)); the OTLP
endpoint (`:11800`) and the Zipkin query API (`:9412/zipkin`) are derived from it.

```sh
export GROW_APACHE_SKYWALKING_URL=http://127.0.0.1:12800
export GROW_APACHE_SKYWALKING_AUTHENTICATION=...   # only when OAP sets SW_AUTHENTICATION; never commit it
```

!!! note "OAP settings"
    OTLP spans are only readable when OAP runs with `SW_RECEIVER_ZIPKIN=default` and
    `SW_QUERY_ZIPKIN=default`. Services appear in listings about 30–40 s after their first data.
    See [Compatibility](compatibility.md#server-settings).

=== "TypeScript"

    ```ts
    import { SkyWalkingClient, withSpan } from "@grow-codes/apache-skywalking";

    const client = SkyWalkingClient.fromEnv();
    try {
      console.log(await client.health(), await client.serverVersion());

      const tracing = client.initTracing({ serviceName: "checkout" }); // OTLP/gRPC → :11800
      const logs = client.logClient({ serviceName: "checkout" });     // native /v3/logs
      const traceId = await withSpan("GET /checkout", async (span) => {
        logs.logger("api").info("order accepted"); // carries the trace and span id
        return span.spanContext().traceId;
      });
      await tracing.forceFlush();
      await logs.flush();

      const trace = await client.traces.waitForTrace(traceId, { timeoutMs: 60_000 });
      const found = await client.logs.search(`trace_id=${traceId}`);
      console.log(trace.spans.length, found.records.length);
    } finally {
      await client.close(); // flushes and shuts down tracing and logs
    }
    ```

=== "Rust"

    ```rust
    use std::time::Duration;
    use grow_apache_skywalking as sdk;
    use sdk::logging::{attrs, LogClient, LogQueryClient, SearchOptions};
    use sdk::tracing::{current_trace_id, traced, TraceQueryClient, WaitForTraceOptions};

    #[tokio::main]
    async fn main() -> sdk::Result<()> {
        let client = sdk::SkyWalkingClient::from_env()?;
        println!("{:?} {}", client.health().await?, client.server_version().await?);

        let tracing = client.init_tracing(sdk::tracing_config("checkout"))?;
        let logs = client.log_client(sdk::SkyWalkingLogClientOptions::service("checkout"))?;
        let log = logs.logger(Some("api"), attrs! {});
        let trace_id = traced("GET /checkout", async {
            log.info("order accepted", attrs! {});
            Ok::<_, sdk::Error>(current_trace_id().unwrap_or_default())
        })
        .await?;
        tracing.force_flush(None).await?;
        logs.flush(None).await?;

        let trace = client
            .traces()
            .wait_for_trace(&trace_id, WaitForTraceOptions::default().timeout(Duration::from_secs(60)))
            .await?;
        let found = client.logs().search(&format!("trace_id={trace_id}"), SearchOptions::default()).await?;
        println!("{} spans, {} logs", trace.spans.len(), found.records.len());
        client.close().await;
        Ok(())
    }
    ```

=== "Go"

    ```go
    package main

    import (
        "context"
        "fmt"
        "log"
        "time"

        skywalking "github.com/grow-codes-packages/apache-skywalking/go"
    )

    func main() {
        ctx := context.Background()
        client, err := skywalking.NewClientFromEnv()
        if err != nil {
            log.Fatal(err)
        }
        defer client.Close()

        health, _ := client.Health(ctx)
        fmt.Println("healthy:", health.Healthy, health.Status)

        tracing, err := client.InitTracing(ctx, skywalking.TracingConfig{ServiceName: "checkout"})
        if err != nil {
            log.Fatal(err)
        }
        spanCtx, span := tracing.Tracer("api").Start(ctx, "GET /checkout")
        traceID, _ := skywalking.CurrentTraceID(spanCtx)
        logs, _ := client.NewLogClient(skywalking.LogClientOptions{
            Resource: map[string]skywalking.AttrValue{"service.name": "checkout"},
        })
        logs.Send(spanCtx, skywalking.LogInput{Message: "order accepted", TraceID: traceID})
        span.End()
        _ = tracing.Shutdown(ctx)
        _ = logs.Close(ctx)

        trace, err := client.Traces().WaitForTrace(ctx, traceID, skywalking.WaitForTraceOptions{Timeout: time.Minute})
        if err != nil {
            log.Fatal(err)
        }
        found, err := client.Logs().Search(ctx, "trace_id="+traceID, skywalking.SearchOptions{})
        if err != nil {
            log.Fatal(err)
        }
        fmt.Println(len(trace.Spans), "spans,", len(found.Records), "logs")
    }
    ```

=== "Python"

    ```python
    from grow_apache_skywalking import SkyWalkingClient

    with SkyWalkingClient.from_env() as client:
        print(client.health(), client.server_version())

        tracing = client.init_tracing(service_name="checkout", set_global=False)
        logs = client.log_client(service_name="checkout")
        tracer = tracing.tracer("api")
        with tracer.start_as_current_span("GET /checkout") as span:
            logs.logger("api").info("order accepted")  # carries the trace and span id
        trace_id = format(span.get_span_context().trace_id, "032x")
        tracing.force_flush(30)
        logs.flush(30)

        trace = client.traces.wait_for_trace(trace_id, timeout_s=60)
        found = client.logs.search(f"trace_id={trace_id}")
        print(len(trace.spans), "spans,", len(found.records), "logs")
    ```

    Async code uses `AsyncSkyWalkingClient` with the same methods (`async with`,
    `await client.health()`, `await client.traces.wait_for_trace(...)`).

=== "Java"

    ```java
    import codes.grow.packages.skywalking.SkyWalkingClient;
    import codes.grow.packages.skywalking.logging.BatchingLogClient;
    import codes.grow.packages.skywalking.tracing.Spans;
    import codes.grow.packages.skywalking.tracing.TracingConfig;
    import codes.grow.packages.skywalking.tracing.TracingHandle;
    import codes.grow.packages.skywalking.tracing.query.Trace;

    public final class Quickstart {
      public static void main(String[] args) throws Exception {
        try (SkyWalkingClient client = SkyWalkingClient.fromEnv()) {
          System.out.println(client.health() + " " + client.serverVersion());

          TracingHandle tracing = client.initTracing(TracingConfig.builder("checkout").build());
          BatchingLogClient logs = client.logClient("checkout");
          String traceId =
              Spans.withSpan(
                  "GET /checkout",
                  () -> {
                    logs.logger("api").info("order accepted");
                    return Spans.currentTraceId().orElseThrow();
                  });
          tracing.forceFlush();
          logs.flush();

          Trace trace = client.traces().waitForTrace(traceId);
          var found = client.logs().search("trace_id=" + traceId);
          System.out.println(trace.spans().size() + " spans, " + found.records().size() + " logs");
        }
      }
    }
    ```

## Next steps

- [Functions](functions.md): every Apache SkyWalking function with examples in five languages.
- [Configuration](configuration.md): endpoints, timeouts, retries, TLS, environment variables.
- [Compatibility](compatibility.md): OAP settings and what the server cannot serve.
- [Error handling](errors.md): error codes and retries.
