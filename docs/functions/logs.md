# Logs

Logs go to OAP's **native HTTP log API** (`POST /v3/logs`, a JSON array of `LogData`) and come back
through the GraphQL `queryLogs`. The shared logging layer (batching, back-pressure, redaction,
logger facade, framework adapters — see [Logging](../logging.md)) sits on top.

## logClient / createLogClient

A batching `LogClient` over the native exporter (`SkyWalkingLogExporter`, Go `NativeLogExporter`).
Mapping: resource `service.name` → `service` (default `unknown_service`), `service.instance.id` →
`serviceInstance`, body → text body, severity → the `level` tag, attributes → tags (non-string
values JSON-encoded), and the active OpenTelemetry trace/span id → `traceContext` so that
`relatedTrace` and `trace_id=` searches find the log. Logs are sent **uncompressed**. The native
API is used instead of OTLP logs because OAP drops the top-level trace and span ids of OTLP log
records.

`client.logClient(...)` is owned by the client (`close()` flushes it); `createLogClient` builds a
standalone client with its own connections.

=== "TypeScript"

    ```ts
    import { createLogClient } from "@grow-codes/apache-skywalking";

    const logs = client.logClient({ serviceName: "checkout" });
    logs.logger("api").info("order accepted", { orderId: 42 });
    await logs.flush();
    const standalone = createLogClient({ url: "http://oap:12800" }, { serviceName: "worker" });
    ```

=== "Rust"

    ```rust
    use grow_apache_skywalking::logging::{attrs, LogClient};
    use grow_apache_skywalking::SkyWalkingLogClientOptions;

    let logs = client.log_client(SkyWalkingLogClientOptions::service("checkout"))?;
    logs.logger(Some("api"), attrs! {}).info("order accepted", attrs! { "orderId" => 42 });
    logs.flush(None).await?;
    ```

=== "Go"

    ```go
    logs, err := client.NewLogClient(skywalking.LogClientOptions{
        Resource: map[string]skywalking.AttrValue{"service.name": "checkout"},
    })
    if err != nil {
        return err
    }
    logs.Send(ctx, skywalking.LogInput{Message: "order accepted", Severity: skywalking.SeverityInfo})
    _ = logs.Close(ctx) // flushes
    ```

=== "Python"

    ```python
    from grow_apache_skywalking import create_log_client

    logs = client.log_client(service_name="checkout")
    logs.logger("api").info("order accepted", {"order_id": 42})
    logs.flush(30)
    standalone = create_log_client(service_name="worker")  # config from the environment
    ```

=== "Java"

    ```java
    BatchingLogClient logs = client.logClient("checkout");
    logs.logger("api").info("order accepted", Map.of("orderId", 42));
    logs.flush();
    BatchingLogClient standalone = SkyWalkingClient.createLogClient(
        SkyWalkingConfig.fromEnv(), SkyWalkingLogClientOptions.builder().serviceName("worker").build());
    ```

## search (LogQueryClient)

`client.logs` implements the category `LogQueryClient` (`search`, `tail`, `listStreams`,
`listLabels`, `waitForLog`). The query string is a list of terms:

| Term | Becomes |
| - | - |
| `service=<name>` | GraphQL condition `serviceId` |
| `instance=<name>` | `serviceInstanceId` (needs `service=`) |
| `endpoint=<name>` | `endpointId` (needs `service=`) |
| `trace_id=<id>` | `relatedTrace.traceId` (native or 32-hex id) |
| any other `key=value` | tag filter (key must be in `SW_SEARCHABLE_LOGS_TAG_KEYS`) |
| free text (`"quoted words"` allowed) | client-side, case-insensitive substring match over content and tag values |

Free text is **always filtered on the client**: OAP with BanyanDB ignores `keywordsOfContent`. The
SDK scans raw pages of 1 000 logs (at most 10 pages, `maxScanPages`) until `limit` matches are found.
Results are SPEC `LogRecord`s (OpenTelemetry trace ids restored from the trace context, the `level`
tag → severity). The cursor is `"page:offset:size"`; the default window is the last hour.

=== "TypeScript"

    ```ts
    const page = await client.logs.search('service=checkout level=ERROR "payment failed"', { limit: 50 });
    const more = page.nextCursor && (await client.logs.search("service=checkout", { cursor: page.nextCursor }));
    ```

=== "Rust"

    ```rust
    use grow_apache_skywalking::logging::{LogQueryClient, SearchOptions};

    let page = client.logs().search("service=checkout level=ERROR payment", SearchOptions::default()).await?;
    ```

=== "Go"

    ```go
    page, err := client.Logs().Search(ctx, `service=checkout level=ERROR "payment failed"`, skywalking.SearchOptions{Limit: 50})
    ```

=== "Python"

    ```python
    page = client.logs.search('service=checkout level=ERROR "payment failed"', limit=50)
    ```

=== "Java"

    ```java
    LogSearchResult page = client.logs().search(
        "service=checkout level=ERROR \"payment failed\"", SearchOptions.builder().limit(50).build());
    ```

## tail / waitForLog / listStreams / listLabels

`tail` polls `search` (default every second) and delivers new records once; `waitForLog` polls until
a record matches (test helper, `TIMEOUT` otherwise). `listStreams` returns the service names that
have logs; `listLabels` the log tag keys (`queryLogTagAutocompleteKeys`).

=== "TypeScript"

    ```ts
    const handle = client.logs.tail("service=checkout", (r) => console.log(r.body));
    const rec = await client.logs.waitForLog(`trace_id=${traceId}`, { timeoutMs: 60_000 });
    const streams = await client.logs.listStreams();
    const labels = await client.logs.listLabels();
    handle.stop();
    ```

=== "Rust"

    ```rust
    use grow_apache_skywalking::logging::{LogQueryClient, WaitOptions};

    let rec = client.logs().wait_for_log(&format!("trace_id={trace_id}"), WaitOptions::default()).await?;
    let streams = client.logs().list_streams().await?;
    ```

=== "Go"

    ```go
    rec, err := client.Logs().WaitForLog(ctx, "trace_id="+traceID, skywalking.WaitForLogOptions{Timeout: time.Minute})
    streams, err := client.Logs().ListStreams(ctx)
    labels, err := client.Logs().ListLabels(ctx)
    ```

=== "Python"

    ```python
    rec = client.logs.wait_for_log(f"trace_id={trace_id}", timeout_s=60)
    streams = client.logs.list_streams()
    labels = client.logs.list_labels()
    ```

=== "Java"

    ```java
    LogRecord rec = client.logs().waitForLog("trace_id=" + traceId);
    List<String> streams = client.logs().listStreams();
    List<String> labels = client.logs().listLabels();
    ```
