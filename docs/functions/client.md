# Client and status

`SkyWalkingClient` connects to one OAP server. It owns the connections of three endpoints that are
all derived from `url` by default:

| Endpoint | Default | Used by |
| - | - | - |
| REST / GraphQL (`url` + `graphqlPath`) | `http://127.0.0.1:12800/graphql` | `health`, `ping`, `serverVersion`, `graphql`, `query`, `logs`, native logs (`/v3/logs`) |
| Zipkin query API (`zipkinUrl`) | `http://<url host>:9412/zipkin` | `traces` for OTLP spans (needs `SW_QUERY_ZIPKIN=default` on OAP) |
| OTLP gRPC receiver (`otlpEndpoint`) | `http://<url host>:11800` | `initTracing` |

The client exposes three query objects that share its connections: `query` (the GraphQL API,
[Query API](query.md)), `traces` (the category `TraceQueryClient`, [Trace queries](traces.md)) and
`logs` (the category `LogQueryClient`, [Logs](logs.md)).

=== "TypeScript"

    ```ts
    import { SkyWalkingClient } from "@grow-codes/apache-skywalking";

    const client = SkyWalkingClient.fromEnv();            // or new SkyWalkingClient({ url: "http://oap:12800" })
    const { query, traces, logs } = client;
    ```

=== "Rust"

    ```rust
    use grow_apache_skywalking::{SkyWalkingClient, SkyWalkingConfig};

    let client = SkyWalkingClient::from_env()?; // or SkyWalkingClient::new(SkyWalkingConfig::builder().url("http://oap:12800").build()?)?
    let (query, traces, logs) = (client.query(), client.traces(), client.logs());
    ```

=== "Go"

    ```go
    client, err := skywalking.NewClientFromEnv() // or skywalking.NewClient(skywalking.Config{CommonConfig: skywalking.CommonConfig{URL: "http://oap:12800"}})
    if err != nil {
        return err
    }
    defer client.Close()
    q, tr, lq := client.Query(), client.Traces(), client.Logs()
    ```

=== "Python"

    ```python
    from grow_apache_skywalking import AsyncSkyWalkingClient, SkyWalkingClient, SkyWalkingConfig

    client = SkyWalkingClient.from_env()  # or SkyWalkingClient(SkyWalkingConfig(url="http://oap:12800"))
    query, traces, logs = client.query, client.traces, client.logs
    aclient = AsyncSkyWalkingClient.from_env()  # same API, awaitable
    ```

=== "Java"

    ```java
    SkyWalkingClient client = SkyWalkingClient.fromEnv(); // or SkyWalkingClient.create(SkyWalkingConfig.builder().url("http://oap:12800").build())
    var query = client.query();
    var traces = client.traces();
    var logs = client.logs();
    ```

## health

`GET /healthcheck` (OAP health checker, `SW_HEALTH_CHECKER=default`): 200 → `ok`, 503 →
`unhealthy`. When the route does not exist (404) it falls back to GraphQL `checkHealth` (score 0
→ `ok`). It never throws for an unhealthy or unreachable server (`unreachable`); only
cancellation and configuration errors are raised.

=== "TypeScript"

    ```ts
    const health = await client.health(); // { healthy, status: "ok" | "unhealthy" | "unreachable", details }
    ```

=== "Rust"

    ```rust
    let health = client.health().await?;
    ```

=== "Go"

    ```go
    health, err := client.Health(ctx)
    ```

=== "Python"

    ```python
    health = client.health()
    ```

=== "Java"

    ```java
    HealthStatus health = client.health();
    ```

## ping

A timed GraphQL round trip (`getTimeInfo`; also caches the OAP time zone used for query windows).
OAP has no query authentication, so `AUTHENTICATION`/`PERMISSION_DENIED` only come from a gateway
in front of it. Returns the latency: TypeScript and Python milliseconds, Rust/Go/Java a duration.

=== "TypeScript"

    ```ts
    const ms = await client.ping();
    ```

=== "Rust"

    ```rust
    let latency = client.ping().await?; // Duration
    ```

=== "Go"

    ```go
    latency, err := client.Ping(ctx) // time.Duration
    ```

=== "Python"

    ```python
    ms = client.ping()
    ```

=== "Java"

    ```java
    Duration latency = client.ping();
    ```

## serverVersion

GraphQL `{version}`, for example `10.4.0-b272da3`. An empty answer is `UNSUPPORTED`.

=== "TypeScript"

    ```ts
    const version = await client.serverVersion();
    ```

=== "Rust"

    ```rust
    let version = client.server_version().await?;
    ```

=== "Go"

    ```go
    version, err := client.ServerVersion(ctx)
    ```

=== "Python"

    ```python
    version = client.server_version()
    ```

=== "Java"

    ```java
    String version = client.serverVersion();
    ```

## graphql

Raw GraphQL against `url + graphqlPath` (same as `query.graphql`). HTTP failures raise (HTTP 400 =
syntax or validation error → `VALIDATION`); a 200 answer is returned as is with `data` (possibly
partial) and `errors`. Mutations are never retried.

=== "TypeScript"

    ```ts
    const res = await client.graphql<{ version: string }>("{ version }");
    console.log(res.data?.version, res.errors);
    ```

=== "Rust"

    ```rust
    let res = client.graphql("{ version }", serde_json::json!({})).await?;
    ```

=== "Go"

    ```go
    res, err := client.GraphQL(ctx, "{ version }", nil)
    if err == nil {
        err = res.Err("version") // GraphQL errors as an *Error
    }
    ```

=== "Python"

    ```python
    res = client.graphql("query($n: String!) { findService(serviceName: $n) { id } }", {"n": "checkout"})
    ```

=== "Java"

    ```java
    GraphQLResponse res = client.graphql("{ version }");
    ```

## logClient / initTracing

`logClient` returns a batching log client that ships to the native `/v3/logs` API
([Logs](logs.md)); `initTracing` sets up OpenTelemetry tracing that exports OTLP/gRPC to OAP
([Tracing setup](tracing-setup.md)). Both are owned by the client: `close()` flushes and shuts
them down.

## close

Flushes and closes the log clients and tracing handles created by the client, then releases the
connections. Idempotent. Every later call fails with `CANCELLED` in all five languages.

=== "TypeScript"

    ```ts
    await client.close();
    ```

=== "Rust"

    ```rust
    client.close().await;
    ```

=== "Go"

    ```go
    _ = client.Close()
    ```

=== "Python"

    ```python
    client.close()          # or: with SkyWalkingClient.from_env() as client: ...
    await aclient.aclose()  # async client
    ```

=== "Java"

    ```java
    client.close();         // or try-with-resources
    ```
