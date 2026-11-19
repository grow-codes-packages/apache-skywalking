// Service-owned: SkyWalkingConfig — connection settings for the OAP server (GraphQL/REST on 12800,
// OTLP gRPC on 11800, Zipkin query on 9412).

import {
  type CommonConfig,
  type CommonConfigOptions,
  type CommonVendorEnv,
  ConfigError,
  type EnvMap,
  EnvReader,
  type Hooks,
  type ClientLogger,
  REDACTED,
  type RetryPolicy,
  Secret,
  type TlsOptions,
  describeCommonConfig,
  isSensitiveHeader,
  mergeOptions,
  readCommonEnv,
  redactUrl,
  resolveCommonConfig,
  secretValues,
  validateCommonConfig,
} from "./core/index.js";

const INSPECT = Symbol.for("nodejs.util.inspect.custom");

/** Which store `TraceQueryClient` reads (see {@link SkyWalkingConfigOptions.traceBackend}). */
export type TraceBackend = "auto" | "zipkin" | "native";

/** OTLP protocol of the trace exporter. OAP 10.4 only serves `grpc`. */
export type OtlpProtocol = "grpc" | "http/protobuf";

/** Options accepted by {@link SkyWalkingConfig} (all optional). */
export interface SkyWalkingConfigOptions extends CommonConfigOptions {
  /**
   * OAP REST/GraphQL base URL (port 12800). Default `http://127.0.0.1:12800`. A trailing
   * `/graphql` is stripped.
   */
  url?: string;
  /** Path of the GraphQL endpoint below {@link url}. Default `/graphql`. */
  graphqlPath?: string;
  /**
   * OTLP endpoint for `initTracing` (OAP gRPC receiver, port 11800). Default
   * `http://<url host>:11800`.
   */
  otlpEndpoint?: string;
  /**
   * OTLP protocol. Default `grpc` (OAP 10.4 has no OTLP/HTTP receiver; use `http/protobuf` only
   * when {@link otlpEndpoint} points at an OpenTelemetry Collector).
   */
  otlpProtocol?: OtlpProtocol;
  /** Extra OTLP exporter headers (gRPC metadata), e.g. for a gateway in front of OAP. */
  otlpHeaders?: Record<string, string>;
  /** Zipkin v2 query API base (OAP `SW_QUERY_ZIPKIN`). Default `http://<url host>:9412/zipkin`. */
  zipkinUrl?: string;
  /**
   * OAP gRPC token (`SW_AUTHENTICATION`), sent as the `authentication` metadata on OTLP exports.
   * OAP does not protect GraphQL or `/v3/*` with it.
   */
  authentication?: string | Secret;
  /**
   * Trace store read by `client.traces`: `zipkin` (OTLP spans, read via the Zipkin query API),
   * `native` (SkyWalking agent segments, read via GraphQL) or `auto` (default: hex ids via Zipkin
   * first, then native; other ids native).
   */
  traceBackend?: TraceBackend;
}

/** Vendor environment variable names, consulted after `GROW_APACHE_SKYWALKING_<NAME>`, in order. */
export const VENDOR_ENV = {
  url: ["GROW_SKYWALKING_URL", "SW_GRAPHQL_URL", "SW_OAP_ADDRESS"],
  caCert: ["GROW_SKYWALKING_CA_CERT"],
  clientCert: ["GROW_SKYWALKING_CLIENT_CERT"],
  clientKey: ["GROW_SKYWALKING_CLIENT_KEY"],
  insecureSkipVerify: ["GROW_SKYWALKING_INSECURE_SKIP_VERIFY"],
  proxy: ["GROW_SKYWALKING_PROXY"],
  otlpEndpoint: [
    "GROW_SKYWALKING_OTLP_ENDPOINT",
    "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
    "OTEL_EXPORTER_OTLP_ENDPOINT",
    "SW_AGENT_COLLECTOR_BACKEND_SERVICES",
  ],
  otlpProtocol: [
    "GROW_SKYWALKING_OTLP_PROTOCOL",
    "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL",
    "OTEL_EXPORTER_OTLP_PROTOCOL",
  ],
  otlpHeaders: ["OTEL_EXPORTER_OTLP_TRACES_HEADERS", "OTEL_EXPORTER_OTLP_HEADERS"],
  authentication: [
    "GROW_SKYWALKING_AUTHENTICATION",
    "SW_AUTHENTICATION",
    "SW_AGENT_AUTHENTICATION",
  ],
  zipkinUrl: ["GROW_SKYWALKING_ZIPKIN_URL", "SW_ZIPKIN_ADDRESS"],
  traceBackend: ["GROW_SKYWALKING_TRACE_BACKEND"],
} as const satisfies CommonVendorEnv & Record<string, readonly string[]>;

