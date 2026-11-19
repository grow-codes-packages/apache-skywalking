// Service-owned: SkyWalkingTraceQueryClient — the SPEC §5 TraceQueryClient over OAP's two trace
// stores (Zipkin model for OTLP spans, native model for SkyWalking agents).

import {
  NotFoundError,
  UnsupportedError,
  ValidationError,
  pollUntil,
  type PollOptions,
} from "./core/index.js";
import type { CallOptions, SkyWalkingApi } from "./api.js";
import type { TraceBackend } from "./config.js";
import { nativeTraceToTrace, topologyToDependencies } from "./native.js";
import type { SkyWalkingQueryClient } from "./query.js";
import {
  BaseTraceQueryClient,
  waitForTrace as waitForHexTrace,
  type DependencyLink,
  type DependencyOptions,
  type Trace,
  type TraceSearchQuery,
  type TraceSummary,
  type WaitForTraceOptions,
} from "./tracing/index.js";
import {
  isHexTraceId,
  stringsFromBody,
  summarize,
  zipkinSpansFromBody,
  zipkinTraceId,
  zipkinTraceToTrace,
  zipkinTracesFromBody,
} from "./zipkin.js";

/** Default `limit` of {@link SkyWalkingTraceQueryClient.searchTraces} (SPEC §5). */
export const DEFAULT_TRACE_SEARCH_LIMIT = 20;

function uniqueSorted(values: Iterable<string>): string[] {
  return [...new Set(values)].filter((v) => v !== "").sort();
}

function epochMs(date: Date, what: string, operation: string): number {
  const ms = date.getTime();
  if (!Number.isFinite(ms) || ms <= 0)
    throw new ValidationError(`${operation}: ${what} must be a valid time`);
  return ms;
}

/**
 * {@link TraceQueryClient} for SkyWalking. OTLP spans (what `initTracing` sends) are stored in
 * OAP's Zipkin model and read via the Zipkin query API (`:9412/zipkin`, needs `SW_QUERY_ZIPKIN`);
 * SkyWalking agent segments are read via GraphQL. The backend comes from
 * `SkyWalkingConfig.traceBackend`:
 *
 * | method | `zipkin` | `native` | `auto` (default) |
 * | - | - | - | - |
 * | `getTrace` | Zipkin | GraphQL | hex id → Zipkin, then GraphQL; other ids → GraphQL |
 * | `searchTraces` | Zipkin | GraphQL `queryTraces` | Zipkin |
 * | `listServices`, `listOperations` | Zipkin | GraphQL | union of both |
 * | `getDependencies` | `UnsupportedError` | topology | topology |
 *
 * Native traces keep their opaque ids (`<hex>.<n>.<ms>`); native span ids are
 * `"<segmentId>-<spanId>"`. Obtain it from `SkyWalkingClient.traces`.
 */
export class SkyWalkingTraceQueryClient extends BaseTraceQueryClient {
  readonly #api: SkyWalkingApi;
  readonly #query: SkyWalkingQueryClient;
  /** The configured backend. */
  readonly backend: TraceBackend;

  /** @internal Use `SkyWalkingClient.traces`. */
  constructor(api: SkyWalkingApi, query: SkyWalkingQueryClient, backend: TraceBackend) {
    super();
    this.#api = api;
    this.#query = query;
    this.backend = backend;
  }

