// Service-owned: SkyWalkingQueryClient — the OAP GraphQL query API (metadata, native traces, logs,
// topology, raw GraphQL).

import { ValidationError } from "./core/index.js";
import {
  type CallOptions,
  type GraphQLResponse,
  type SkyWalkingApi,
  type SkyWalkingCapabilities,
} from "./api.js";
import { type TimeRange, endpointId, instanceId, serviceId } from "./duration.js";
import {
  NATIVE_SPAN_FIELDS,
  type KeyValue,
  type NativeTrace,
  type Topology,
  decodeTopology,
  keyValues,
  nativeTraceFromSpans,
} from "./native.js";

/** A service known to OAP (native agents, native logs, OTLP logs). */
export interface SkyWalkingService {
  /** `base64(name) + ".1"` for real services. */
  id: string;
  name: string;
  group: string;
  shortName: string;
  layers: string[];
  /** `false` for virtual (conjectured) services. */
  normal: boolean;
}

/** An endpoint (operation) of a service. */
export interface SkyWalkingEndpoint {
  id: string;
  name: string;
}

/** A service instance. */
export interface SkyWalkingInstance {
  id: string;
  name: string;
  language: string;
  instanceUUID: string;
  attributes: { name: string; value: string }[];
}

/** 1-based page selection. */
export interface Paging {
  /** Default 1. */
  pageNum?: number;
  /** Default 20. */
  pageSize?: number;
}

/** Trace state filter of {@link SkyWalkingQueryClient.queryTraces}. */
export type TraceState = "ALL" | "SUCCESS" | "ERROR";

/** Filter of {@link SkyWalkingQueryClient.queryTraces} (native traces). */
export interface QueryTracesOptions extends CallOptions {
  /** Service name. */
  service?: string;
  /** Instance name (needs `service`). */
  instance?: string;
  /** Endpoint name (needs `service`). */
  endpoint?: string;
  /** Native trace id. */
  traceId?: string;
  /** Default `ALL`. */
  traceState?: TraceState;
  /** Minimum trace duration in ms. */
  minDurationMs?: number;
  /** Maximum trace duration in ms. */
  maxDurationMs?: number;
  /** Tag matches; only keys in the server's `SW_SEARCHABLE_TAG_KEYS` can match. */
  tags?: Record<string, string>;
  /** Window (default: last hour; omitted when only `traceId` is given). */
  range?: TimeRange;
  paging?: Paging;
  /** Default `BY_START_TIME`. */
  order?: "BY_START_TIME" | "BY_DURATION";
}

/** Result of {@link SkyWalkingQueryClient.queryTraces}. */
export interface QueryTracesResult {
  /** Whole traces (every segment) that match. */
  traces: NativeTrace[];
  /** Window the server searched (epoch ms), when reported. */
  retrievedTimeRange?: { startTime: number; endTime: number };
}

/** A log entry as stored by OAP. */
export interface NativeLog {
  serviceName: string;
  serviceId: string;
  serviceInstanceName: string;
  endpointName: string;
  /** Native or hex trace id; `""` when the log has no trace context. */
  traceId: string;
  /** Epoch milliseconds. */
  timestamp: number;
  /** `TEXT`, `JSON` or `YAML`. */
  contentType: string;
  content: string;
  tags: KeyValue[];
}

/** Filter of {@link SkyWalkingQueryClient.queryLogs}. */
export interface QueryLogsOptions extends CallOptions {
  service?: string;
  /** Needs `service`. */
  instance?: string;
  /** Needs `service`. */
  endpoint?: string;
  /** Logs related to this trace (native or hex id). */
  traceId?: string;
  /** With `traceId`: the segment. */
  segmentId?: string;
  /** With `segmentId`: the span. */
  spanId?: number;
  /**
   * Words the content must contain. Sent to the server when it supports keyword search
   * (Elasticsearch storage); on BanyanDB the returned page is filtered client side
   * (case-insensitive substring).
   */
  keywords?: string[];
  /** Words the content must not contain (same rules as `keywords`). */
  excludingKeywords?: string[];
  /** Tag matches; only keys in `SW_SEARCHABLE_LOGS_TAG_KEYS` can match. */
  tags?: Record<string, string>;
  /** Window (default last hour; omitted when only `traceId` is given). */
  range?: TimeRange;
  paging?: Paging;
  /** Default `DES` (newest first). */
  order?: "DES" | "ASC";
}