const BACKENDS: readonly TraceBackend[] = ["auto", "zipkin", "native"];
const PROTOCOLS: readonly OtlpProtocol[] = ["grpc", "http/protobuf"];

function stripSlash(url: string): string {
  return url.replace(/\/+$/, "");
}

/** Strips a trailing `/graphql` (with or without slash) from a GraphQL URL. */
export function baseFromGraphqlUrl(url: string): string {
  return stripSlash(stripSlash(url).replace(/\/graphql$/i, ""));
}

/** `host:port[,host:port]` (SkyWalking agent collector address) → `http://host:port`. */
export function collectorToUrl(value: string): string {
  const first = (value.split(",")[0] ?? "").trim();
  return /^[a-z][a-z0-9+.-]*:\/\//i.test(first) ? first : `http://${first}`;
}

/** A Zipkin address with or without the `/zipkin` path → `<address>/zipkin`. */
export function zipkinQueryUrl(value: string): string {
  const base = stripSlash(value);
  return /\/zipkin$/i.test(base) ? base : `${base}/zipkin`;
}

/**
 * Parses OTEL-style headers `k1=v1,k2=v2` (values URL-decoded). Throws {@link ConfigError}
 * naming `source` (never the value) when an entry is malformed.
 */
export function parseOtlpHeaders(raw: string, source = "headers"): Record<string, string> {
  const out: Record<string, string> = {};
  for (const part of raw.split(",")) {
    if (part.trim() === "") continue;
    const eq = part.indexOf("=");
    const key = eq <= 0 ? "" : part.slice(0, eq).trim();
    if (key === "") throw new ConfigError(`invalid value in ${source}: expected key=value pairs`);
    let value = part.slice(eq + 1).trim();
    try {
      value = decodeURIComponent(value);
    } catch {
      throw new ConfigError(`invalid value in ${source}: bad percent-encoding`);
    }
    out[key.toLowerCase()] = value;
  }
  return out;
}

function hostUrl(url: string, port: number, path = ""): string {
  const u = new URL(url);
  return `${u.protocol}//${u.hostname}:${port}${path}`;
}

function checkHttpUrl(value: string, field: string): void {
  let url: URL | undefined;
  try {
    url = new URL(value);
  } catch {
    url = undefined;
  }
  if (url === undefined || (url.protocol !== "http:" && url.protocol !== "https:")) {
    throw new ConfigError(`invalid configuration: ${field} must be an http(s) URL`);
  }
}

/** Immutable configuration of {@link SkyWalkingClient}. */
export class SkyWalkingConfig implements CommonConfig {
  /** Default REST/GraphQL endpoint. */
  static readonly DEFAULT_URL = "http://127.0.0.1:12800";
  /** OAP gRPC port (OTLP receiver). */
  static readonly OTLP_GRPC_PORT = 11800;
  /** OAP Zipkin query port. */
  static readonly ZIPKIN_QUERY_PORT = 9412;
  /** Prefix of every environment variable. */
  static readonly ENV_PREFIX = "GROW_APACHE_SKYWALKING_";

