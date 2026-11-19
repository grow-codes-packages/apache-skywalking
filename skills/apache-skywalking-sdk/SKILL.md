---
name: apache-skywalking-sdk
description: Add and use the Grow Codes SDK for Apache SkyWalking (`apache-skywalking`) in TypeScript, Rust, Go, Python or Java projects. Use when code must talk to an Apache SkyWalking OAP server - sending OpenTelemetry traces over OTLP/gRPC, sending trace-correlated logs, reading traces back (Zipkin store or native GraphQL), querying services/endpoints/instances/topology/logs over GraphQL, handling sw8 headers, installing @grow-codes/apache-skywalking, the grow-apache-skywalking crate or PyPI package, the Go module github.com/grow-codes-packages/apache-skywalking/go or Maven codes.grow.packages:apache-skywalking, configuring it with GROW_APACHE_SKYWALKING_* variables, handling SkyWalkingError codes, testing with the bundled fakes, or running the Docker integration suite of this repository.
---

# Apache SkyWalking SDK (Grow Codes)

One API for Apache SkyWalking in five languages (categories: logging,tracing). Pick the package for the
project's language, configure it from the environment, and handle errors by their canonical code.

## 1. Add the package

| Language | Command | Import |
| - | - | - |
| TypeScript | `npm install @grow-codes/apache-skywalking` | `import { SkyWalkingClient } from "@grow-codes/apache-skywalking"` |
| Rust | `cargo add grow-apache-skywalking` (add `tokio` with `macros,rt-multi-thread`) | `use grow_apache_skywalking::SkyWalkingClient;` |
| Go | `go get github.com/grow-codes-packages/apache-skywalking/go` | `skywalking "github.com/grow-codes-packages/apache-skywalking/go"` |
| Python | `pip install grow-apache-skywalking` (or `uv add grow-apache-skywalking`) | `from grow_apache_skywalking import SkyWalkingClient` |
| Java | Maven `codes.grow.packages:apache-skywalking` (Gradle `implementation("codes.grow.packages:apache-skywalking:<version>")`) | `import codes.grow.packages.skywalking.SkyWalkingClient;` |

Check the latest version in the README badges before pinning one.

## 2. Configure

Prefer environment configuration so no credential is written into code:

```sh
export GROW_APACHE_SKYWALKING_URL=http://oap.example.internal:12800   # REST/GraphQL; or SW_GRAPHQL_URL / SW_OAP_ADDRESS
export GROW_APACHE_SKYWALKING_AUTHENTICATION=...   # only when OAP sets SW_AUTHENTICATION; from your secret store
```

