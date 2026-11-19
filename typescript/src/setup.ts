// Service-owned: initTracing — OpenTelemetry tracing exporting OTLP/gRPC to the OAP receiver.

import { readFileSync } from "node:fs";
import { Metadata, credentials as grpcCredentials } from "@grpc/grpc-js";
import { OTLPTraceExporter as OtlpGrpcExporter } from "@opentelemetry/exporter-trace-otlp-grpc";
import { OTLPTraceExporter as OtlpProtoExporter } from "@opentelemetry/exporter-trace-otlp-proto";
import { CompressionAlgorithm } from "@opentelemetry/otlp-exporter-base";
import type { SpanExporter } from "@opentelemetry/sdk-trace-base";
import { ConfigError, LANGUAGE, PACKAGE_KEY, SDK_VERSION, type TlsOptions } from "./core/index.js";
import { SkyWalkingConfig, type SkyWalkingConfigOptions } from "./config.js";
import { Sw8Propagator } from "./sw8.js";
import type { ResolvedTracingConfig, TracingConfig } from "./tracing/config.js";
import { initTracingWith, type InitTracingOptions, type TracingHandle } from "./tracing/init.js";

/** {@link TracingConfig} plus the SkyWalking connection settings. */
export interface SkyWalkingTracingConfig extends TracingConfig {
  /** Endpoint, protocol and token source (default: `SkyWalkingConfig.fromEnv()`). */
  skywalking?: SkyWalkingConfig | SkyWalkingConfigOptions;
}

/** gRPC metadata key OAP reads the token from (`SW_AUTHENTICATION`). */
export const AUTHENTICATION_HEADER = "authentication";

/**
 * Service defaults merged under the caller's {@link TracingConfig}: protocol and endpoint from
 * `otlpProtocol`/`otlpEndpoint`, headers from `otlpHeaders` plus the `authentication` token.
 */
export function skywalkingTracingDefaults(config: SkyWalkingConfig): Partial<TracingConfig> {
  const headers: Record<string, string> = { ...config.otlpHeaders };
  if (config.authentication !== undefined)
    headers[AUTHENTICATION_HEADER] = config.authentication.reveal();
  return {
    protocol: config.otlpProtocol,
    endpoint: config.otlpEndpoint,
    headers,
    tls: { ...config.tls },
  };
}

function readPem(path: string | undefined): string | undefined {
  return path === undefined ? undefined : readFileSync(path, "utf8");
}

function agentOptions(tls: Readonly<TlsOptions>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  const ca = [tls.caCertPem, readPem(tls.caCertPath)].filter((v) => v !== undefined);
  if (ca.length > 0) out.ca = ca;
  const cert = readPem(tls.clientCertPath);
  if (cert !== undefined) out.cert = cert;
  const key = readPem(tls.clientKeyPath);
  if (key !== undefined) out.key = key;
  if (tls.insecureSkipVerify === true) out.rejectUnauthorized = false;
  return out;
}

/** `<endpoint>/v1/traces` unless the endpoint already names that path. */
export function otlpTracesUrl(endpoint: string): string {
  const base = endpoint.replace(/\/+$/, "");
  return base.endsWith("/v1/traces") ? base : `${base}/v1/traces`;
}

/**
 * Builds the span exporter: `grpc` (default) → OTLP/gRPC to the OAP receiver (headers become
 * gRPC metadata; https endpoints use the TLS settings); `http/protobuf` → OTLP/HTTP protobuf to
 * `<endpoint>/v1/traces` (only for an OpenTelemetry Collector in front of OAP — OAP 10.4 serves
 * no OTLP/HTTP). `native` is not supported (`ConfigError`): use the official SkyWalking agents.
 */
export function createSkyWalkingSpanExporter(resolved: ResolvedTracingConfig): SpanExporter {
  const compression =
    resolved.compression === "gzip" ? CompressionAlgorithm.GZIP : CompressionAlgorithm.NONE;
  const userAgent = `grow-codes-${PACKAGE_KEY}/${SDK_VERSION} (${LANGUAGE})`;
  const endpoint = resolved.endpoint ?? `http://127.0.0.1:${SkyWalkingConfig.OTLP_GRPC_PORT}`;
  if (resolved.protocol === "native") {
    throw new ConfigError(
      "tracing: the SkyWalking native segment protocol is not supported; use grpc (OTLP) or the official SkyWalking agents",
    );
  }
  if (resolved.protocol === "http/protobuf") {
    return new OtlpProtoExporter({
      url: otlpTracesUrl(endpoint),
      headers: { ...resolved.headers },
      compression,
      userAgent,
      timeoutMillis: resolved.batch.exportTimeoutMs,
      httpAgentOptions: agentOptions(resolved.tls),
    });
  }
  const metadata = new Metadata();
  for (const [k, v] of Object.entries(resolved.headers)) metadata.set(k.toLowerCase(), v);
  const options: ConstructorParameters<typeof OtlpGrpcExporter>[0] = {
    url: endpoint,
    metadata,
    compression,
    userAgent,
    timeoutMillis: resolved.batch.exportTimeoutMs,
  };
  const tls = resolved.tls;
  if (
    new URL(endpoint).protocol === "https:" &&
    (tls.caCertPath !== undefined ||
      tls.caCertPem !== undefined ||
      tls.clientCertPath !== undefined)
  ) {
    const ca = [tls.caCertPem, readPem(tls.caCertPath)]
      .filter((v): v is string => v !== undefined)
      .join("\n");
    const key = readPem(tls.clientKeyPath);
    const cert = readPem(tls.clientCertPath);
    options.credentials = grpcCredentials.createSsl(
      ca === "" ? null : Buffer.from(ca),
      key === undefined ? null : Buffer.from(key),
      cert === undefined ? null : Buffer.from(cert),
    );
  }
  return new OtlpGrpcExporter(options);
}

/**
 * Sets up OpenTelemetry tracing that exports OTLP/gRPC to SkyWalking OAP (SPEC §5). Endpoint,
 * protocol and token come from `config.skywalking` (default `SkyWalkingConfig.fromEnv()`); every
 * {@link TracingConfig} field overrides them. Adds the `sw8` propagator name (extract by
 * default; see {@link Sw8Propagator}). Spans are readable with `client.traces` (Zipkin query
 * API) about 5–15 s after export. Throws `ConfigError` for invalid settings.
 */
export function initTracing(
  config: SkyWalkingTracingConfig,
  options: InitTracingOptions = {},
): TracingHandle {
  const { skywalking, ...tracing } = config;
  const sw =
    skywalking === undefined ? SkyWalkingConfig.fromEnv() : SkyWalkingConfig.from(skywalking);
  return initTracingWith(tracing, createSkyWalkingSpanExporter, {
    ...options,
    defaults: { ...skywalkingTracingDefaults(sw), ...options.defaults },
    propagators: { sw8: () => new Sw8Propagator(), ...options.propagators },
  });
}
