# Compatibility

## Runtimes

| Language | Supported | Tested in CI |
| - | - | - |
| TypeScript | Node.js ≥ 22.12, ESM and CommonJS | Node 22, 24 |
| Rust | MSRV 1.88, edition 2021, tokio | stable, 1.88 |
| Go | Go ≥ 1.26 | 1.26, 1.27 |
| Python | ≥ 3.10 | 3.10, 3.13, 3.14 |
| Java | ≥ 17 (Java 17 bytecode) | Temurin 17, 21, 25 |

## Apache SkyWalking versions

The integration suite runs against `apache/skywalking-oap-server:10.4.0` with
`apache/skywalking-banyandb:0.10.3` storage (the versions of the Grow Codes stack catalog; the
`apache/skywalking-ui:10.4.0` is available in the compose profile `ui`). Other OAP 10.x versions
that expose the same GraphQL query protocol, OTLP receiver and Zipkin query API are expected to
work; report problems as an issue.

## Server settings

OAP needs these settings for every function of the SDK to work:

| Setting | Why |
| - | - |
| `SW_OTEL_RECEIVER=default` with `otlp-traces` in `SW_OTEL_RECEIVER_ENABLED_HANDLERS` (gRPC port **11800**) | receives the OTLP spans of `initTracing` (the image default) |
| `SW_RECEIVER_ZIPKIN=default` **and** `SW_QUERY_ZIPKIN=default` (port **9412**) | OAP converts OTLP spans to its **Zipkin** model; without the Zipkin receiver/query modules the spans are stored but can never be read back, so `client.traces` finds nothing for OTLP trace ids |
| `SW_ZIPKIN_SEARCHABLE_TAG_KEYS`, `SW_SEARCHABLE_TAG_KEYS`, `SW_SEARCHABLE_LOGS_TAG_KEYS` | only listed tag keys can be used as filters (`searchTraces` tags, `queryTraces` tags, log `key=value` terms); the Docker fixture adds `run_id` (and `trace_id`, `span_id` for logs) |
| `SW_HEALTH_CHECKER=default` | `health()` uses `GET /healthcheck`; without it the SDK falls back to GraphQL `checkHealth` |

!!! warning "Grow stacks (platform follow-up)"
    The Apache SkyWalking stack template of the Grow Codes platform does not set
    `SW_RECEIVER_ZIPKIN` / `SW_QUERY_ZIPKIN` and does not expose port 9412 yet. On such a stack OTLP
    traces sent by `initTracing` are stored but not readable through `client.traces`
    (`getTrace` returns nothing, `waitForTrace` times out). Native agent traces, logs, services and
    topology work. A separate platform ticket adds the two variables to the template.

Other behaviour of OAP 10.4 that the SDK takes into account:

- **Metadata delay**: services, instances, endpoints and the Zipkin service/span-name lists appear
  about **30–40 s** after the first data (OAP flushes its metadata index periodically); topology
  edges after about 40 s. OTLP spans are readable 5–15 s after export; native traces and logs on the
  next poll.
- **Span names are lower-cased** in the Zipkin store (`GET /checkout` comes back as
  `get /checkout`); `searchTraces` lower-cases `operation` to match.
- **BanyanDB ignores log keyword search** (`keywordsOfContent` is accepted but has no effect). Free
  text in `logs.search` is always matched on the client (pages of 1 000, at most 10 pages);
  `queryLogs` keywords are filtered on the client when `capabilities().logsByKeywords` is false.
- **BanyanDB disk and memory**: BanyanDB refuses writes when its data volume is above 95 % full
  (OAP then crash-loops with `failed to publish property update`), and its memory protector trips
  with a 1 GiB limit during schema installation. The Docker fixture keeps BanyanDB data on tmpfs with
  1.5 GiB.
- **Authentication**: `SW_AUTHENTICATION` protects only gRPC ingest (OTLP, agents); a missing or
  wrong token is gRPC status 7 (`PERMISSION_DENIED`). GraphQL and `/v3/*` stay open: put OAP behind
  an authenticating gateway for queries and pass its credentials in `headers`.
