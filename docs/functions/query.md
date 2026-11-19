# Query API (GraphQL)

`SkyWalkingQueryClient` (`client.query`) wraps OAP's GraphQL query protocol on port 12800: services,
endpoints, instances, native traces, logs and topology, plus the raw `graphql` call. It reads what
**native** sources store — SkyWalking agents, native segments and logs (`/v3/segments`,
`/v3/logs`) and OTLP **logs**. OTLP **spans** are stored in OAP's Zipkin model and are read with
[Trace queries](traces.md) instead.

Common rules:

- **Time windows** (`range`): default the last hour up to now, step `MINUTE`, formatted in the OAP
  time zone (read once with `getTimeInfo` and cached).
- **Ids** of real services are derived without a lookup: service `base64(name).1`, instance and
  endpoint `<serviceId>_base64(name)` (helpers `serviceId`/`instanceId`/`endpointId`, Go
  `ServiceID`/`InstanceID`/`EndpointID`).
- **Native trace ids** are opaque strings (`<hex>.<n>.<ms>`) and are kept verbatim.
- Missing required arguments and invalid paging are `VALIDATION` errors before any request. GraphQL
  resolver errors become `VALIDATION` (`UNSUPPORTED` when OAP reports an
  `UnsupportedOperationException`).
- Metadata (services, instances, endpoints) appears about 30–40 s after the first data: OAP flushes
  its metadata index periodically.

## listServices

GraphQL `listServices(layer)`. `layer` omitted / `null` = all layers; an unknown layer returns an
empty list. OAP has no time filter for services, so `range` is accepted for symmetry and ignored
(Go omits the parameter). Services that only sent OTLP **spans** are not listed here; use
`client.traces.listServices()`.

=== "TypeScript"

    ```ts
    const all = await client.query.listServices();
    const general = await client.query.listServices("GENERAL"); // SkyWalkingService[] {id, name, group, shortName, layers, normal}
    ```

=== "Rust"

    ```rust
    let all = client.query().list_services(None, None).await?;
    let general = client.query().list_services(Some("GENERAL"), None).await?;
    ```

=== "Go"

    ```go
    all, err := client.Query().ListServices(ctx, "")
    general, err := client.Query().ListServices(ctx, "GENERAL")
    ```

=== "Python"

    ```python
    all_services = client.query.list_services()
    general = client.query.list_services("GENERAL")
    ```

=== "Java"

    ```java
    List<SkyWalkingService> all = client.query().listServices();
    List<SkyWalkingService> general = client.query().listServices("GENERAL");
    ```

## findService

GraphQL `findService(serviceName)`; `null` / `None` / `nil` / `Optional.empty()` when unknown.

=== "TypeScript"

    ```ts
    const svc = await client.query.findService("checkout"); // SkyWalkingService | null
    ```

=== "Rust"

    ```rust
    let svc = client.query().find_service("checkout").await?; // Option<SkyWalkingService>
    ```

=== "Go"

    ```go
    svc, err := client.Query().FindService(ctx, "checkout") // *skywalking.Service, nil when unknown
    ```

=== "Python"

    ```python
    svc = client.query.find_service("checkout")
    ```

=== "Java"

    ```java
    Optional<SkyWalkingService> svc = client.query().findService("checkout");
    ```

## listEndpoints

GraphQL `findEndpoint(keyword, serviceId, limit)` (default limit 100). `keyword` is a **tokenised**
match: `downstream` finds `/native/downstream`, `down` does not. No duration is sent (OAP 10.4
with BanyanDB returns nothing when one is set).

=== "TypeScript"

    ```ts
    const eps = await client.query.listEndpoints("checkout", "orders", { limit: 20 });
    ```

=== "Rust"

    ```rust
    let eps = client.query().list_endpoints("checkout", Some("orders"), Some(20)).await?;
    ```

=== "Go"

    ```go
    eps, err := client.Query().ListEndpoints(ctx, "checkout", "orders", 20) // 0 = default limit
    ```

=== "Python"

    ```python
    eps = client.query.list_endpoints("checkout", "orders", limit=20)
    ```

=== "Java"

    ```java
    List<SkyWalkingEndpoint> eps = client.query().listEndpoints("checkout", "orders", 20);
    ```

