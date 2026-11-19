# Functions

The Apache SkyWalking API of this SDK. Every function exists in all five languages with the same
behaviour, named per language convention (`listServices` in TypeScript and Java, `list_services`
in Rust and Python, `ListServices` in Go). Each module page shows every function with an example
per language.

| Module | Object | Functions | Page |
| - | - | - | - |
| Client | `SkyWalkingClient` | `health`, `ping`, `serverVersion`, `graphql`, `logClient`, `initTracing`, `close` (+ `createLogClient`) | [Client and status](functions/client.md) |
| Query (GraphQL) | `client.query` — `SkyWalkingQueryClient` | `listServices`, `findService`, `listEndpoints`, `listInstances`, `queryTraces`, `getTrace`, `queryLogs`, `listLogTagKeys`, `topology`, `timeInfo`, `capabilities`, `graphql` | [Query API](functions/query.md) |
| Trace queries | `client.traces` — `SkyWalkingTraceQueryClient` (`TraceQueryClient`) | `getTrace`, `searchTraces`, `listServices`, `listOperations`, `getDependencies`, `waitForTrace` | [Trace queries](functions/traces.md) |
| Logs | `logClient()` / `createLogClient()`, `client.logs` — `SkyWalkingLogQueryClient` (`LogQueryClient`) | `send`, `sendBatch`, `flush`, `close`, `stats`, `logger`, `search`, `tail`, `listStreams`, `listLabels`, `waitForLog` | [Logs](functions/logs.md) |
| Tracing setup | `initTracing`, `Sw8Propagator` | `initTracing`, span helpers, `encodeSw8`/`decodeSw8`, `getSw8Context`/`withSw8Context` | [Tracing setup and sw8](functions/tracing-setup.md) |

## Integration tests

Every function above is called against a real OAP 10.4 + BanyanDB in the Docker suite of each
language (`<lang>/integration/`), plus the shared conformance scenarios `core.health`,
`logging.batch`, `logging.redaction`, `logging.roundtrip`, `tracing.services` and `tracing.tree`.
Paths the container cannot serve (OTLP/HTTP, Zipkin `/dependencies` data, `/healthcheck` 503,
gateway 401/403/429, server-side log keywords) are covered by unit tests; see
[Compatibility](compatibility.md).

## Service configuration

| Setting | Default | Environment variables (precedence order) |
| - | - | - |
| `url` — OAP REST/GraphQL base (a trailing `/graphql` is stripped) | `http://127.0.0.1:12800` | `GROW_APACHE_SKYWALKING_URL`, `GROW_SKYWALKING_URL`, `SW_GRAPHQL_URL`, `SW_OAP_ADDRESS` |
| `graphqlPath` | `/graphql` | `GROW_APACHE_SKYWALKING_GRAPHQL_PATH` |
| `otlpEndpoint` — OTLP gRPC receiver | `http://<url host>:11800` | `GROW_APACHE_SKYWALKING_OTLP_ENDPOINT`, `GROW_SKYWALKING_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `SW_AGENT_COLLECTOR_BACKEND_SERVICES` (`host:port`) |
| `otlpProtocol` — `grpc` or `http/protobuf` | `grpc` | `GROW_APACHE_SKYWALKING_OTLP_PROTOCOL`, `GROW_SKYWALKING_OTLP_PROTOCOL`, `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL`, `OTEL_EXPORTER_OTLP_PROTOCOL` |
| `otlpHeaders` — extra exporter headers (`k=v,k2=v2`) | none | `GROW_APACHE_SKYWALKING_OTLP_HEADERS`, `OTEL_EXPORTER_OTLP_TRACES_HEADERS`, `OTEL_EXPORTER_OTLP_HEADERS` |
| `authentication` — OAP gRPC token (secret) | none | `GROW_APACHE_SKYWALKING_AUTHENTICATION`, `GROW_SKYWALKING_AUTHENTICATION`, `SW_AUTHENTICATION`, `SW_AGENT_AUTHENTICATION` |
| `zipkinUrl` — Zipkin query API | `http://<url host>:9412/zipkin` | `GROW_APACHE_SKYWALKING_ZIPKIN_URL`, `GROW_SKYWALKING_ZIPKIN_URL`, `SW_ZIPKIN_ADDRESS` (`/zipkin` appended) |
| `traceBackend` — `auto`, `zipkin`, `native` | `auto` | `GROW_APACHE_SKYWALKING_TRACE_BACKEND`, `GROW_SKYWALKING_TRACE_BACKEND` |

The common settings (timeouts, retries, TLS, proxy, headers, hooks, logger) are on
[Configuration](configuration.md); `GROW_SKYWALKING_*` (the short form from the stack catalog) is
accepted as an alias after `GROW_APACHE_SKYWALKING_*` for every setting that has a vendor variable.
