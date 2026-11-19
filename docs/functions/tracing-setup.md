# Tracing setup and sw8

## initTracing

Sets up the official OpenTelemetry SDK of each language with an exporter to the OAP **OTLP gRPC
receiver** (port 11800) and returns a `TracingHandle` (`tracerProvider`, `tracer(name, version)`,
`forceFlush()`, `shutdown()`). Span helpers (`withSpan`, `traced`, `currentSpan`, `traceFields`, …),
propagation and framework adapters are described on [Tracing](../tracing.md).

Resolution of the exporter settings (explicit `TracingConfig` field > `SkyWalkingConfig` > default):

| Setting | Source | Default |
| - | - | - |
| endpoint | `TracingConfig.endpoint`, else `SkyWalkingConfig.otlpEndpoint` | `http://<url host>:11800` |
| protocol | `TracingConfig.protocol`, else `SkyWalkingConfig.otlpProtocol` | `grpc` |
| headers | `SkyWalkingConfig.otlpHeaders` + the `authentication` token (gRPC metadata `authentication`, OAP `SW_AUTHENTICATION`), overridden by `TracingConfig.headers` | none |
| TLS | `TracingConfig.tls`, else `SkyWalkingConfig.tls` (`https://` endpoints) | system roots |
| propagators | `TracingConfig.propagators` / `OTEL_PROPAGATORS` | `tracecontext`, `baggage` (add `sw8`, see below) |

- **`grpc`** is the only OTLP transport OAP 10.4 serves. **`http/protobuf`** sends to
  `<endpoint>/v1/traces` and is meant for an OpenTelemetry Collector in front of OAP. **`native`**
  (SkyWalking segments) is a `CONFIG` error: use the official SkyWalking agents for that.
- **Protocol default in Rust, Python and Java**: the generated `TracingConfig` of these languages
  always carries a protocol, with the template default `http/protobuf`. That default counts as
  *unset*, so the protocol comes from `SkyWalkingConfig.otlpProtocol` (default `grpc`) — the same
  result as TypeScript and Go, where the field is optional. Select `http/protobuf` through
  `SkyWalkingConfig.otlpProtocol` / `OTEL_EXPORTER_OTLP_PROTOCOL` (Python also accepts the
  `protocol="http/protobuf"` keyword of `init_tracing`); an explicit `grpc` always wins.
