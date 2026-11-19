# Trace queries

`SkyWalkingTraceQueryClient` (`client.traces`) implements the category interface
**`TraceQueryClient`** (`getTrace`, `searchTraces`, `listServices`, `listOperations`,
`getDependencies`, `waitForTrace`) with the normalized model `Trace { traceId, spans[] }` /
`Span { traceId, spanId, parentSpanId, name, kind, serviceName, startMicros, endMicros,
durationMicros, status, attributes, events, links }`.

OAP keeps traces in **two stores**:

| Store | Written by | Read through | Trace ids |
| - | - | - | - |
| Zipkin model | OTLP spans (what `initTracing` sends) | Zipkin query API `:9412/zipkin` (`SW_QUERY_ZIPKIN=default`) | 32 hex (normalized) |
| Native model | SkyWalking agents, `/v3/segments` | GraphQL | opaque `<hex>.<n>.<ms>`, kept verbatim |

`SkyWalkingConfig.traceBackend` (`GROW_APACHE_SKYWALKING_TRACE_BACKEND`) selects the store:

| Method | `zipkin` | `native` | `auto` (default) |
| - | - | - | - |
| `getTrace` | Zipkin | GraphQL `queryTrace` | 16/32-hex id → Zipkin, then GraphQL when not found; other ids → GraphQL |
| `searchTraces` | Zipkin `/api/v2/traces` | GraphQL `queryTraces` | Zipkin |
| `listServices`, `listOperations` | Zipkin | GraphQL | union of both, sorted |
| `getDependencies` | `UNSUPPORTED` | native topology | native topology |
| `waitForTrace` | polls `getTrace` | polls `getTrace` | polls `getTrace` |

Rust, Go and Java also offer `withBackend(...)` / `with_backend(...)`, a copy of the client that
reads another store; in TypeScript and Python set `traceBackend` / `trace_backend` in the config.

