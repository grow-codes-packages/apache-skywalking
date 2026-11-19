# Apache SkyWalking SDK

[![Grow Codes](https://img.shields.io/badge/Grow%20Codes-grow.codes-2ea44f)](https://grow.codes)
[![CI](https://github.com/grow-codes-packages/apache-skywalking/actions/workflows/ci.yml/badge.svg)](https://github.com/grow-codes-packages/apache-skywalking/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](license.md)

One API for **Apache SkyWalking** in five languages: TypeScript, Rust, Go, Python and Java. Every package
has the same functions, options, defaults, error codes and behaviour, tested against a real
`apache/skywalking-oap-server:10.4.0` with BanyanDB 0.10.3 in Docker. OAP needs
`SW_RECEIVER_ZIPKIN` + `SW_QUERY_ZIPKIN=default` for OTLP spans to be readable; see
[Compatibility](compatibility.md).

| Language | Package | Coverage |
| - | - | - |
| TypeScript | [`@grow-codes/apache-skywalking`](https://www.npmjs.com/package/@grow-codes/apache-skywalking) | ![TypeScript coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/apache-skywalking/badges/coverage-typescript.json) |
| Rust | [`grow-apache-skywalking`](https://crates.io/crates/grow-apache-skywalking) | ![Rust coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/apache-skywalking/badges/coverage-rust.json) |
| Go | [`github.com/grow-codes-packages/apache-skywalking/go`](https://pkg.go.dev/github.com/grow-codes-packages/apache-skywalking/go) | ![Go coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/apache-skywalking/badges/coverage-go.json) |
| Python | [`grow-apache-skywalking`](https://pypi.org/project/grow-apache-skywalking/) | ![Python coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/apache-skywalking/badges/coverage-python.json) |
| Java | [`codes.grow.packages:apache-skywalking`](https://central.sonatype.com/artifact/codes.grow.packages/apache-skywalking) | ![Java coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/apache-skywalking/badges/coverage-java.json) |

## What you get

- **Client** for one OAP server: health, ping, server version, raw GraphQL
  ([Client and status](functions/client.md)).
- **Query API** over GraphQL: services, endpoints, instances, native traces, logs and topology
  ([Query API](functions/query.md)).
- **Trace queries** (`TraceQueryClient`) over both OAP trace stores: OTLP spans through the Zipkin
  query API and SkyWalking agent traces through GraphQL, native ids kept verbatim
  ([Trace queries](functions/traces.md)).
- **Tracing**: OpenTelemetry setup exporting OTLP/gRPC to OAP (port 11800), span helpers, framework
  adapters and an `sw8` propagator next to W3C ([Tracing setup and sw8](functions/tracing-setup.md),
  [Tracing](tracing.md)).
- **Logging**: batching log client to OAP's native `/v3/logs` API, correlated with the active trace,
  and log search with client-side free-text matching ([Logs](functions/logs.md),
  [Logging](logging.md)).
- **Configuration** from code or `GROW_APACHE_SKYWALKING_*`, `SW_*` and `OTEL_EXPORTER_OTLP_*`
  variables, immutable and validated ([Configuration](configuration.md)).
- **Retries** with full-jitter backoff and `Retry-After`, connect/request/total timeouts and
  cancellation in every language; **typed errors** with one set of codes ([Error handling](errors.md)).
- **Redaction everywhere**: the OAP token and gateway headers never appear in errors, logs, hooks or
  `toString` ([Redaction](redaction.md)).

Start with [Install](install.md) and the [Quickstart](quickstart.md).

!!! note "Independent project"
    This SDK is made by [Grow Codes](https://grow.codes). Apache SkyWalking is a trademark of
    The Apache Software Foundation. This project is not affiliated with or endorsed by The Apache Software Foundation. See
    [License & disclaimer](license.md).