- OTLP spans are stored in OAP's **Zipkin model**. They are only readable when OAP runs with
  `SW_RECEIVER_ZIPKIN=default` and `SW_QUERY_ZIPKIN=default` (see
  [Compatibility](../compatibility.md#server-settings)); then `client.traces` finds them 5–15 s
  after export.

=== "TypeScript"

    ```ts
    import { initTracing, withSpan } from "@grow-codes/apache-skywalking";

    const tracing = client.initTracing({ serviceName: "checkout", environment: "prod" });
    // standalone (SkyWalkingConfig.fromEnv()): initTracing({ serviceName: "checkout" })
    await withSpan("GET /checkout", async () => { /* … */ });
    await tracing.forceFlush();
    ```

=== "Rust"

    ```rust
    use grow_apache_skywalking as sdk;

    let tracing = client.init_tracing(sdk::tracing_config("checkout").with_environment("prod"))?;
    // standalone: sdk::init_tracing(sdk::tracing_config("checkout"))?
    tracing.force_flush(None).await?;
    ```

=== "Go"

    ```go
    tracing, err := client.InitTracing(ctx, skywalking.TracingConfig{ServiceName: "checkout", Environment: "prod"})
    // standalone (environment): skywalking.InitTracing(ctx, skywalking.TracingConfig{ServiceName: "checkout"})
    if err != nil {
        return err
    }
    defer tracing.Shutdown(ctx)
    ```

=== "Python"

    ```python
    from grow_apache_skywalking import init_tracing

    tracing = client.init_tracing(service_name="checkout", environment="prod")
    # standalone (SkyWalkingConfig.from_env()): init_tracing(service_name="checkout")
    tracing.force_flush(30)
    ```

=== "Java"

    ```java
    TracingHandle tracing = client.initTracing(TracingConfig.builder("checkout").environment("prod").build());
    // standalone: SkyWalkingTracing.initTracing(TracingConfig.builder("checkout").build(), SkyWalkingConfig.fromEnv())
    tracing.forceFlush();
    ```

## sw8 propagator

`sw8` is SkyWalking's cross-process header
(`sample-b64(traceId)-b64(parentSegmentId)-parentSpanId-b64(parentService)-b64(parentInstance)-b64(parentEndpoint)-b64(peer)`),
`sw8-correlation` carries `b64(key):b64(value)` pairs. Add `sw8` to the propagators next to W3C:

- **Extract** (default): a valid incoming `sw8` header is stored in the context (and its fields can
  be recorded as span attributes with `sw8Attributes`); `sw8-correlation` entries are stored too.
  Invalid headers are ignored.
- **Inject** only what the caller supplied: an OpenTelemetry SDK cannot derive a native parent
  (segment id, integer span id, endpoint) from W3C context, so nothing is written unless native
  metadata was put into the context with `withSw8Context`. For full native propagation use the
  official [SkyWalking agents](https://skywalking.apache.org/docs/#Agent).
- Codec helpers: `encodeSw8` / `decodeSw8`, `encodeSw8Correlation` / `decodeSw8Correlation`. The
  parent segment field is called `parentTraceSegmentId` in every language.

The context helpers follow each language's idiom, so their names and argument order differ:

| | read the extracted header | attach native metadata for injection |
| - | - | - |
| TypeScript | `getSw8Context(ctx)` | `withSw8Context(ctx, sw8)` (`sw8.correlation` optional) |
| Rust | `sw8_context(&cx)` | `with_sw8_context(&cx, Sw8InjectContext { sw8, correlation })` |
| Go | `SW8FromContext(ctx)`, `SW8CorrelationFromContext(ctx)` | `ContextWithSW8(ctx, sw8)`, `ContextWithSW8Correlation(ctx, kvs)` |
| Python | `get_sw8_context(context=None)` | `with_sw8_context(sw8, context=None)` — context **last** and optional (current context by default), like `opentelemetry.context.set_value` |
| Java | `Sw8.fromContext(ctx)` | `Sw8.withContext(ctx, sw8)` / `Sw8.withContext(ctx, sw8, correlation)` |

=== "TypeScript"

    ```ts
    import { context } from "@opentelemetry/api";
    import { decodeSw8, getSw8Context, withSw8Context } from "@grow-codes/apache-skywalking";

    const tracing = client.initTracing({ serviceName: "b", propagators: ["tracecontext", "baggage", "sw8"] });
    const parent = getSw8Context(extractedCtx);              // Sw8Context | undefined
    const ctx = withSw8Context(context.active(), { ...decodeSw8(header)!, correlation: { tenant: "a" } });
    ```

=== "Rust"

    ```rust
    use grow_apache_skywalking::{decode_sw8, sw8_context, with_sw8_context, Sw8InjectContext};

    let parent = sw8_context(&extracted_cx);
    let cx = with_sw8_context(&cx, Sw8InjectContext { sw8: decode_sw8(header).unwrap(), correlation: vec![] });
    ```

=== "Go"

    ```go
    tracing, err := client.InitTracing(ctx, skywalking.TracingConfig{
        ServiceName: "b",
        Propagators: []string{skywalking.PropagatorTraceContext, skywalking.PropagatorBaggage, skywalking.PropagatorSW8},
    })
    parent, ok := skywalking.SW8FromContext(extractedCtx)
    sw8, err := skywalking.ParseSW8(header)
    ctx = skywalking.ContextWithSW8(ctx, sw8)
    ```

=== "Python"

    ```python
    from grow_apache_skywalking import decode_sw8, get_sw8_context, with_sw8_context

    tracing = client.init_tracing(service_name="b", propagators=("tracecontext", "baggage", "sw8"))
    parent = get_sw8_context(extracted_ctx)
    ctx = with_sw8_context(decode_sw8(header))  # current context
    ```

=== "Java"

    ```java
    Optional<Sw8Context> parent = Sw8.fromContext(extractedCtx);
    Context ctx = Sw8.withContext(Context.current(), Sw8.decode(header).orElseThrow(), Map.of("tenant", "a"));
    ```
