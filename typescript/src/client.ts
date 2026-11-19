// Service-owned: SkyWalkingClient — health/ping/version, the GraphQL query API, trace and log
// query clients, the native log client and tracing setup for Apache SkyWalking OAP.

import {
  type HealthStatus,
  HttpTransport,
  type TransportOptions,
  UnsupportedError,
  healthStatus,
  probeHealth,
} from "./core/index.js";
import { SkyWalkingApi, type CallOptions, type GraphQLResponse } from "./api.js";
import { SkyWalkingConfig, type SkyWalkingConfigOptions } from "./config.js";
import {
  SkyWalkingLogExporter,
  SkyWalkingLogQueryClient,
  type SkyWalkingLogQueryOptions,
} from "./logs.js";
import { BatchingLogClient, type BatchingLogClientOptions } from "./logging/index.js";
import { SkyWalkingQueryClient } from "./query.js";
import { initTracing, type SkyWalkingTracingConfig } from "./setup.js";
import { SkyWalkingTraceQueryClient } from "./traces.js";
import type { InitTracingOptions, TracingHandle } from "./tracing/index.js";

export type { CallOptions } from "./api.js";

/** Test seams (undici `MockAgent`, fake sleep, clock). Not part of the stable API. */
export type ClientInternals = Pick<TransportOptions, "dispatcher" | "sleep" | "now"> &
  SkyWalkingLogQueryOptions;

/** Options of {@link SkyWalkingClient.logClient} / {@link createLogClient}. */
export interface SkyWalkingLogClientOptions extends BatchingLogClientOptions {
  /** `service.name` of the records (resource). Default `unknown_service`. */
  serviceName?: string;
  /** `service.instance.id` of the records (resource). */
  serviceInstance?: string;
}

function logResource(options: SkyWalkingLogClientOptions): BatchingLogClientOptions["resource"] {
  return {
    ...options.resource,
    ...(options.serviceName === undefined ? {} : { "service.name": options.serviceName }),
    ...(options.serviceInstance === undefined
      ? {}
      : { "service.instance.id": options.serviceInstance }),
  };
}

function createTransport(cfg: SkyWalkingConfig, internals: ClientInternals): HttpTransport {
  return new HttpTransport({
    ...(internals.dispatcher === undefined ? {} : { dispatcher: internals.dispatcher }),
    ...(internals.sleep === undefined ? {} : { sleep: internals.sleep }),
    ...(internals.now === undefined ? {} : { now: internals.now }),
    config: cfg,
    knownSecrets: () => cfg.knownSecrets(),
  });
}

/**
 * Standalone {@link BatchingLogClient} that sends native logs to `POST /v3/logs` (owns its
 * connections; `close()` flushes and releases them).
 */
export function createLogClient(
  config: SkyWalkingConfig | SkyWalkingConfigOptions = {},
  options: SkyWalkingLogClientOptions = {},
  internals: ClientInternals = {},
): BatchingLogClient {
  const cfg = SkyWalkingConfig.from(config);
  const exporter = new SkyWalkingLogExporter(createTransport(cfg, internals), {
    ownsTransport: true,
    ...(options.serviceName === undefined ? {} : { serviceName: options.serviceName }),
  });
  return new BatchingLogClient(exporter, { ...options, resource: logResource(options) });
}

/** Client for Apache SkyWalking OAP. */
export class SkyWalkingClient {
  /** The immutable configuration. */
  readonly config: SkyWalkingConfig;
  readonly #transport: HttpTransport;
  readonly #api: SkyWalkingApi;
  readonly #query: SkyWalkingQueryClient;
  readonly #traces: SkyWalkingTraceQueryClient;
  readonly #logs: SkyWalkingLogQueryClient;
  readonly #owned = new Set<{ close?: () => Promise<void>; shutdown?: () => Promise<void> }>();
  #closed: Promise<void> | undefined;

