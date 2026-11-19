# Grow Codes SDK for Apache SkyWalking

[![CI](https://github.com/grow-codes-packages/apache-skywalking/actions/workflows/ci.yml/badge.svg)](https://github.com/grow-codes-packages/apache-skywalking/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/grow-codes-packages/apache-skywalking?sort=semver)](https://github.com/grow-codes-packages/apache-skywalking/releases)
[![Docs](https://img.shields.io/badge/docs-grow--codes--packages.github.io-blue)](https://grow-codes-packages.github.io/apache-skywalking/)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Grow Codes](https://img.shields.io/badge/Grow%20Codes-grow.codes-2ea44f)](https://grow.codes)

| Language | Package | Coverage |
| - | - | - |
| TypeScript | [![npm](https://img.shields.io/npm/v/@grow-codes/apache-skywalking)](https://www.npmjs.com/package/@grow-codes/apache-skywalking) | [![TypeScript coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/apache-skywalking/badges/coverage-typescript.json)](https://github.com/grow-codes-packages/apache-skywalking/actions/workflows/ci.yml) |
| Rust | [![crates.io](https://img.shields.io/crates/v/grow-apache-skywalking)](https://crates.io/crates/grow-apache-skywalking) [![docs.rs](https://img.shields.io/docsrs/grow-apache-skywalking)](https://docs.rs/grow-apache-skywalking) | [![Rust coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/apache-skywalking/badges/coverage-rust.json)](https://github.com/grow-codes-packages/apache-skywalking/actions/workflows/ci.yml) |
| Go | [![Go Reference](https://pkg.go.dev/badge/github.com/grow-codes-packages/apache-skywalking/go.svg)](https://pkg.go.dev/github.com/grow-codes-packages/apache-skywalking/go) | [![Go coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/apache-skywalking/badges/coverage-go.json)](https://github.com/grow-codes-packages/apache-skywalking/actions/workflows/ci.yml) |
| Python | [![PyPI](https://img.shields.io/pypi/v/grow-apache-skywalking)](https://pypi.org/project/grow-apache-skywalking/) | [![Python coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/apache-skywalking/badges/coverage-python.json)](https://github.com/grow-codes-packages/apache-skywalking/actions/workflows/ci.yml) |
| Java | [![Maven Central](https://img.shields.io/maven-central/v/codes.grow.packages/apache-skywalking)](https://central.sonatype.com/artifact/codes.grow.packages/apache-skywalking) | [![Java coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/apache-skywalking/badges/coverage-java.json)](https://github.com/grow-codes-packages/apache-skywalking/actions/workflows/ci.yml) |

Open-source client libraries for [Apache SkyWalking](#trademarks-and-disclaimer) in **TypeScript, Rust, Go,
Python and Java** — one API, five idiomatic packages, tested against a real SkyWalking OAP server with
BanyanDB storage in Docker.

- **OpenTelemetry tracing to OAP** in one call (`initTracing`): OTLP/gRPC to the OAP receiver
  (port 11800), span helpers, framework middleware and an **`sw8` propagator** next to W3C.
- **Trace queries** (`TraceQueryClient`) over both OAP trace stores: OTLP spans through OAP's
  Zipkin query API and SkyWalking agent traces through GraphQL — native trace ids kept verbatim.
- **Logs** to OAP's native `/v3/logs` API, correlated with the active trace, plus log search with
  client-side free-text matching (BanyanDB ignores keyword search).
- **GraphQL query client**: services, endpoints, instances, native traces, logs, topology and raw
  GraphQL.
- **Typed client** with timeouts, retries with jitter, `Retry-After`, cancellation and one error
  model (`SkyWalkingError` with canonical codes) in every language; the OAP token is redacted
  everywhere.

## Contents

- [Supported service version](#supported-service-version)
- [Install](#install)
- [Quickstart](#quickstart)
- [Configuration](#configuration)
- [Function overview](#function-overview)
- [Testing with Docker](#testing-with-docker)
- [Compatibility](#compatibility)
- [Contributing](#contributing)
- [Security](#security)
- [Licence](#licence)
- [Trademarks and disclaimer](#trademarks-and-disclaimer)
- [About Grow Codes](#about-grow-codes)

## Supported service version

Tested in CI against **`apache/skywalking-oap-server:10.4.0`** with **`apache/skywalking-banyandb:0.10.3`**
storage (the versions pinned in the Grow Codes stack catalog; `apache/skywalking-ui:10.4.0` is in the
compose profile `ui`). Other OAP 10.x versions with the same GraphQL query protocol, OTLP receiver
and Zipkin query API usually work.

OAP must run with **`SW_RECEIVER_ZIPKIN=default`** and **`SW_QUERY_ZIPKIN=default`** (port 9412): OAP
stores OTLP spans in its Zipkin model and only serves them back through the Zipkin query API.
Without these settings traces are accepted but cannot be read. See
[docs/compatibility.md](docs/compatibility.md#server-settings).

## Install

| Language | Package | Install |
| - | - | - |
| TypeScript (Node.js ≥ 22.12) | `@grow-codes/apache-skywalking` | `npm install @grow-codes/apache-skywalking` |
| Rust (MSRV 1.88) | `grow-apache-skywalking` | `cargo add grow-apache-skywalking` |
| Go (1.26+) | `github.com/grow-codes-packages/apache-skywalking/go` | `go get github.com/grow-codes-packages/apache-skywalking/go` |
| Python (≥ 3.10) | `grow-apache-skywalking` | `pip install grow-apache-skywalking` |
| Java (17+) | `codes.grow.packages:apache-skywalking` | see below |

Maven:

```xml
<dependency>
  <groupId>codes.grow.packages</groupId>
  <artifactId>apache-skywalking</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("codes.grow.packages:apache-skywalking:0.1.0")
```

The OpenTelemetry SDK and its OTLP gRPC exporter come with each package (Python: the
`opentelemetry-exporter-otlp-proto-grpc` dependency is installed by default).

## Quickstart

Every package reads its configuration from the environment (`GROW_APACHE_SKYWALKING_URL`, or the
vendor variables `SW_GRAPHQL_URL` / `SW_OAP_ADDRESS`, see [Configuration](#configuration)) or from an
explicit config object. The OTLP endpoint (`:11800`) and the Zipkin query API (`:9412/zipkin`) are
derived from the URL. The examples send a traced request with a correlated log and read both back.

```sh
export GROW_APACHE_SKYWALKING_URL=http://127.0.0.1:12800
```

### TypeScript

```ts
import { SkyWalkingClient, withSpan } from "@grow-codes/apache-skywalking";

const client = SkyWalkingClient.fromEnv();
try {
  console.log(await client.health(), await client.serverVersion());
  const tracing = client.initTracing({ serviceName: "checkout" });
  const logs = client.logClient({ serviceName: "checkout" });
  const traceId = await withSpan("GET /checkout", async (span) => {
    logs.logger("api").info("order accepted");
    return span.spanContext().traceId;
  });
  await tracing.forceFlush();
  await logs.flush();
  const trace = await client.traces.waitForTrace(traceId, { timeoutMs: 60_000 });
  const found = await client.logs.search(`trace_id=${traceId}`);
  console.log(trace.spans.length, found.records.length);
} finally {
  await client.close();
}
```

### Rust

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

### Go

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

	tracing, err := client.InitTracing(ctx, skywalking.TracingConfig{ServiceName: "checkout"})
	if err != nil {
		log.Fatal(err)
	}
	spanCtx, span := tracing.Tracer("api").Start(ctx, "GET /checkout")
	traceID, _ := skywalking.CurrentTraceID(spanCtx)
	span.End()
	_ = tracing.Shutdown(ctx)

	logs, _ := client.NewLogClient(skywalking.LogClientOptions{
		Resource: map[string]skywalking.AttrValue{"service.name": "checkout"},
	})
	logs.Send(ctx, skywalking.LogInput{Message: "order accepted", TraceID: traceID})
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

### Python

```python
from grow_apache_skywalking import SkyWalkingClient

with SkyWalkingClient.from_env() as client:
    print(client.health(), client.server_version())
    tracing = client.init_tracing(service_name="checkout", set_global=False)
    logs = client.log_client(service_name="checkout")
    with tracing.tracer("api").start_as_current_span("GET /checkout") as span:
        logs.logger("api").info("order accepted")
    trace_id = format(span.get_span_context().trace_id, "032x")
    tracing.force_flush(30)
    logs.flush(30)
    trace = client.traces.wait_for_trace(trace_id, timeout_s=60)
    found = client.logs.search(f"trace_id={trace_id}")
    print(len(trace.spans), "spans,", len(found.records), "logs")
```

Async code uses `AsyncSkyWalkingClient` with the same methods (`async with`, `await client.health()`).

### Java

```java
import codes.grow.packages.skywalking.SkyWalkingClient;
import codes.grow.packages.skywalking.logging.BatchingLogClient;
import codes.grow.packages.skywalking.tracing.Spans;
import codes.grow.packages.skywalking.tracing.TracingConfig;
import codes.grow.packages.skywalking.tracing.TracingHandle;

try (SkyWalkingClient client = SkyWalkingClient.fromEnv()) {
    System.out.println(client.health() + " " + client.serverVersion());
    TracingHandle tracing = client.initTracing(TracingConfig.builder("checkout").build());
    BatchingLogClient logs = client.logClient("checkout");
    String traceId = Spans.withSpan("GET /checkout", () -> {
        logs.logger("api").info("order accepted");
        return Spans.currentTraceId().orElseThrow();
    });
    tracing.forceFlush();
    logs.flush();
    var trace = client.traces().waitForTrace(traceId);
    var found = client.logs().search("trace_id=" + traceId);
    System.out.println(trace.spans().size() + " spans, " + found.records().size() + " logs");
}
```

More complete programs live in [examples/](examples/) — one folder per language.

## Configuration

All settings can be passed in code or through environment variables. Precedence: **explicit value >
`GROW_APACHE_SKYWALKING_*` > `GROW_SKYWALKING_*` (short alias) > vendor variable > default.** A present
but invalid value raises a `ConfigError` that names the variable (never its value).

| Setting | Environment variables (in order) | Default |
| - | - | - |
| `url` — OAP REST/GraphQL (a trailing `/graphql` is stripped) | `GROW_APACHE_SKYWALKING_URL`, `GROW_SKYWALKING_URL`, `SW_GRAPHQL_URL`, `SW_OAP_ADDRESS` | `http://127.0.0.1:12800` |
| `graphqlPath` | `GROW_APACHE_SKYWALKING_GRAPHQL_PATH` | `/graphql` |
| `otlpEndpoint` — OTLP gRPC receiver | `GROW_APACHE_SKYWALKING_OTLP_ENDPOINT`, `GROW_SKYWALKING_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `SW_AGENT_COLLECTOR_BACKEND_SERVICES` | `http://<url host>:11800` |
| `otlpProtocol` — `grpc` / `http/protobuf` (collector only) | `GROW_APACHE_SKYWALKING_OTLP_PROTOCOL`, `GROW_SKYWALKING_OTLP_PROTOCOL`, `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL`, `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc` |
| `otlpHeaders` — exporter headers (`k=v,k2=v2`) | `GROW_APACHE_SKYWALKING_OTLP_HEADERS`, `OTEL_EXPORTER_OTLP_TRACES_HEADERS`, `OTEL_EXPORTER_OTLP_HEADERS` | none |
| `authentication` — OAP gRPC token (secret) | `GROW_APACHE_SKYWALKING_AUTHENTICATION`, `GROW_SKYWALKING_AUTHENTICATION`, `SW_AUTHENTICATION`, `SW_AGENT_AUTHENTICATION` | none |
| `zipkinUrl` — Zipkin query API (OTLP spans) | `GROW_APACHE_SKYWALKING_ZIPKIN_URL`, `GROW_SKYWALKING_ZIPKIN_URL`, `SW_ZIPKIN_ADDRESS` | `http://<url host>:9412/zipkin` |
| `traceBackend` — `auto` / `zipkin` / `native` | `GROW_APACHE_SKYWALKING_TRACE_BACKEND`, `GROW_SKYWALKING_TRACE_BACKEND` | `auto` |
| `connectTimeout` | `GROW_APACHE_SKYWALKING_CONNECT_TIMEOUT` | 10 s |
| `requestTimeout` (per attempt) | `GROW_APACHE_SKYWALKING_REQUEST_TIMEOUT` | 30 s |
| `totalTimeout` (including retries) | `GROW_APACHE_SKYWALKING_TOTAL_TIMEOUT` | 120 s |
| `retry.maxRetries` | `GROW_APACHE_SKYWALKING_MAX_RETRIES` | 3 |
| `tls.caCertPath` | `GROW_APACHE_SKYWALKING_CA_CERT`, `GROW_SKYWALKING_CA_CERT` | system roots |
| `tls.clientCertPath` / `tls.clientKeyPath` | `GROW_APACHE_SKYWALKING_CLIENT_CERT` / `_CLIENT_KEY` (+ `GROW_SKYWALKING_*`) | none |
| `tls.insecureSkipVerify` (tests only) | `GROW_APACHE_SKYWALKING_INSECURE_SKIP_VERIFY`, `GROW_SKYWALKING_INSECURE_SKIP_VERIFY` | `false` |
| `proxy` | `GROW_APACHE_SKYWALKING_PROXY`, `GROW_SKYWALKING_PROXY` | none (environment proxies are ignored) |
| `headers` (gateway credentials, redacted), `userAgentSuffix`, `hooks`, `logger` | — | none |

`initTracing` also reads the standard `OTEL_SERVICE_NAME`, `OTEL_RESOURCE_ATTRIBUTES`,
`OTEL_TRACES_SAMPLER(_ARG)` and `OTEL_PROPAGATORS`. Durations accept milliseconds (`1500`) or units
(`500ms`, `10s`, `1m30s`); booleans accept `true/false/1/0/yes/no/on/off`. Field names follow each
language's convention (`otlp_endpoint` in Python and Rust, `OTLPEndpoint` in Go). The full reference
is in the [documentation](https://grow-codes-packages.github.io/apache-skywalking/configuration/).

## Function overview

<!-- service:function-overview — the service fills this section; keep the heading. -->

The same functions exist in every language, named per language convention (`listServices` in
TypeScript and Java, `list_services` in Python and Rust, `ListServices` in Go). Every function has
an example in all five languages in the [documentation](https://grow-codes-packages.github.io/apache-skywalking/functions/).

### Client — `SkyWalkingClient`

| Function | Description |
| - | - |
| `health()` | `GET /healthcheck` (falls back to GraphQL `checkHealth`); never throws for an unreachable server |
| `ping()` | timed GraphQL round trip (`getTimeInfo`) |
| `serverVersion()` | OAP version (`10.4.0-…`) |
| `graphql(query, variables)` | raw GraphQL: `{data, errors}`; HTTP 400 → `VALIDATION` |
| `logClient(options)` / `createLogClient(config, options)` | batching log client to `/v3/logs` |
| `initTracing(config)` | OpenTelemetry → OTLP/gRPC to OAP |
| `close()` | flushes owned log clients and tracing handles; later calls fail with `CANCELLED` |

```ts
// TypeScript
const version = await client.serverVersion();
```
```rust
// Rust
let version = client.server_version().await?;
```
```go
// Go
version, err := client.ServerVersion(ctx)
```
```python
# Python
version = client.server_version()
```
```java
// Java
String version = client.serverVersion();
```

### Query — `client.query` (`SkyWalkingQueryClient`, GraphQL)

| Function | Description |
| - | - |
| `listServices(layer?, range?)` | services (all layers or one layer) |
| `findService(name)` | one service or none |
| `listEndpoints(service, keyword?, limit?)` | endpoints (tokenised keyword match) |
| `listInstances(service, range?)` | instances active in the window |
| `queryTraces(options)` | native traces by service/instance/endpoint/state/duration/tags, paged |
| `getTrace(traceId)` | one native trace (all segments) |
| `queryLogs(options)` | logs by service/instance/endpoint/trace/tags, keywords (client-side on BanyanDB) |
| `listLogTagKeys(range?)` | log tag keys |
| `topology(range?, {service, layer})` | global or service topology |
| `timeInfo()`, `capabilities()` | OAP clock/time zone; optional query features |
| `graphql(query, variables)` | raw GraphQL |

```ts
// TypeScript
const services = await client.query.listServices("GENERAL");
```
```rust
// Rust
let services = client.query().list_services(Some("GENERAL"), None).await?;
```
```go
// Go
services, err := client.Query().ListServices(ctx, "GENERAL")
```
```python
# Python
services = client.query.list_services("GENERAL")
```
```java
// Java
List<SkyWalkingService> services = client.query().listServices("GENERAL");
```

### Trace queries — `client.traces` (`TraceQueryClient`)

| Function | Description |
| - | - |
| `getTrace(traceId)` | normalized `Trace`; hex ids via Zipkin, native ids (`<hex>.<n>.<ms>`) via GraphQL, kept verbatim |
| `searchTraces(query)` | `TraceSummary[]` by service, operation, tags, durations, window, limit |
| `listServices()`, `listOperations(service)` | names from both stores |
| `getDependencies(range)` | links from the native topology (`callCount` 0); `UNSUPPORTED` on the `zipkin` backend |
| `waitForTrace(traceId, options)` | test helper: poll until the trace has `minSpans` spans |

```ts
// TypeScript
const trace = await client.traces.waitForTrace(traceId, { minSpans: 2 });
```
```rust
// Rust
let trace = client.traces().wait_for_trace(&trace_id, WaitForTraceOptions::default().min_spans(2)).await?;
```
```go
// Go
trace, err := client.Traces().WaitForTrace(ctx, traceID, skywalking.WaitForTraceOptions{MinSpans: 2})
```
```python
# Python
trace = client.traces.wait_for_trace(trace_id, min_spans=2)
```
```java
// Java
Trace trace = client.traces().waitForTrace(traceId);
```

### Logs — `logClient()`, `client.logs` (`LogQueryClient`)

| Function | Description |
| - | - |
| `send`, `sendBatch`, `flush`, `close`, `stats`, `logger` | batching log shipping to `/v3/logs`, trace-correlated, redacted |
| `search(query, options)` | `service=` / `instance=` / `endpoint=` / `trace_id=` / tag terms + client-side free text; cursor `page:offset:size` |
| `tail`, `waitForLog` | polling helpers |
| `listStreams()`, `listLabels()` | services with logs; log tag keys |

```ts
// TypeScript
const page = await client.logs.search('service=checkout level=ERROR "payment failed"');
```
```rust
// Rust
let page = client.logs().search("service=checkout level=ERROR payment", SearchOptions::default()).await?;
```
```go
// Go
page, err := client.Logs().Search(ctx, `service=checkout level=ERROR "payment failed"`, skywalking.SearchOptions{})
```
```python
# Python
page = client.logs.search('service=checkout level=ERROR "payment failed"')
```
```java
// Java
LogSearchResult page = client.logs().search("service=checkout level=ERROR \"payment failed\"");
```

### Tracing setup — `initTracing`, `sw8`

| Function | Description |
| - | - |
| `initTracing(config)` | OTLP/gRPC exporter to OAP with the `authentication` token, sampler, batch, TLS; `TracingHandle` |
| `withSpan`, `traced`, `currentSpan`, `currentTraceId`, `traceFields`, `inject`, `extract`, adapters | shared tracing helpers ([docs](https://grow-codes-packages.github.io/apache-skywalking/tracing/)) |
| `Sw8Propagator` (`"sw8"`) | extracts `sw8` / `sw8-correlation`; injects only native metadata you supply |
| `encodeSw8`, `decodeSw8`, `getSw8Context`, `withSw8Context` | sw8 codec and context helpers (idiomatic names per language) |

```ts
// TypeScript
const tracing = client.initTracing({ serviceName: "checkout", propagators: ["tracecontext", "baggage", "sw8"] });
```
```rust
// Rust
let tracing = client.init_tracing(sdk::tracing_config("checkout"))?;
```
```go
// Go
tracing, err := client.InitTracing(ctx, skywalking.TracingConfig{ServiceName: "checkout"})
```
```python
# Python
tracing = client.init_tracing(service_name="checkout", propagators=("tracecontext", "baggage", "sw8"))
```
```java
// Java
TracingHandle tracing = client.initTracing(TracingConfig.builder("checkout").build());
```

For full native SkyWalking propagation and auto-instrumentation, use the official
[SkyWalking language agents](https://skywalking.apache.org/docs/#Agent); this SDK reads their data
with `client.query` and the `native` trace backend.

<!-- /service:function-overview -->

## Testing with Docker

The integration suite runs every public function against a real OAP 10.4 with BanyanDB 0.10.3 in
Docker (`docker/compose.service.yml`; the fixture enables the Zipkin receiver/query modules and keeps
BanyanDB data on tmpfs). Each language package is packed and installed into a separate consumer
project first, so the tests use the package exactly as you would. A suite needs about 2 GiB of RAM;
the stack is healthy about 60–80 s after start.

```sh
make test-docker                     # all five languages
make test-docker LANGS="go python"   # a subset
```

No host ports are published and every run uses its own Compose project, so runs never collide; the
stack is always removed afterwards (`docker compose down -v`). Unit tests need no Docker:
`make test` or `make coverage`.

## Compatibility

| | TypeScript | Rust | Go | Python | Java |
| - | - | - | - | - | - |
| Runtime | Node.js ≥ 22.12, ESM + CJS | stable, MSRV 1.88 | 1.26, 1.27 | 3.10 – 3.14 | 17, 21, 25 |
| Async | `Promise` + `AbortSignal` | `async` (tokio), `blocking` feature | `context.Context` | sync + `asyncio` | sync + `CompletableFuture` |
| Apache SkyWalking | OAP 10.4.0 + BanyanDB 0.10.3 | OAP 10.4.0 + BanyanDB 0.10.3 | OAP 10.4.0 + BanyanDB 0.10.3 | OAP 10.4.0 + BanyanDB 0.10.3 | OAP 10.4.0 + BanyanDB 0.10.3 |

Service differences that matter in practice: OTLP spans need `SW_RECEIVER_ZIPKIN` + `SW_QUERY_ZIPKIN`;
OAP 10.4 has no OTLP/HTTP receiver (gRPC only); services appear about 30–40 s after their first data;
BanyanDB ignores log keyword search; Zipkin `/dependencies` is not implemented. Functions the server
cannot serve and the few deliberate language differences are listed in
[docs/compatibility.md](docs/compatibility.md).

## Contributing

Contributions are welcome — please read [CONTRIBUTING.md](CONTRIBUTING.md) and
[AGENTS.md](AGENTS.md) first. All five languages keep the same API; a change lands in all of them
together. Parts of this repository are generated by
[sdk-kit](https://github.com/grow-codes-packages/sdk-kit) and changed there.

## Security

Please report vulnerabilities privately to [opensource@grow.codes](mailto:opensource@grow.codes).
See [SECURITY.md](SECURITY.md).

## Licence

Licensed under the [Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for attributions.

## Trademarks and disclaimer

This is an independent, community-maintained project. It is **not affiliated with, endorsed or
sponsored by The Apache Software Foundation**. Apache SkyWalking is a trademark of The Apache Software Foundation; it is used here only to name the
service this SDK connects to. The Apache SkyWalking server is licensed separately by its owner
(Apache-2.0 (ASF)).

The software is provided "AS IS", without warranties or conditions of any kind. Grow Codes is not
responsible for any damage, data loss, security incident, outage or cost that results from using it.
See [LICENSE](LICENSE) and [NOTICE](NOTICE).

## About Grow Codes

This SDK is built and maintained by [Grow Codes](https://grow.codes). Grow Codes publishes the
client libraries it builds as open source in the
[grow-codes-packages](https://github.com/grow-codes-packages) organisation, generated from one
shared kit so that every service and every language behaves the same way. Learn more at
[grow.codes](https://grow.codes).