## listInstances

GraphQL `listInstances(duration, serviceId)`: instances active in `range` (default last hour).

=== "TypeScript"

    ```ts
    const instances = await client.query.listInstances("checkout", { start: new Date(Date.now() - 6 * 3_600_000) });
    ```

=== "Rust"

    ```rust
    let instances = client.query().list_instances("checkout", None).await?;
    ```

=== "Go"

    ```go
    instances, err := client.Query().ListInstances(ctx, "checkout", skywalking.TimeRange{})
    ```

=== "Python"

    ```python
    instances = client.query.list_instances("checkout")
    ```

=== "Java"

    ```java
    List<SkyWalkingInstance> instances = client.query().listInstances("checkout");
    ```

## queryTraces

GraphQL `queryTraces(condition)` — whole native traces (all segments), the V2 trace query. Filters:
`service`, `instance` and `endpoint` (both need `service`), `traceId`, `traceState`
(`ALL` default, `SUCCESS`, `ERROR`), `minDuration`/`maxDuration` in ms, `tags` (only keys in the
server's `SW_SEARCHABLE_TAG_KEYS` can match), `range` (omitted when only `traceId` is given),
`paging` (page 1, size 20 by default; size ≤ 10 000) and `order` (`BY_START_TIME` default,
`BY_DURATION`). The legacy `queryBasicTraces` is not used: it is unsupported on BanyanDB.

=== "TypeScript"

    ```ts
    const res = await client.query.queryTraces({
      service: "checkout",
      traceState: "ERROR",
      minDurationMs: 100,
      tags: { "http.method": "GET" },
      paging: { pageNum: 1, pageSize: 10 },
    });
    for (const t of res.traces) console.log(t.traceId, t.spans.length);
    ```

=== "Rust"

    ```rust
    use grow_apache_skywalking::{QueryTracesOptions, TraceState};

    let res = client
        .query()
        .query_traces(&QueryTracesOptions::service("checkout").trace_state(TraceState::Error).tag("http.method", "GET"))
        .await?;
    ```

=== "Go"

    ```go
    page, err := client.Query().QueryTraces(ctx, skywalking.QueryTracesOptions{
        Service:     "checkout",
        TraceState:  skywalking.TraceStateError,
        MinDuration: 100 * time.Millisecond,
        Tags:        map[string]string{"http.method": "GET"},
        PageSize:    10,
    })
    ```

=== "Python"

    ```python
    res = client.query.query_traces(
        service="checkout", trace_state="ERROR", min_duration_ms=100, tags={"http.method": "GET"}
    )
    ```

=== "Java"

    ```java
    QueryTracesResult res = client.query().queryTraces(
        QueryTracesOptions.builder()
            .service("checkout")
            .traceState(TraceState.ERROR)
            .minDurationMs(100L)
            .tag("http.method", "GET")
            .build());
    ```

## getTrace

GraphQL `queryTrace(traceId)`: one native trace with every span (segment id, span id, parent span
id, refs, tags, logs, component, layer, peer, error flag). `null` when unknown. OTLP trace ids are
never native — read them with `client.traces.getTrace`.

=== "TypeScript"

    ```ts
    const trace = await client.query.getTrace("2a2e04e8d1114b14925c04a6321ca26c.38.17315284473370001");
    ```

=== "Rust"

    ```rust
    let trace = client.query().get_trace(native_id).await?; // Option<NativeTrace>
    ```

=== "Go"

    ```go
    trace, err := client.Query().GetTrace(ctx, nativeID) // *skywalking.NativeTrace, nil when unknown
    ```

=== "Python"

    ```python
    trace = client.query.get_trace(native_id)
    ```

=== "Java"

    ```java
    Optional<NativeTrace> trace = client.query().getTrace(nativeId);
    ```

## queryLogs

GraphQL `queryLogs(condition)`. Filters: `service`, `instance`, `endpoint` (both need `service`),
`traceId` (native or 32-hex OpenTelemetry id; with `segmentId` and `spanId` for one span),
`keywords` / `excludingKeywords`, `tags` (keys must be in `SW_SEARCHABLE_LOGS_TAG_KEYS`), `range`
(default last hour; omitted when only `traceId` is given), `paging` and `order` (`DES` default,
`ASC`).

!!! warning "Keywords on BanyanDB"
    OAP with BanyanDB storage silently ignores `keywordsOfContent`. The SDK asks the server
    (`capabilities().logsByKeywords`) and, when keyword search is not supported, filters the
    returned page on the client (case-insensitive substring over the content). Use
    [`logs.search`](logs.md) for "find logs containing X" across pages.

=== "TypeScript"

    ```ts
    const res = await client.query.queryLogs({ service: "checkout", tags: { level: "ERROR" }, order: "ASC" });
    const byTrace = await client.query.queryLogs({ traceId });
    ```

=== "Rust"

    ```rust
    use grow_apache_skywalking::QueryLogsOptions;

    let res = client.query().query_logs(&QueryLogsOptions::service("checkout").tag("level", "ERROR")).await?;
    let by_trace = client.query().query_logs(&QueryLogsOptions::trace_id(trace_id)).await?;
    ```

=== "Go"

    ```go
    page, err := client.Query().QueryLogs(ctx, skywalking.QueryLogsOptions{
        Service: "checkout",
        Tags:    map[string]string{"level": "ERROR"},
        Order:   skywalking.OrderAsc,
    })
    ```

=== "Python"

    ```python
    res = client.query.query_logs(service="checkout", tags={"level": "ERROR"}, order="ASC")
    by_trace = client.query.query_logs(trace_id=trace_id)
    ```

=== "Java"

    ```java
    QueryLogsResult res = client.query().queryLogs(
        QueryLogsOptions.builder().service("checkout").tag("level", "ERROR").build());
    ```

## listLogTagKeys

GraphQL `queryLogTagAutocompleteKeys(duration)`: log tag keys seen in `range`, sorted.

=== "TypeScript"

    ```ts
    const keys = await client.query.listLogTagKeys();
    ```

=== "Rust"

    ```rust
    let keys = client.query().list_log_tag_keys(None).await?;
    ```

=== "Go"

    ```go
    keys, err := client.Query().ListLogTagKeys(ctx, skywalking.TimeRange{})
    ```

=== "Python"

    ```python
    keys = client.query.list_log_tag_keys()
    ```

=== "Java"

    ```java
    List<String> keys = client.query().listLogTagKeys();
    ```

## topology

GraphQL `getGlobalTopology(duration, layer)` or, with `service`, `getServiceTopology(serviceId,
duration)`. Nodes (with the virtual `User` node) and calls with detect points and components.
Native traffic only: OTLP spans create no topology edges.

=== "TypeScript"

    ```ts
    const global = await client.query.topology();
    const around = await client.query.topology(undefined, { service: "checkout" });
    ```

=== "Rust"

    ```rust
    use grow_apache_skywalking::TopologyOptions;

    let global = client.query().topology(None, &TopologyOptions::default()).await?;
    ```

=== "Go"

    ```go
    topo, err := client.Query().Topology(ctx, skywalking.TimeRange{}, skywalking.TopologyOptions{Service: "checkout"})
    ```

=== "Python"

    ```python
    topo = client.query.topology(service="checkout")
    ```

=== "Java"

    ```java
    Topology topo = client.query().topology();
    ```

## timeInfo / capabilities

`timeInfo` is GraphQL `getTimeInfo` (OAP time zone and clock). `capabilities` probes
`hasQueryTracesV2Support` and `supportQueryLogsByKeywords` once and caches the answer
(`queryTracesV2`, `logsByKeywords`).

=== "TypeScript"

    ```ts
    const { timezone, currentTimestamp } = await client.query.timeInfo();
    const caps = await client.query.capabilities();
    ```

=== "Rust"

    ```rust
    let info = client.query().time_info().await?;
    let caps = client.query().capabilities().await?;
    ```

=== "Go"

    ```go
    info, err := client.Query().TimeInfo(ctx)
    caps, err := client.Query().Capabilities(ctx)
    ```

=== "Python"

    ```python
    info = client.query.time_info()
    caps = client.query.capabilities()
    ```

=== "Java"

    ```java
    TimeInfo info = client.query().timeInfo();
    SkyWalkingCapabilities caps = client.query().capabilities();
    ```

## graphql

The raw call, see [Client](client.md#graphql).
