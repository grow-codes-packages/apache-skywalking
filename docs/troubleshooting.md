<!-- Taken over from sdk-kit 0.1.0: SkyWalking has no API token; OAP-specific problems added. -->

# Troubleshooting

## The client cannot connect

- `health()` returns `healthy: false, status: "unreachable"`: the URL is wrong or the service is
  down. Check `GROW_APACHE_SKYWALKING_URL` (default `http://127.0.0.1:12800`) and that the port is
  reachable from where your code runs (inside Docker, `127.0.0.1` is the container itself).
- Calls fail with `UNAVAILABLE` after several seconds: the client retried with backoff. Lower
  `maxRetries` or `totalTimeout` to fail faster.
- Behind a corporate proxy: `HTTP_PROXY`/`HTTPS_PROXY` are **ignored** on purpose. Set `proxy`
  (or `GROW_APACHE_SKYWALKING_PROXY`).

## `CONFIG` errors

The message names the setting or environment variable, never its value. Typical causes: a URL
without `http://` or `https://`, a duration such as `10 sec` (use `10s` or `10000`), a client
certificate without its key, `MAX_RETRIES` below 0.

## `AUTHENTICATION` or `PERMISSION_DENIED`

- OAP has **no query authentication**: on GraphQL, REST and the Zipkin query API these codes come
  from a gateway in front of OAP. Pass its credentials in `headers`; `ping()` is the quickest check.
- `SW_AUTHENTICATION` on OAP protects only gRPC ingest. When OTLP exports are rejected (gRPC status
  7), set the same value in `authentication` (`GROW_APACHE_SKYWALKING_AUTHENTICATION` or
  `SW_AUTHENTICATION`); it is sent as the `authentication` gRPC metadata.
- The SDK never prints credentials, so `toString()` of the config shows `[REDACTED]` even when a
  token is set.

## OTLP traces never show up

- OAP stores OTLP spans in its **Zipkin** model: it needs `SW_RECEIVER_ZIPKIN=default` and
  `SW_QUERY_ZIPKIN=default`, and port 9412 must be reachable (`zipkinUrl`,
  `GROW_APACHE_SKYWALKING_ZIPKIN_URL`). Without them `getTrace` returns nothing and `waitForTrace`
  times out. The Grow Codes stack template does not set them yet.
- Export goes to the **gRPC** receiver on port 11800. OAP 10.4 has no OTLP/HTTP receiver: an
  `http/protobuf` exporter pointed at OAP fails (404). In Rust, Python and Java an explicit
  `TracingConfig` endpoint does not change the protocol; it stays `SkyWalkingConfig.otlpProtocol`
  (`grpc`).
- Span names come back lower-cased (`GET /checkout` → `get /checkout`).

## A new service or log is not listed yet

OAP flushes its metadata index periodically: services, instances, endpoints and Zipkin name lists
appear about 30–40 s after the first data, topology edges after about 40 s. Poll with `waitForTrace`
/ `waitForLog` (or a retry loop around `listServices`) instead of a single call.

## Log search ignores my words

OAP with BanyanDB storage ignores keyword search. The SDK matches free text on the client over at
most 10 pages of 1 000 logs; narrow the window or add `service=` / tag terms when a search scans
too much.

## OAP crash-loops on start

`failed to publish property update to any node` means BanyanDB refused writes: its data volume is
above 95 % full (`--property-max-disk-usage-percent`). Free disk space or give BanyanDB a larger
volume; with a 1 GiB memory limit BanyanDB's memory protector can also trip during schema
installation.

## TLS errors

- Self-signed or private CA: set `tls.caCertPath` (`GROW_APACHE_SKYWALKING_CA_CERT`).
- Mutual TLS: set both `tls.clientCertPath` and `tls.clientKeyPath`.
- `tls.insecureSkipVerify` only for local tests.

## `RATE_LIMITED`

The service asked the client to slow down. The client waits for `Retry-After` up to
`maxRetryAfter` (60 s); longer waits fail at once with `retryAfter` set on the error so your code
can schedule a later attempt.

## `TIMEOUT`

`requestTimeout` (30 s) bounds one attempt and `totalTimeout` (120 s) the whole call including
retries. Raise them for slow operations, or pass a per-call deadline (signal, ctx, cancel token,
future timeout).

## See what the client does

Turn on the [logger](hooks.md#logger) or add [hooks](hooks.md#hooks): both see every attempt with
redacted headers and URLs.

## Docker integration tests fail

- Run one language: `make test-docker LANGS=go`.
- In CI, download the `compose-logs-<lang>` artifact of the failed job.
- Locally, the stack is removed after each run; rerun with Docker logs open
  (`docker compose -f docker/compose.yml logs -f`) in a second terminal.

## Still stuck?

Open an issue with the language, package version, Apache SkyWalking version and a minimal reproduction
(with secrets removed): <https://github.com/grow-codes-packages/apache-skywalking/issues>.