  /** Zipkin read of one trace: `GET /api/v2/trace/{id}`; `null` on 404. */
  async #zipkinTrace(traceId: string, signal?: AbortSignal): Promise<Trace | null> {
    const operation = "traces.getTrace";
    const id = zipkinTraceId(traceId, operation);
    const res = await this.#api.zipkinGet(`/api/v2/trace/${id}`, operation, {}, signal, [404]);
    if (res.status === 404) return null;
    return zipkinTraceToTrace(zipkinSpansFromBody(res.data));
  }

  async #nativeTrace(traceId: string, signal?: AbortSignal): Promise<Trace | null> {
    return nativeTraceToTrace(
      await this.#query.getTrace(traceId, signal === undefined ? {} : { signal }),
    );
  }

  /**
   * One trace in the SPEC model, `null` when it does not exist. Zipkin rejects non-hex ids with
   * `ValidationError`.
   */
  async getTrace(traceId: string, opts: CallOptions = {}): Promise<Trace | null> {
    if (typeof traceId !== "string" || traceId.trim() === "")
      throw new ValidationError("traces.getTrace: traceId is required");
    if (this.backend === "zipkin") return this.#zipkinTrace(traceId, opts.signal);
    if (this.backend === "native" || !isHexTraceId(traceId))
      return this.#nativeTrace(traceId.trim(), opts.signal);
    return (
      (await this.#zipkinTrace(traceId, opts.signal)) ??
      this.#nativeTrace(traceId.trim().toLowerCase(), opts.signal)
    );
  }

  /**
   * SPEC search. Zipkin: `service` → `serviceName`, `operation` → `spanName` (lower-cased like
   * OAP stores names), `tags` → `annotationQuery` (`k=v and …`; keys must be in
   * `SW_ZIPKIN_SEARCHABLE_TAG_KEYS`), durations in µs, `endTs` = `end` (default now), `lookback`
   * = `end - start`. Native: `queryTraces` with `operation` as endpoint name and durations in ms.
   */
  async searchTraces(
    query: TraceSearchQuery = {},
    opts: CallOptions = {},
  ): Promise<TraceSummary[]> {
    const operation = "traces.searchTraces";
    const limit = query.limit ?? DEFAULT_TRACE_SEARCH_LIMIT;
    if (!Number.isInteger(limit) || limit <= 0)
      throw new ValidationError(`${operation}: limit must be a positive integer`);
    for (const v of [query.minDurationMicros, query.maxDurationMicros]) {
      if (v !== undefined && (!Number.isFinite(v) || v < 0))
        throw new ValidationError(`${operation}: durations must be non-negative`);
    }
    const endMs = epochMs(query.end ?? new Date(this.#api.now()), "end", operation);
    const startMs =
      query.start === undefined ? undefined : epochMs(query.start, "start", operation);
    if (startMs !== undefined && startMs > endMs)
      throw new ValidationError(`${operation}: start must not be after end`);
    if (this.backend === "native") {
      if (query.operation !== undefined && query.service === undefined)
        throw new ValidationError(`${operation}: operation needs service on the native backend`);
      const res = await this.#query.queryTraces({
        ...(query.service === undefined ? {} : { service: query.service }),
        ...(query.operation === undefined ? {} : { endpoint: query.operation }),
        ...(query.tags === undefined ? {} : { tags: query.tags }),
        ...(query.minDurationMicros === undefined
          ? {}
          : { minDurationMs: Math.floor(query.minDurationMicros / 1000) }),
        ...(query.maxDurationMicros === undefined
          ? {}
          : { maxDurationMs: Math.ceil(query.maxDurationMicros / 1000) }),
        range: {
          end: new Date(endMs),
          start: new Date(startMs ?? endMs - 3_600_000),
        },
        paging: { pageNum: 1, pageSize: limit },
        ...(opts.signal === undefined ? {} : { signal: opts.signal }),
      });
      return res.traces
        .map(nativeTraceToTrace)
        .filter((t): t is Trace => t !== null)
        .map(summarize);
    }
    const terms = Object.entries(query.tags ?? {}).map(([k, v]) => `${k}=${v}`);
    if (terms.some((t) => t.includes(" and ")))
      throw new ValidationError(`${operation}: tags must not contain " and "`);
    const res = await this.#api.zipkinGet(
      "/api/v2/traces",
      operation,
      {
        serviceName: query.service,
        spanName: query.operation?.toLowerCase(),
        annotationQuery: terms.length === 0 ? undefined : terms.join(" and "),
        minDuration:
          query.minDurationMicros === undefined ? undefined : Math.round(query.minDurationMicros),
        maxDuration:
          query.maxDurationMicros === undefined ? undefined : Math.round(query.maxDurationMicros),
        endTs: endMs,
        lookback: startMs === undefined ? undefined : Math.max(1, endMs - startMs),
        limit,
      },
      opts.signal,
    );
    return zipkinTracesFromBody(res.data)
      .map(zipkinTraceToTrace)
      .filter((t): t is Trace => t !== null)
      .map(summarize);
  }

  async #zipkinServices(signal?: AbortSignal): Promise<string[]> {
    return stringsFromBody(
      (await this.#api.zipkinGet("/api/v2/services", "traces.listServices", {}, signal)).data,
    );
  }

  async #nativeServices(signal?: AbortSignal): Promise<string[]> {
    const services = await this.#query.listServices(
      null,
      undefined,
      signal === undefined ? {} : { signal },
    );
    return services.filter((s) => s.normal).map((s) => s.name);
  }

  /**
   * Service names (sorted, unique). New services appear about 30–35 s after their first data
   * (OAP flushes its name index periodically).
   */
  async listServices(opts: CallOptions = {}): Promise<string[]> {
    const parts: Promise<string[]>[] = [];
    if (this.backend !== "native") parts.push(this.#zipkinServices(opts.signal));
    if (this.backend !== "zipkin") parts.push(this.#nativeServices(opts.signal));
    return uniqueSorted((await Promise.all(parts)).flat());
  }

  /** Operation names of a service (Zipkin span names and/or native endpoint names), sorted. */
  async listOperations(service: string, opts: CallOptions = {}): Promise<string[]> {
    const operation = "traces.listOperations";
    if (typeof service !== "string" || service.trim() === "")
      throw new ValidationError(`${operation}: service is required`);
    const parts: Promise<string[]>[] = [];
    if (this.backend !== "native")
      parts.push(
        this.#api
          .zipkinGet("/api/v2/spans", operation, { serviceName: service }, opts.signal)
          .then((r) => stringsFromBody(r.data)),
      );
    if (this.backend !== "zipkin")
      parts.push(
        this.#query
          .listEndpoints(
            service,
            undefined,
            opts.signal === undefined ? {} : { signal: opts.signal },
          )
          .then((eps) => eps.map((e) => e.name)),
      );
    return uniqueSorted((await Promise.all(parts)).flat());
  }

  /**
   * Service dependencies from the native topology (`callCount` is 0: the topology carries no
   * counts). OAP 10.4 does not implement Zipkin `/dependencies`, so the `zipkin` backend throws
   * `UnsupportedError`; OTLP spans create no topology edges.
   */
  override async getDependencies(
    opts: DependencyOptions & CallOptions = {},
  ): Promise<DependencyLink[]> {
    if (this.backend === "zipkin")
      throw new UnsupportedError(
        "traces.getDependencies: OAP does not implement the Zipkin dependencies API",
      );
    const topology = await this.#query.topology(
      {
        ...(opts.start === undefined ? {} : { start: opts.start }),
        ...(opts.end === undefined ? {} : { end: opts.end }),
      },
      opts.signal === undefined ? {} : { signal: opts.signal },
    );
    return topologyToDependencies(topology);
  }

  /**
   * Polls {@link getTrace} until the trace has `minSpans` spans (`TimeoutError` otherwise).
   * Native ids are used verbatim; hex ids are normalized.
   */
  override waitForTrace(traceId: string, opts: WaitForTraceOptions = {}): Promise<Trace> {
    if (isHexTraceId(traceId)) return waitForHexTrace(this, traceId, opts);
    const minSpans = opts.minSpans ?? 1;
    const pollOpts: PollOptions = {
      timeoutMs: opts.timeoutMs ?? 30_000,
      intervalMs: opts.intervalMs ?? 1000,
      operation: `tracing.waitForTrace ${traceId}`,
      ignoreError: (error) => error instanceof NotFoundError,
    };
    if (opts.signal !== undefined) pollOpts.signal = opts.signal;
    return pollUntil(
      () => this.getTrace(traceId),
      (t) => t !== null && t.spans.length >= minSpans,
      pollOpts,
    ) as Promise<Trace>;
  }
}