!!! info "Native ids are kept verbatim (SPEC exception)"
    The SPEC model normalizes trace ids to 32 hex and span ids to 16 hex. Native SkyWalking ids are
    not hex, so `getTrace`, `searchTraces` and `waitForTrace` keep them exactly as OAP returns them:
    the trace id `<hex>.<n>.<ms>` and span ids `"<segmentId>-<spanId>"` (the parent is the same
    segment's parent span, or the `ref` of the first span for cross-segment links). Hex ids from the
    Zipkin store are normalized as usual. The generated sdk-kit trace models normalize ids, so each
    language carries a service-side variant (see [Compatibility](../compatibility.md#native-trace-ids)).

## getTrace

One trace, or `null` / `None` / `nil` / `Optional.empty()` when it does not exist. The Zipkin store
lower-cases names (OAP stores span names lower-cased), turns attributes into string tags and events
into annotations; the SDK maps them back (`error` tag and `otel.status_*` → `status`). A non-hex id
on the `zipkin` backend is a `VALIDATION` error (the OAP 400 text is kept in the message).

=== "TypeScript"

    ```ts
    const otlp = await client.traces.getTrace("4bf92f3577b34da6a3ce929d0e0e4736");
    const native = await client.traces.getTrace("2a2e04e8d1114b14925c04a6321ca26c.38.17315284473370001");
    ```

=== "Rust"

    ```rust
    use grow_apache_skywalking::tracing::TraceQueryClient;

    let trace = client.traces().get_trace("4bf92f3577b34da6a3ce929d0e0e4736").await?; // Option<Trace>
    ```

=== "Go"

    ```go
    trace, err := client.Traces().GetTrace(ctx, "4bf92f3577b34da6a3ce929d0e0e4736") // nil when not found
    ```

=== "Python"

    ```python
    trace = client.traces.get_trace("4bf92f3577b34da6a3ce929d0e0e4736")
    ```

=== "Java"

    ```java
    Optional<Trace> trace = client.traces().getTrace("4bf92f3577b34da6a3ce929d0e0e4736");
    ```

## searchTraces

SPEC search → `TraceSummary[]` (`traceId`, `rootServiceName`, `rootName`, `startMicros`,
`durationMicros`, `spanCount`), default `limit` 20.

- **Zipkin**: `service` → `serviceName`, `operation` → `spanName` (lower-cased), `tags` →
  `annotationQuery` `k=v and …` (keys must be in OAP's `SW_ZIPKIN_SEARCHABLE_TAG_KEYS`), durations in
  µs, `endTs` = `end` (default now), `lookback` = `end − start` when `start` is set.
- **Native**: `queryTraces` with `operation` as endpoint name (needs `service`), durations rounded
  to ms, window default the last hour.

=== "TypeScript"

    ```ts
    const hits = await client.traces.searchTraces({ service: "checkout", tags: { run_id: "42" }, limit: 5 });
    ```

=== "Rust"

    ```rust
    use grow_apache_skywalking::tracing::TraceSearchQuery;

    let hits = client.traces().search_traces(&TraceSearchQuery::service("checkout").tag("run_id", "42").limit(5)).await?;
    ```

=== "Go"

    ```go
    hits, err := client.Traces().SearchTraces(ctx, skywalking.TraceSearchQuery{
        Service: "checkout", Tags: map[string]string{"run_id": "42"}, Limit: 5,
    })
    ```

=== "Python"

    ```python
    from grow_apache_skywalking import TraceSearchQuery

    hits = client.traces.search_traces(TraceSearchQuery(service="checkout", tags={"run_id": "42"}, limit=5))
    ```

=== "Java"

    ```java
    List<TraceSummary> hits = client.traces().searchTraces(
        TraceSearchQuery.builder().service("checkout").tag("run_id", "42").limit(5).build());
    ```

## listServices / listOperations

Service names and the operation names of one service (Zipkin span names and/or native endpoint
names), sorted and unique. New services appear about 30–40 s after their first data.

=== "TypeScript"

    ```ts
    const services = await client.traces.listServices();
    const ops = await client.traces.listOperations("checkout");
    ```

=== "Rust"

    ```rust
    let services = client.traces().list_services().await?;
    let ops = client.traces().list_operations("checkout").await?;
    ```

=== "Go"

    ```go
    services, err := client.Traces().ListServices(ctx)
    ops, err := client.Traces().ListOperations(ctx, "checkout")
    ```

=== "Python"

    ```python
    services = client.traces.list_services()
    ops = client.traces.list_operations("checkout")
    ```

=== "Java"

    ```java
    List<String> services = client.traces().listServices();
    List<String> ops = client.traces().listOperations("checkout");
    ```

## getDependencies

Service → service links from the native topology. `callCount` is always 0: the topology carries no
counts (they would need MQE metrics). OAP 10.4 does not implement Zipkin `/dependencies` (404), so
the `zipkin` backend raises `UNSUPPORTED`; OTLP spans create no topology edges.

=== "TypeScript"

    ```ts
    const links = await client.traces.getDependencies({ start: new Date(Date.now() - 3_600_000) });
    ```

=== "Rust"

    ```rust
    use grow_apache_skywalking::tracing::DependencyOptions;

    let links = client.traces().get_dependencies(DependencyOptions::default()).await?;
    ```

=== "Go"

    ```go
    links, err := client.Traces().GetDependencies(ctx, skywalking.DependencyOptions{})
    ```

=== "Python"

    ```python
    links = client.traces.get_dependencies()
    ```

=== "Java"

    ```java
    List<DependencyLink> links = client.traces().getDependencies(null, null); // default window: last hour
    ```

## waitForTrace

Test helper: polls `getTrace` until the trace has `minSpans` spans (default 1; timeout 30 s,
interval 1 s) and raises `TIMEOUT` otherwise. Native ids are used verbatim, hex ids are normalized.
OTLP spans become readable 5–15 s after export.

=== "TypeScript"

    ```ts
    const trace = await client.traces.waitForTrace(traceId, { timeoutMs: 60_000, minSpans: 2 });
    ```

=== "Rust"

    ```rust
    use std::time::Duration;
    use grow_apache_skywalking::tracing::WaitForTraceOptions;

    let trace = client
        .traces()
        .wait_for_trace(&trace_id, WaitForTraceOptions::default().timeout(Duration::from_secs(60)).min_spans(2))
        .await?;
    ```

=== "Go"

    ```go
    trace, err := client.Traces().WaitForTrace(ctx, traceID, skywalking.WaitForTraceOptions{Timeout: time.Minute, MinSpans: 2})
    ```

=== "Python"

    ```python
    trace = client.traces.wait_for_trace(trace_id, timeout_s=60, min_spans=2)
    ```

=== "Java"

    ```java
    Trace trace = client.traces().waitForTrace(
        traceId, new WaitForTraceOptions(Duration.ofMinutes(1), Duration.ofSeconds(1), 2));
    ```