  readonly url: string;
  readonly connectTimeoutMs: number;
  readonly requestTimeoutMs: number;
  readonly totalTimeoutMs: number;
  readonly retry: RetryPolicy;
  readonly tls: Readonly<TlsOptions>;
  readonly proxy?: string;
  readonly headers: Readonly<Record<string, string>>;
  readonly userAgentSuffix?: string;
  readonly hooks?: Hooks;
  readonly logger?: ClientLogger;
  /** GraphQL path (default `/graphql`). */
  readonly graphqlPath: string;
  /** OTLP endpoint for traces. */
  readonly otlpEndpoint: string;
  /** OTLP protocol. */
  readonly otlpProtocol: OtlpProtocol;
  /** Extra OTLP headers. */
  readonly otlpHeaders: Readonly<Record<string, string>>;
  /** Zipkin query API base (ends with `/zipkin`). */
  readonly zipkinUrl: string;
  /** OAP gRPC token. */
  readonly authentication?: Secret;
  /** Trace store read by `client.traces`. */
  readonly traceBackend: TraceBackend;

  /** Applies defaults, validates (throws {@link ConfigError}) and freezes. */
  constructor(options: SkyWalkingConfigOptions = {}) {
    const url = options.url === undefined ? undefined : baseFromGraphqlUrl(options.url);
    const common = resolveCommonConfig(
      { ...options, ...(url === undefined ? {} : { url }) },
      SkyWalkingConfig.DEFAULT_URL,
    );
    this.url = common.url;
    this.connectTimeoutMs = common.connectTimeoutMs;
    this.requestTimeoutMs = common.requestTimeoutMs;
    this.totalTimeoutMs = common.totalTimeoutMs;
    this.retry = common.retry;
    this.tls = common.tls;
    this.headers = common.headers;
    if (common.proxy !== undefined) this.proxy = common.proxy;
    if (common.userAgentSuffix !== undefined) this.userAgentSuffix = common.userAgentSuffix;
    if (common.hooks !== undefined) this.hooks = common.hooks;
    if (common.logger !== undefined) this.logger = common.logger;
    checkHttpUrl(this.url, "url");
    this.graphqlPath = options.graphqlPath ?? "/graphql";
    this.otlpEndpoint = stripSlash(
      options.otlpEndpoint ?? hostUrl(this.url, SkyWalkingConfig.OTLP_GRPC_PORT),
    );
    this.otlpProtocol = options.otlpProtocol ?? "grpc";
    this.otlpHeaders = Object.freeze(
      Object.fromEntries(
        Object.entries(options.otlpHeaders ?? {}).map(([k, v]) => [k.toLowerCase(), v]),
      ),
    );
    this.zipkinUrl = stripSlash(
      options.zipkinUrl ?? hostUrl(this.url, SkyWalkingConfig.ZIPKIN_QUERY_PORT, "/zipkin"),
    );
    const auth = Secret.from(options.authentication);
    if (auth !== undefined) this.authentication = auth;
    this.traceBackend = options.traceBackend ?? "auto";
    this.validate();
    Object.freeze(this);
  }

  /** Returns `options` when it already is a config, otherwise builds one. */
  static from(options: SkyWalkingConfigOptions = {}): SkyWalkingConfig {
    if (options instanceof SkyWalkingConfig) return options;
    return new SkyWalkingConfig(options);
  }