/** Result of {@link SkyWalkingQueryClient.queryLogs}. */
export interface QueryLogsResult {
  logs: NativeLog[];
  /** Server-side explanation when the query could not run as asked. */
  errorReason?: string;
}

/** Options of {@link SkyWalkingQueryClient.listEndpoints}. */
export interface ListEndpointsOptions extends CallOptions {
  /** Maximum endpoints. Default 100. */
  limit?: number;
}

/** Options of {@link SkyWalkingQueryClient.topology}. */
export interface TopologyOptions extends CallOptions {
  /** Only this service's neighbourhood (`getServiceTopology`). */
  service?: string;
  /** Only this layer (global topology). */
  layer?: string;
}

const SERVICE_FIELDS = "id name group shortName layers normal";
const LOG_FIELDS =
  "serviceName serviceId serviceInstanceName endpointName traceId timestamp contentType content tags{key value}";

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function str(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function list(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value) ? value.filter(isObject) : [];
}

function decodeService(s: Record<string, unknown>): SkyWalkingService {
  return {
    id: str(s.id),
    name: str(s.name),
    group: str(s.group),
    shortName: str(s.shortName),
    layers: Array.isArray(s.layers) ? s.layers.filter((l) => typeof l === "string") : [],
    normal: s.normal !== false,
  };
}

/** Decodes one native log. */
export function decodeNativeLog(raw: unknown): NativeLog {
  const l = isObject(raw) ? raw : {};
  return {
    serviceName: str(l.serviceName),
    serviceId: str(l.serviceId),
    serviceInstanceName: str(l.serviceInstanceName),
    endpointName: str(l.endpointName),
    traceId: str(l.traceId),
    timestamp: typeof l.timestamp === "number" ? l.timestamp : 0,
    contentType: str(l.contentType),
    content: str(l.content),
    tags: keyValues(l.tags),
  };
}

function required(value: string, what: string, operation: string): string {
  if (typeof value !== "string" || value.trim() === "")
    throw new ValidationError(`${operation}: ${what} is required`);
  return value;
}

function paging(p: Paging | undefined, operation: string): { pageNum: number; pageSize: number } {
  const pageNum = p?.pageNum ?? 1;
  const pageSize = p?.pageSize ?? 20;
  if (!Number.isInteger(pageNum) || pageNum < 1)
    throw new ValidationError(`${operation}: pageNum must be a positive integer`);
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 10_000)
    throw new ValidationError(`${operation}: pageSize must be 1..10000`);
  return { pageNum, pageSize };
}

function tagList(tags: Record<string, string> | undefined): KeyValue[] | undefined {
  const entries = Object.entries(tags ?? {});
  return entries.length === 0 ? undefined : entries.map(([key, value]) => ({ key, value }));
}

function scopedIds(
  opts: { service?: string; instance?: string; endpoint?: string },
  operation: string,
): Record<string, string> {
  const out: Record<string, string> = {};
  if ((opts.instance !== undefined || opts.endpoint !== undefined) && opts.service === undefined)
    throw new ValidationError(`${operation}: instance and endpoint need service`);
  if (opts.service !== undefined)
    out.serviceId = serviceId(required(opts.service, "service", operation));
  if (opts.service !== undefined && opts.instance !== undefined)
    out.serviceInstanceId = instanceId(opts.service, opts.instance);
  if (opts.service !== undefined && opts.endpoint !== undefined)
    out.endpointId = endpointId(opts.service, opts.endpoint);
  return out;
}

function containsAll(text: string, words: readonly string[]): boolean {
  const lower = text.toLowerCase();
  return words.every((w) => lower.includes(w.toLowerCase()));
}