Derived from the URL unless set: OTLP gRPC receiver `http://<host>:11800`
(`…_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `SW_AGENT_COLLECTOR_BACKEND_SERVICES`) and the
Zipkin query API `http://<host>:9412/zipkin` (`…_ZIPKIN_URL`, `SW_ZIPKIN_ADDRESS`). Other service
variables: `…_GRAPHQL_PATH`, `…_OTLP_PROTOCOL` (`grpc` default; `http/protobuf` only for a Collector),
`…_OTLP_HEADERS`, `…_TRACE_BACKEND` (`auto`|`zipkin`|`native`). `GROW_SKYWALKING_*` is accepted as a
short alias. Common variables: `…_CONNECT_TIMEOUT`, `…_REQUEST_TIMEOUT`, `…_TOTAL_TIMEOUT` (`10s`,
`1500`), `…_MAX_RETRIES`, `…_CA_CERT`, `…_CLIENT_CERT`, `…_CLIENT_KEY`, `…_PROXY`. Precedence:
explicit value > `GROW_APACHE_SKYWALKING_*` > `GROW_SKYWALKING_*` > vendor variables > default.
Environment proxies (`HTTPS_PROXY`) are ignored unless `proxy` is set.

**Server requirement**: OAP must run with `SW_RECEIVER_ZIPKIN=default` and `SW_QUERY_ZIPKIN=default`
(port 9412 reachable), otherwise OTLP traces are stored but can never be read back. Grow Codes
stacks do not set these yet.

## 3. Use

Create one client per process and close it on shutdown (TypeScript `await client.close()`, Rust
`client.close().await`, Go `defer client.Close()`, Python `with …`/`close()`, Java
try-with-resources).

- `health()` never throws for an unhealthy service; check `healthy`.
- `ping()` is a GraphQL round trip. OAP has no query authentication: `AUTHENTICATION` only comes
  from a gateway (put its credentials in `headers`).
- All I/O is retried with jitter on 429/502/503/504 and connection errors; only idempotent calls
  are retried on 500.
- Errors carry `code` (`CONFIG`, `AUTHENTICATION`, `PERMISSION_DENIED`, `NOT_FOUND`, `CONFLICT`,
  `RATE_LIMITED`, `VALIDATION`, `UNAVAILABLE`, `TIMEOUT`, `UNSUPPORTED`, `CANCELLED`), `retryable`,
  `httpStatus` and `requestId`. Branch on the code, not on the message text. Go:
  `errors.Is(err, skywalking.ErrNotFound)`.

### Logging

Create the log client once, send records with `send`/`sendBatch` or use the logger facade
(`logger("component").info("message", attrs)`) or the framework adapter for the language (pino or
winston, `tracing`/`log`, `slog`/zap, `logging`/structlog, Logback/Log4j2/JUL). Records are batched
and redacted; call `close()` on shutdown so the queue is flushed.

### Tracing

Call `initTracing({ serviceName })` once at startup, wrap work in `withSpan(name, fn)` or the
`traced` helper, propagate context with `inject`/`extract`, and call `shutdown()` on exit. Use the
trace query client to read traces back (`getTrace`, `searchTraces`, `waitForTrace`).

<!-- service:skill-usage — the service adds its own functions and examples here. -->

### Apache SkyWalking specifics

| Task | TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - | - |
| tracing to OAP | `client.initTracing({serviceName})` | `client.init_tracing(sdk::tracing_config("svc"))?` | `client.InitTracing(ctx, skywalking.TracingConfig{ServiceName: "svc"})` | `client.init_tracing(service_name="svc")` | `client.initTracing(TracingConfig.builder("svc").build())` |
| logs to OAP | `client.logClient({serviceName})` | `client.log_client(SkyWalkingLogClientOptions::service("svc"))?` | `client.NewLogClient(skywalking.LogClientOptions{Resource: …"service.name"…})` | `client.log_client(service_name="svc")` | `client.logClient("svc")` |
| read a trace | `client.traces.getTrace(id)` | `client.traces().get_trace(id)` | `client.Traces().GetTrace(ctx, id)` | `client.traces.get_trace(id)` | `client.traces().getTrace(id)` |
| wait in tests | `client.traces.waitForTrace(id, {timeoutMs: 60_000})` | `wait_for_trace(&id, WaitForTraceOptions::default())` | `WaitForTrace(ctx, id, opts)` | `wait_for_trace(id, timeout_s=60)` | `waitForTrace(id)` |
| search logs | `client.logs.search("service=svc trace_id=… text")` | `client.logs().search(q, SearchOptions::default())` | `client.Logs().Search(ctx, q, opts)` | `client.logs.search(q)` | `client.logs().search(q)` |
| GraphQL queries | `client.query.listServices()`, `topology()`, `queryTraces({...})`, `queryLogs({...})` | `client.query().list_services(None, None)` | `client.Query().ListServices(ctx, "")` | `client.query.list_services()` | `client.query().listServices()` |

Rules that save debugging time:

- Export is **OTLP/gRPC to port 11800**; OAP 10.4 has no OTLP/HTTP receiver. In Rust, Python and Java
  the template default protocol `http/protobuf` counts as unset and becomes `grpc`.
- OAP lower-cases span names in the Zipkin store (`GET /checkout` → `get /checkout`).
- Services, instances and name lists appear **30–40 s** after the first data; poll
  (`waitForTrace`, `waitForLog`) instead of asserting once.
- Native SkyWalking trace ids (`<hex>.<n>.<ms>`) are kept verbatim; native span ids are
  `"<segmentId>-<spanId>"`. `traceBackend` `auto` routes hex ids to Zipkin, others to GraphQL.
- Log search: `service=`, `instance=`, `endpoint=`, `trace_id=` and other `key=value` (tag) terms go
  to the server; free text is matched on the client (BanyanDB ignores keywords), at most 10 pages of
  1 000 logs. Tag keys must be in OAP's `SW_SEARCHABLE_LOGS_TAG_KEYS`.
- `getDependencies` comes from the native topology with `callCount` 0; the `zipkin` backend raises
  `UNSUPPORTED`. OTLP spans create no topology.
- `sw8`: add `"sw8"` to the propagators to extract SkyWalking agent headers; injection happens only
  with native metadata set through `withSw8Context` (Python `with_sw8_context(sw8, context=None)`,
  Go `ContextWithSW8`, Java `Sw8.withContext`). For full native propagation use the official
  SkyWalking agents.
- After `close()` every call fails with `CANCELLED`.

## 4. Test code that uses the SDK

Use the in-memory fakes from the testing module instead of a real Apache SkyWalking in unit tests:
TypeScript `@grow-codes/apache-skywalking/testing`, Rust feature `testing`, Go package `testkit`, Python
`grow_apache_skywalking.testing`, Java `codes.grow.packages.skywalking.testing`.

## 5. Work on this repository

Read `AGENTS.md` first. Short version: the five languages keep the same API; files with the
`Generated by sdk-kit` header are changed in sdk-kit, not here; `make lint coverage` must pass
(≥ 90 % lines and branches); `make test-docker LANGS="<lang>"` runs the integration suite against
OAP 10.4.0 + BanyanDB 0.10.3 (needs about 2 GiB of RAM; services appear 30–40 s after first data).
Service quirks and unit-test-only paths are in `docs/compatibility.md`.