  /**
   * Builds a config from the environment. Precedence: explicit `overrides` >
   * `GROW_APACHE_SKYWALKING_<NAME>` > vendor variables ({@link VENDOR_ENV}, in order) > defaults.
   * `SW_GRAPHQL_URL` loses a trailing `/graphql`, `SW_AGENT_COLLECTOR_BACKEND_SERVICES`
   * (`host:port`) becomes `http://host:port` and `SW_ZIPKIN_ADDRESS` gains `/zipkin`.
   */
  static fromEnv(
    overrides: SkyWalkingConfigOptions = {},
    env: EnvMap = process.env,
  ): SkyWalkingConfig {
    const reader = new EnvReader(SkyWalkingConfig.ENV_PREFIX, env);
    const fromEnv: SkyWalkingConfigOptions = readCommonEnv(reader, VENDOR_ENV);
    const otlp = reader.lookup("OTLP_ENDPOINT", VENDOR_ENV.otlpEndpoint);
    if (otlp !== undefined) {
      fromEnv.otlpEndpoint =
        otlp.name === "SW_AGENT_COLLECTOR_BACKEND_SERVICES"
          ? collectorToUrl(otlp.value)
          : otlp.value;
    }
    const protocol = reader.lookup("OTLP_PROTOCOL", VENDOR_ENV.otlpProtocol);
    if (protocol !== undefined) {
      if (!PROTOCOLS.includes(protocol.value as OtlpProtocol)) {
        throw new ConfigError(
          `invalid value in environment variable ${protocol.name}: expected grpc or http/protobuf`,
        );
      }
      fromEnv.otlpProtocol = protocol.value as OtlpProtocol;
    }
    const headers = reader.lookup("OTLP_HEADERS", VENDOR_ENV.otlpHeaders);
    if (headers !== undefined) {
      fromEnv.otlpHeaders = parseOtlpHeaders(headers.value, `environment variable ${headers.name}`);
    }
    const auth = reader.secret("AUTHENTICATION", VENDOR_ENV.authentication);
    if (auth !== undefined) fromEnv.authentication = auth;
    const zipkin = reader.lookup("ZIPKIN_URL", VENDOR_ENV.zipkinUrl);
    if (zipkin !== undefined) fromEnv.zipkinUrl = zipkinQueryUrl(zipkin.value);
    const backend = reader.lookup("TRACE_BACKEND", VENDOR_ENV.traceBackend);
    if (backend !== undefined) {
      if (!BACKENDS.includes(backend.value as TraceBackend)) {
        throw new ConfigError(
          `invalid value in environment variable ${backend.name}: expected auto, zipkin or native`,
        );
      }
      fromEnv.traceBackend = backend.value as TraceBackend;
    }
    const graphqlPath = reader.string("GRAPHQL_PATH");
    if (graphqlPath !== undefined) fromEnv.graphqlPath = graphqlPath;
    const merged = mergeOptions<SkyWalkingConfigOptions>(fromEnv, overrides);
    return new SkyWalkingConfig(merged);
  }

  /** Throws {@link ConfigError} when a setting is invalid. */
  validate(): void {
    validateCommonConfig(this);
    if (!this.graphqlPath.startsWith("/"))
      throw new ConfigError("invalid configuration: graphqlPath must start with /");
    checkHttpUrl(this.otlpEndpoint, "otlpEndpoint");
    checkHttpUrl(this.zipkinUrl, "zipkinUrl");
    if (!PROTOCOLS.includes(this.otlpProtocol))
      throw new ConfigError("invalid configuration: otlpProtocol must be grpc or http/protobuf");
    if (!BACKENDS.includes(this.traceBackend))
      throw new ConfigError("invalid configuration: traceBackend must be auto, zipkin or native");
    if (this.authentication?.reveal().trim() === "")
      throw new ConfigError("invalid configuration: authentication must not be blank");
  }

  /** Absolute GraphQL URL. */
  get graphqlUrl(): string {
    return `${stripSlash(this.url)}${this.graphqlPath}`;
  }

  /** Credential values to redact from error messages and logs. */
  knownSecrets(): string[] {
    const headerSecrets = Object.entries(this.otlpHeaders)
      .filter(([k]) => isSensitiveHeader(k))
      .map(([, v]) => v);
    return [...secretValues(this.authentication), ...headerSecrets];
  }

  /** Debug form with every credential redacted. */
  toJSON(): Record<string, unknown> {
    return {
      ...describeCommonConfig(this),
      graphqlPath: this.graphqlPath,
      otlpEndpoint: redactUrl(this.otlpEndpoint),
      otlpProtocol: this.otlpProtocol,
      otlpHeaders: Object.fromEntries(Object.keys(this.otlpHeaders).map((k) => [k, REDACTED])),
      zipkinUrl: redactUrl(this.zipkinUrl),
      authentication: this.authentication === undefined ? null : REDACTED,
      traceBackend: this.traceBackend,
    };
  }

  toString(): string {
    return `SkyWalkingConfig(${JSON.stringify(this.toJSON())})`;
  }

  [INSPECT](): string {
    return this.toString();
  }
}