function containsNone(text: string, words: readonly string[]): boolean {
  const lower = text.toLowerCase();
  return words.every((w) => !lower.includes(w.toLowerCase()));
}

/**
 * The SkyWalking GraphQL query API (`POST /graphql` on port 12800). Obtain it from
 * `SkyWalkingClient.query`. Services, instances and endpoints are named by their names; the
 * SDK derives the ids (`base64(name).1`).
 */
export class SkyWalkingQueryClient {
  readonly #api: SkyWalkingApi;

  /** @internal Use `SkyWalkingClient.query`. */
  constructor(api: SkyWalkingApi) {
    this.#api = api;
  }

  #signal(opts: CallOptions): AbortSignal | undefined {
    return opts.signal;
  }

  /**
   * Raw GraphQL: returns `{data, errors}` (partial data possible). HTTP 400 (syntax/validation
   * errors) throws `ValidationError`.
   */
  graphql<T = Record<string, unknown>>(
    query: string,
    variables: Record<string, unknown> = {},
    opts: CallOptions = {},
  ): Promise<GraphQLResponse<T>> {
    return this.#api.graphql<T>(query, variables, { operation: "graphql", ...opts });
  }

  /** OAP clock and timezone: `getTimeInfo`. */
  timeInfo(opts: CallOptions = {}): Promise<{ timezone: string; currentTimestamp: number }> {
    return this.#api.timeInfo(this.#signal(opts));
  }

  /** Server features (`hasQueryTracesV2Support`, `supportQueryLogsByKeywords`), cached. */
  capabilities(opts: CallOptions = {}): Promise<SkyWalkingCapabilities> {
    return this.#api.capabilities(this.#signal(opts));
  }

  /**
   * Services: `listServices(layer)`. `layer` `null`/omitted = all layers; an unknown layer → `[]`.
   * OAP has no time filter for services, so `range` is accepted for API symmetry and ignored.
   * Services that only sent OTLP **traces** are not listed (use `client.traces.listServices()`).
   */
  async listServices(
    layer: string | null = null,
    _range?: TimeRange,
    opts: CallOptions = {},
  ): Promise<SkyWalkingService[]> {
    const rows = await this.#api.field<unknown>(
      `query($l:String){listServices(layer:$l){${SERVICE_FIELDS}}}`,
      { l: layer },
      "listServices",
      "query.listServices",
      this.#signal(opts),
    );
    return list(rows).map(decodeService);
  }

  /** One service by name: `findService`; `null` when unknown. */
  async findService(name: string, opts: CallOptions = {}): Promise<SkyWalkingService | null> {
    const operation = "query.findService";
    const row = await this.#api.field<unknown>(
      `query($n:String!){findService(serviceName:$n){${SERVICE_FIELDS}}}`,
      { n: required(name, "name", operation) },
      "findService",
      operation,
      this.#signal(opts),
    );
    return isObject(row) ? decodeService(row) : null;
  }

  /**
   * Endpoints of a service: `findEndpoint(keyword, serviceId, limit)`. `keyword` is a tokenised
   * match (`downstream` finds `/native/downstream`, `down` does not). No duration is sent (OAP
   * 10.4 + BanyanDB returns nothing with one).
   */
  async listEndpoints(
    service: string,
    keyword?: string,
    opts: ListEndpointsOptions = {},
  ): Promise<SkyWalkingEndpoint[]> {
    const operation = "query.listEndpoints";
    const limit = opts.limit ?? 100;
    if (!Number.isInteger(limit) || limit < 1)
      throw new ValidationError(`${operation}: limit must be a positive integer`);
    const rows = await this.#api.field<unknown>(
      "query($k:String,$s:ID!,$l:Int!){findEndpoint(keyword:$k,serviceId:$s,limit:$l){id name}}",
      {
        k: keyword === undefined || keyword === "" ? null : keyword,
        s: serviceId(required(service, "service", operation)),
        l: limit,
      },
      "findEndpoint",
      operation,
      this.#signal(opts),
    );
    return list(rows).map((e) => ({ id: str(e.id), name: str(e.name) }));
  }

  /** Instances of a service active in `range` (default last hour): `listInstances`. */
  async listInstances(
    service: string,
    range?: TimeRange,
    opts: CallOptions = {},
  ): Promise<SkyWalkingInstance[]> {
    const operation = "query.listInstances";
    const id = serviceId(required(service, "service", operation));
    const duration = await this.#api.duration(range, operation, this.#signal(opts));
    const rows = await this.#api.field<unknown>(
      "query($s:ID!,$d:Duration!){listInstances(serviceId:$s,duration:$d){id name language instanceUUID attributes{name value}}}",
      { s: id, d: duration },
      "listInstances",
      operation,
      this.#signal(opts),
    );
    return list(rows).map((i) => ({
      id: str(i.id),
      name: str(i.name),
      language: str(i.language),
      instanceUUID: str(i.instanceUUID),
      attributes: list(i.attributes).map((a) => ({ name: str(a.name), value: str(a.value) })),
    }));
  }

  /**
   * Native traces: `queryTraces(condition)` (whole traces). Durations in ms; tags must be
   * searchable keys. The legacy `queryBasicTraces` is not used (unsupported on BanyanDB).
   */
  async queryTraces(opts: QueryTracesOptions = {}): Promise<QueryTracesResult> {
    const operation = "query.queryTraces";
    const condition: Record<string, unknown> = {
      ...scopedIds(opts, operation),
      traceState: opts.traceState ?? "ALL",
      queryOrder: opts.order ?? "BY_START_TIME",
      paging: paging(opts.paging, operation),
    };
    for (const [key, value] of [
      ["minTraceDuration", opts.minDurationMs],
      ["maxTraceDuration", opts.maxDurationMs],
    ] as const) {
      if (value === undefined) continue;
      if (!Number.isFinite(value) || value < 0)
        throw new ValidationError(`${operation}: durations must be non-negative`);
      condition[key] = Math.round(value);
    }
    if (opts.traceId !== undefined)
      condition.traceId = required(opts.traceId, "traceId", operation);
    const tags = tagList(opts.tags);
    if (tags !== undefined) condition.tags = tags;
    if (opts.traceId === undefined || opts.range !== undefined)
      condition.queryDuration = await this.#api.duration(opts.range, operation, opts.signal);
    const res = await this.#api.field<unknown>(
      `query($c:TraceQueryCondition){queryTraces(condition:$c){traces{spans{${NATIVE_SPAN_FIELDS}}} retrievedTimeRange{startTime endTime}}}`,
      { c: condition },
      "queryTraces",
      operation,
      opts.signal,
    );
    const body = isObject(res) ? res : {};
    const traces = list(body.traces)
      .map((t) => nativeTraceFromSpans(t.spans))
      .filter((t): t is NativeTrace => t !== null);
    const out: QueryTracesResult = { traces };
    const range = body.retrievedTimeRange;
    if (isObject(range) && typeof range.startTime === "number" && typeof range.endTime === "number")
      out.retrievedTimeRange = { startTime: range.startTime, endTime: range.endTime };
    return out;
  }

  /** One native trace: `queryTrace(traceId)`; `null` when unknown (OTLP trace ids are never native). */
  async getTrace(traceId: string, opts: CallOptions = {}): Promise<NativeTrace | null> {
    const operation = "query.getTrace";
    const res = await this.#api.field<unknown>(
      `query($id:ID!){queryTrace(traceId:$id){spans{${NATIVE_SPAN_FIELDS}}}}`,
      { id: required(traceId, "traceId", operation) },
      "queryTrace",
      operation,
      this.#signal(opts),
    );
    return nativeTraceFromSpans(isObject(res) ? res.spans : undefined);
  }

  /**
   * Logs: `queryLogs(condition)`. Either a window (default last hour) or `traceId` is sent;
   * see {@link QueryLogsOptions.keywords} for keyword handling on BanyanDB.
   */
  async queryLogs(opts: QueryLogsOptions = {}): Promise<QueryLogsResult> {
    const operation = "query.queryLogs";
    const condition: Record<string, unknown> = {
      ...scopedIds(opts, operation),
      paging: paging(opts.paging, operation),
      queryOrder: opts.order ?? "DES",
    };
    if (opts.traceId !== undefined) {
      const related: Record<string, unknown> = {
        traceId: required(opts.traceId, "traceId", operation),
      };
      if (opts.segmentId !== undefined) related.segmentId = opts.segmentId;
      if (opts.spanId !== undefined) related.spanId = opts.spanId;
      condition.relatedTrace = related;
    }
    const tags = tagList(opts.tags);
    if (tags !== undefined) condition.tags = tags;
    const keywords = opts.keywords ?? [];
    const excluding = opts.excludingKeywords ?? [];
    let serverKeywords = false;
    if (keywords.length > 0 || excluding.length > 0) {
      serverKeywords = (await this.#api.capabilities(opts.signal)).logsByKeywords;
      if (serverKeywords) {
        if (keywords.length > 0) condition.keywordsOfContent = keywords;
        if (excluding.length > 0) condition.excludingKeywordsOfContent = excluding;
      }
    }
    if (opts.traceId === undefined || opts.range !== undefined)
      condition.queryDuration = await this.#api.duration(opts.range, operation, opts.signal);
    const res = await this.#api.field<unknown>(
      `query($c:LogQueryCondition){queryLogs(condition:$c){errorReason logs{${LOG_FIELDS}}}}`,
      { c: condition },
      "queryLogs",
      operation,
      opts.signal,
    );
    const body = isObject(res) ? res : {};
    let logs = list(body.logs).map(decodeNativeLog);
    if (!serverKeywords)
      logs = logs.filter(
        (l) => containsAll(l.content, keywords) && containsNone(l.content, excluding),
      );
    const out: QueryLogsResult = { logs };
    if (typeof body.errorReason === "string" && body.errorReason !== "")
      out.errorReason = body.errorReason;
    return out;
  }

  /** Log tag keys seen in `range` (default last hour): `queryLogTagAutocompleteKeys`. */
  async listLogTagKeys(range?: TimeRange, opts: CallOptions = {}): Promise<string[]> {
    const operation = "query.listLogTagKeys";
    const duration = await this.#api.duration(range, operation, opts.signal);
    const keys = await this.#api.field<unknown>(
      "query($d:Duration!){queryLogTagAutocompleteKeys(duration:$d)}",
      { d: duration },
      "queryLogTagAutocompleteKeys",
      operation,
      opts.signal,
    );
    return (
      Array.isArray(keys) ? keys.filter((k): k is string => typeof k === "string") : []
    ).sort();
  }

  /**
   * Service topology in `range` (default last hour): `getGlobalTopology(duration, layer)`, or
   * `getServiceTopology` when `opts.service` is set. Native traffic only (OTLP spans create no
   * edges); includes the virtual `User` node.
   */
  async topology(range?: TimeRange, opts: TopologyOptions = {}): Promise<Topology> {
    const operation = "query.topology";
    const duration = await this.#api.duration(range, operation, opts.signal);
    const fields =
      "nodes{id name type isReal layers} calls{id source target detectPoints sourceComponents targetComponents}";
    if (opts.service !== undefined) {
      const res = await this.#api.field<unknown>(
        `query($s:ID!,$d:Duration!){getServiceTopology(serviceId:$s,duration:$d){${fields}}}`,
        { s: serviceId(required(opts.service, "service", operation)), d: duration },
        "getServiceTopology",
        operation,
        opts.signal,
      );
      return decodeTopology(res);
    }
    const res = await this.#api.field<unknown>(
      `query($d:Duration!,$l:String){getGlobalTopology(duration:$d,layer:$l){${fields}}}`,
      { d: duration, l: opts.layer ?? null },
      "getGlobalTopology",
      operation,
      opts.signal,
    );
    return decodeTopology(res);
  }
}
