# Apache SkyWalking SDK

[![Grow Codes](https://img.shields.io/badge/Grow%20Codes-grow.codes-2ea44f)](https://grow.codes)
[![CI](https://github.com/grow-codes-packages/apache-skywalking/actions/workflows/ci.yml/badge.svg)](https://github.com/grow-codes-packages/apache-skywalking/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](license.md)

One API for **Apache SkyWalking** in five languages: TypeScript, Rust, Go, Python and Java. Every package
has the same functions, options, defaults, error codes and behaviour, tested against a real
`apache/skywalking-oap-server + apache/skywalking-ui:10.4.0 + BanyanDB` in Docker.

| Language | Package | Coverage |
| - | - | - |
| TypeScript | [`@grow-codes/apache-skywalking`](https://www.npmjs.com/package/@grow-codes/apache-skywalking) | ![TypeScript coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/apache-skywalking/badges/coverage-typescript.json) |
| Rust | [`grow-apache-skywalking`](https://crates.io/crates/grow-apache-skywalking) | ![Rust coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/apache-skywalking/badges/coverage-rust.json) |
| Go | [`github.com/grow-codes-packages/apache-skywalking/go`](https://pkg.go.dev/github.com/grow-codes-packages/apache-skywalking/go) | ![Go coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/apache-skywalking/badges/coverage-go.json) |
| Python | [`grow-apache-skywalking`](https://pypi.org/project/grow-apache-skywalking/) | ![Python coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/apache-skywalking/badges/coverage-python.json) |
| Java | [`codes.grow.packages:apache-skywalking`](https://central.sonatype.com/artifact/codes.grow.packages/apache-skywalking) | ![Java coverage](https://img.shields.io/endpoint?url=https://grow-codes-packages.github.io/apache-skywalking/badges/coverage-java.json) |

## What you get

- **Client** with health, authenticated ping and server version ([Client](client.md)).
- **Configuration** from code or `GROW_APACHE_SKYWALKING_*` / vendor environment variables, immutable
  and validated ([Configuration](configuration.md)).
- **Retries** with full-jitter backoff and `Retry-After`, connect/request/total timeouts and
  cancellation in every language.
- **Typed errors** with one set of codes across languages ([Error handling](errors.md)).
- **Redaction everywhere**: credentials never appear in errors, logs, hooks or `toString`
  ([Redaction](redaction.md)).
- **Logging**: batching log client, logger facade, redaction processor, framework adapters and
  log search ([Logging](logging.md)).
- **Tracing**: OpenTelemetry setup, span helpers, propagation, framework adapters and trace
  queries ([Tracing](tracing.md)).
- **Service functions** for the Apache SkyWalking API ([Functions](functions.md)).

Start with [Install](install.md) and the [Quickstart](quickstart.md).

!!! note "Independent project"
    This SDK is made by [Grow Codes](https://grow.codes). Apache SkyWalking is a trademark of
    The Apache Software Foundation. This project is not affiliated with or endorsed by The Apache Software Foundation. See
    [License & disclaimer](license.md).