- **Official agents**: for full native propagation (sw8 inject with real segment ids, automatic
  instrumentation, native topology for your own services) use the official
  [SkyWalking language agents](https://skywalking.apache.org/docs/#Agent). This SDK reads their data
  through `client.query` and the `native` trace backend.

## Functions the service or a language cannot serve

Every public function is called against the real service in the Docker suite. These paths cannot
be produced by the container and are covered by unit tests:

| Function / path | Languages | Reason |
| - | - | - |
| `initTracing` with `http/protobuf` | all | OAP 10.4 has **no OTLP/HTTP receiver** (`/v1/traces` is 404); only meant for an OpenTelemetry Collector in front of OAP |
| `initTracing` with `native` | all | not supported (`CONFIG`); use the official SkyWalking agents |
| `traces.getDependencies` on the `zipkin` backend | all | OAP does not implement Zipkin `/api/v2/dependencies` (404 → `UNSUPPORTED`); the native topology is used otherwise, with `callCount` 0 |
| `health()` → `unhealthy` (503) | all | the healthy fixture never answers 503 |
| `AUTHENTICATION` / `PERMISSION_DENIED` / `RATE_LIMITED` on queries | all | OAP has no query auth or rate limit; these come only from a gateway |
| gRPC export rejected (status 7) | all | the fixture runs without `SW_AUTHENTICATION` |
| server-side log keywords | all | BanyanDB ignores them (Elasticsearch storage would honour them) |
| `queryBasicTraces` | — | not used: unsupported on BanyanDB; `queryTraces` (V2) is used |
| `listEndpoints` with a duration | — | not sent: OAP 10.4 + BanyanDB returns `[]` with one |
| gRPC to `https://` endpoints | Rust | the template does not enable a tonic TLS feature; use `http://` inside the cluster or a TLS-terminating proxy |

## Skipped conformance scenarios

| Scenario | Reason |
| - | - |
| — | none: `core.health`, `logging.batch`, `logging.redaction`, `logging.roundtrip`, `tracing.services` (with a service warm-up for the metadata delay) and `tracing.tree` pass in all five languages |

## Native trace ids

SPEC §5 normalizes trace ids to 32 hex and span ids to 16 hex. Native SkyWalking ids are opaque
(`<hex>.<n>.<ms>` traces, `<segmentId>-<spanId>` spans), so the native backend keeps them verbatim
(coordinator decision, SPEC exception). `getTrace`, `searchTraces` and `waitForTrace` return them
unchanged in every language; hex ids from the Zipkin store are still normalized.

The generated sdk-kit trace models enforce hex ids, so each language needs a service-side variant
(**template gap**, to be fixed in sdk-kit):

| Language | How native ids are carried |
| - | - |
| TypeScript | plain model interfaces; no normalization on construction |
| Rust | plain structs built directly (`Trace::new` does not normalize) |
| Go | plain structs; `NormalizeTraceID` is only called for hex ids |
| Python | `NativeIdTrace`, `NativeIdSpan`, `NativeIdSpanLink` subclasses of the frozen dataclasses skip the hex normalization |
| Java | `tracing/query/Span`, `Trace` and `TraceSummary` taken over from the template (`LenientIds`: hex-like ids normalized, others kept trimmed) |

## Language differences

The API is the same in every language; only the conventions differ (naming, durations, async
model). Java cannot change the process environment, so `loadEnv` sets **system properties**
instead and returns the names it set. Deliberate differences:

| Topic | Difference |
| - | - |
| sw8 context helpers | idiomatic names and argument order: TypeScript `getSw8Context(ctx)` / `withSw8Context(ctx, sw8)`, Rust `sw8_context(&cx)` / `with_sw8_context(&cx, inject)`, Go `SW8FromContext(ctx)` / `ContextWithSW8(ctx, sw8)` (+ `ContextWithSW8Correlation`), Python `get_sw8_context(context=None)` / `with_sw8_context(sw8, context=None)` (context last and optional, like `opentelemetry.context`), Java `Sw8.fromContext(ctx)` / `Sw8.withContext(ctx, sw8[, correlation])` |
| `TracingConfig` protocol default | TypeScript and Go: the field is optional; unset → `SkyWalkingConfig.otlpProtocol` (`grpc`). Rust, Python, Java: the generated config always has a protocol with the template default `http/protobuf`, which counts as unset and becomes `SkyWalkingConfig.otlpProtocol`; select `http/protobuf` through `SkyWalkingConfig.otlpProtocol` / `OTEL_EXPORTER_OTLP_PROTOCOL` (Python also via the `protocol=` keyword) |
| `query.listServices(layer, range)` | Go `ListServices(ctx, layer)` has no `range`: OAP ignores it for services in every language |
| `traces.withBackend` | Rust, Go and Java offer a copy for another backend; TypeScript and Python select it with `traceBackend` in the config |
| `ping()` result | TypeScript and Python milliseconds (number / float), Rust/Go/Java a duration |
| Go `GRAPHQL_URL` | Go also accepts `GROW_APACHE_SKYWALKING_GRAPHQL_URL` / `GROW_SKYWALKING_GRAPHQL_URL` (a full GraphQL URL split into `url` and `graphqlPath`); the other languages take `URL` + `GRAPHQL_PATH` |
| Zipkin error text | OAP answers Zipkin errors with `content-type: text/*`; the SDK keeps the server text in the `VALIDATION` message in every language (Go rewrites the content type, Python and Java build the error in the service layer, TypeScript and Rust read any text body) |
| closed client | every call after `close()` fails with `CANCELLED` (TypeScript transport taken over from the template, which raised `CONFIG`) |
| OTLP logs | not offered: OAP drops the top-level trace/span ids of OTLP log records, so all languages send logs to the native `/v3/logs` API (an earlier Go-only OTLP log exporter was removed for parity) |