  /** Accepts a {@link SkyWalkingConfig} or plain options. */
  constructor(config: SkyWalkingConfigOptions = {}, internals: ClientInternals = {}) {
    const cfg = SkyWalkingConfig.from(config);
    this.config = cfg;
    this.#transport = createTransport(cfg, internals);
    this.#api = new SkyWalkingApi(this.#transport, cfg, internals.now ?? Date.now);
    this.#query = new SkyWalkingQueryClient(this.#api);
    this.#traces = new SkyWalkingTraceQueryClient(this.#api, this.#query, cfg.traceBackend);
    this.#logs = new SkyWalkingLogQueryClient(
      this.#query,
      this.#api.now,
      internals.maxScanPages === undefined ? {} : { maxScanPages: internals.maxScanPages },
    );
  }

  /** Builds a client from `GROW_APACHE_SKYWALKING_*` (and vendor) environment variables. */
  static fromEnv(overrides: SkyWalkingConfigOptions = {}): SkyWalkingClient {
    return new SkyWalkingClient(SkyWalkingConfig.fromEnv(overrides));
  }

  /** The shared HTTP transport (for service modules). @internal */
  get transport(): HttpTransport {
    return this.#transport;
  }

  /** The GraphQL query API: services, endpoints, instances, native traces, logs, topology. */
  get query(): SkyWalkingQueryClient {
    return this.#query;
  }

  /** SPEC `TraceQueryClient` (Zipkin store for OTLP spans, native store for agents). */
  get traces(): SkyWalkingTraceQueryClient {
    return this.#traces;
  }

  /** SPEC `LogQueryClient`. */
  get logs(): SkyWalkingLogQueryClient {
    return this.#logs;
  }

  /**
   * Liveness: `GET /healthcheck` (OAP health checker, `SW_HEALTH_CHECKER=default`): 200 → ok,
   * 503 → unhealthy. Without the health checker (404) falls back to GraphQL `checkHealth`
   * (score 0 = ok). Never throws for an unhealthy or unreachable service (only `CANCELLED` and
   * `CONFIG`).
   */
  health(options: CallOptions = {}): Promise<HealthStatus> {
    return probeHealth(async () => {
      const res = await this.#transport.get<string | undefined>("/healthcheck", {
        operation: "health",
        responseType: "text",
        allowStatus: [404, 503],
        ...options,
      });
      if (res.status === 503) return healthStatus("unhealthy", { httpStatus: 503 }, false);
      if (res.status !== 404) return healthStatus("ok", {}, true);
      const check = await this.#api.field<{ score?: unknown; details?: unknown } | null>(
        "{checkHealth{score details}}",
        {},
        "checkHealth",
        "health",
        options.signal,
      );
      const score = typeof check?.score === "number" ? check.score : -1;
      const details = typeof check?.details === "string" ? check.details : "";
      return score === 0
        ? healthStatus("ok", {}, true)
        : healthStatus("unhealthy", { score, ...(details === "" ? {} : { details }) }, false);
    });
  }

  /**
   * Timed GraphQL round trip (`getTimeInfo`; also caches the OAP timezone). OAP has no query
   * authentication: `AuthenticationError` only comes from a gateway in front of it.
   */
  async ping(options: CallOptions = {}): Promise<number> {
    const start = performance.now();
    await this.#api.timeInfo(options.signal);
    return performance.now() - start;
  }

  /** OAP version from GraphQL `{version}` (e.g. `10.4.0-b272da3`). */
  async serverVersion(options: CallOptions = {}): Promise<string> {
    const version = await this.#api.field<unknown>(
      "{version}",
      {},
      "version",
      "serverVersion",
      options.signal,
    );
    if (typeof version !== "string" || version === "")
      throw new UnsupportedError("serverVersion: the service does not report a version");
    return version;
  }

  /** Raw GraphQL (same as `client.query.graphql`). */
  graphql<T = Record<string, unknown>>(
    query: string,
    variables: Record<string, unknown> = {},
    options: CallOptions = {},
  ): Promise<GraphQLResponse<T>> {
    return this.#query.graphql<T>(query, variables, options);
  }

  /**
   * A {@link BatchingLogClient} that sends native logs (`POST /v3/logs`) over this client's
   * connections. {@link close} flushes and closes it.
   */
  logClient(options: SkyWalkingLogClientOptions = {}): BatchingLogClient {
    const exporter = new SkyWalkingLogExporter(this.#transport, {
      ...(options.serviceName === undefined ? {} : { serviceName: options.serviceName }),
    });
    const client = new BatchingLogClient(exporter, { ...options, resource: logResource(options) });
    this.#owned.add(client);
    return client;
  }

  /**
   * {@link initTracing} with this client's config (OTLP endpoint, protocol, token).
   * {@link close} shuts the handle down.
   */
  initTracing(
    config: Omit<SkyWalkingTracingConfig, "skywalking">,
    options: InitTracingOptions = {},
  ): TracingHandle {
    const handle = initTracing({ ...config, skywalking: this.config }, options);
    this.#owned.add(handle);
    return handle;
  }

  /** Flushes and closes log clients and tracing handles created here, then releases connections. Idempotent. */
  close(): Promise<void> {
    this.#closed ??= (async () => {
      for (const owned of this.#owned) {
        await (owned.close?.() ?? owned.shutdown?.())?.catch(() => undefined);
      }
      this.#owned.clear();
      await this.#transport.close();
    })();
    return this.#closed;
  }
}
