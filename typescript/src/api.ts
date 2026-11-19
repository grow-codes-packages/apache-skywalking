// Service-owned: the shared OAP access layer (GraphQL, Zipkin query, native REST) used by every
// module of the client.

import {
  type HttpTransport,
  type QueryValue,
  type SkyWalkingError,
  MAX_VENDOR_TEXT,
  UnsupportedError,
  ValidationError,
  redactSecretsInText,
} from "./core/index.js";
import type { SkyWalkingConfig } from "./config.js";
import {
  type GraphQLDuration,
  type TimeRange,
  parseTimezone,
  resolveRange,
  toGraphQLDuration,
} from "./duration.js";

/** One entry of a GraphQL `errors` array. */
export interface GraphQLErrorInfo {
  message: string;
  /** Field path the error belongs to (resolver errors). */
  path?: (string | number)[];
  /** graphql-java classification (`ValidationError`, `DataFetchingException`, …). */
  classification?: string;
}

/** Result of a raw GraphQL call: partial `data` is possible next to `errors`. */
export interface GraphQLResponse<T = Record<string, unknown>> {
  data: T | null;
  errors: GraphQLErrorInfo[];
}

/** Per-call options. */
export interface CallOptions {
  /** Cancels the call (`CancelledError`). */
  signal?: AbortSignal;
}

/** Server features probed once per client (GraphQL `hasQueryTracesV2Support`, `supportQueryLogsByKeywords`). */
export interface SkyWalkingCapabilities {
  /** `queryTraces` (V2 trace query) is available. */
  queryTracesV2: boolean;
  /** The log store honours `keywordsOfContent` (false on BanyanDB). */
  logsByKeywords: boolean;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Decodes the `errors` array of a GraphQL response. */
export function graphqlErrors(body: unknown): GraphQLErrorInfo[] {
  if (!isObject(body) || !Array.isArray(body.errors)) return [];
  return body.errors.filter(isObject).map((e) => {
    const out: GraphQLErrorInfo = { message: typeof e.message === "string" ? e.message : "" };
    if (Array.isArray(e.path)) out.path = e.path as (string | number)[];
    const ext = e.extensions;
    if (isObject(ext) && typeof ext.classification === "string")
      out.classification = ext.classification;
    return out;
  });
}

/**
 * Maps GraphQL errors to an SDK error: `UnsupportedOperationException` → `UnsupportedError`,
 * everything else → `ValidationError` (bad input caught by the resolver). The message is
 * redacted and truncated to 300 characters.
 */
export function graphqlError(
  operation: string,
  errors: readonly GraphQLErrorInfo[],
  redact: (text: string) => string = (t) => t,
): SkyWalkingError {
  const raw = errors.map((e) => e.message).find((m) => m !== "") ?? "GraphQL error";
  let text = redactSecretsInText(redact(raw.replace(/\s+/g, " ").trim()));
  if (text.length > MAX_VENDOR_TEXT) text = text.slice(0, MAX_VENDOR_TEXT - 1) + "…";
  if (raw.includes("UnsupportedOperationException"))
    return new UnsupportedError(`${operation}: ${text}`);
  return new ValidationError(`${operation}: ${text}`);
}

/** Shared OAP access for the client's modules. @internal */
export class SkyWalkingApi {
  readonly transport: HttpTransport;
  readonly config: SkyWalkingConfig;
  readonly now: () => number;
  #offset: Promise<number> | undefined;
  #capabilities: Promise<SkyWalkingCapabilities> | undefined;

  constructor(transport: HttpTransport, config: SkyWalkingConfig, now: () => number = Date.now) {
    this.transport = transport;
    this.config = config;
    this.now = now;
  }

  /**
   * Raw GraphQL call: `POST <url>/graphql`. HTTP errors throw (400 → `ValidationError`); resolver
   * errors come back in `errors` with HTTP 200. Queries are retried like idempotent requests,
   * mutations are not.
   */
  async graphql<T = Record<string, unknown>>(
    query: string,
    variables: Record<string, unknown> = {},
    opts: CallOptions & { operation?: string } = {},
  ): Promise<GraphQLResponse<T>> {
    if (typeof query !== "string" || query.trim() === "")
      throw new ValidationError(`${opts.operation ?? "graphql"}: query is required`);
    const res = await this.transport.post(this.config.graphqlPath, {
      operation: opts.operation ?? "graphql",
      json: { query, variables },
      idempotent: !/^\s*mutation\b/i.test(query),
      ...(opts.signal === undefined ? {} : { signal: opts.signal }),
    });
    const body = res.data;
    const data = isObject(body) && isObject(body.data) ? (body.data as T) : null;
    return { data, errors: graphqlErrors(body) };
  }

  /** Runs a query and returns `data[field]`; throws on any error. */
  async field<T>(
    query: string,
    variables: Record<string, unknown>,
    field: string,
    operation: string,
    signal?: AbortSignal,
  ): Promise<T> {
    const res = await this.graphql(query, variables, {
      operation,
      ...(signal === undefined ? {} : { signal }),
    });
    if (res.errors.length > 0)
      throw graphqlError(operation, res.errors, (t) => this.transport.redact(t));
    return (res.data?.[field] ?? null) as T;
  }

  /** OAP timezone offset in minutes (from `getTimeInfo`, fetched once and cached). */
  offsetMinutes(signal?: AbortSignal): Promise<number> {
    if (this.#offset === undefined) {
      const p = this.timeInfo(signal).then((t) => parseTimezone(t.timezone));
      this.#offset = p;
      p.catch(() => {
        if (this.#offset === p) this.#offset = undefined;
      });
    }
    return this.#offset;
  }

  /** `getTimeInfo` → `{timezone, currentTimestamp}` (ms). */
  async timeInfo(signal?: AbortSignal): Promise<{ timezone: string; currentTimestamp: number }> {
    const info = await this.field<{ timezone?: unknown; currentTimestamp?: unknown } | null>(
      "{getTimeInfo{timezone currentTimestamp}}",
      {},
      "getTimeInfo",
      "timeInfo",
      signal,
    );
    const timezone = typeof info?.timezone === "string" ? info.timezone : "+0000";
    if (this.#offset === undefined) this.#offset = Promise.resolve(parseTimezone(timezone));
    return {
      timezone,
      currentTimestamp:
        typeof info?.currentTimestamp === "number" ? info.currentTimestamp : this.now(),
    };
  }

  /** GraphQL `Duration` for a window, in the OAP timezone. */
  async duration(
    range: TimeRange | undefined,
    operation: string,
    signal?: AbortSignal,
  ): Promise<GraphQLDuration> {
    const resolved = resolveRange(range, this.now(), operation);
    return toGraphQLDuration(resolved, await this.offsetMinutes(signal));
  }

  /** Server feature probe (cached). */
  capabilities(signal?: AbortSignal): Promise<SkyWalkingCapabilities> {
    if (this.#capabilities === undefined) {
      const p = this.graphql<{
        hasQueryTracesV2Support?: unknown;
        supportQueryLogsByKeywords?: unknown;
      }>(
        "{hasQueryTracesV2Support supportQueryLogsByKeywords}",
        {},
        { operation: "capabilities", ...(signal === undefined ? {} : { signal }) },
      ).then((res) => ({
        queryTracesV2: res.data?.hasQueryTracesV2Support === true,
        logsByKeywords: res.data?.supportQueryLogsByKeywords === true,
      }));
      this.#capabilities = p;
      p.catch(() => {
        if (this.#capabilities === p) this.#capabilities = undefined;
      });
    }
    return this.#capabilities;
  }

  /** GET on the Zipkin query API (`<zipkinUrl>/api/v2/...`). */
  async zipkinGet(
    path: string,
    operation: string,
    query: Record<string, QueryValue>,
    signal?: AbortSignal,
    allowStatus?: readonly number[],
  ): Promise<{ status: number; data: unknown }> {
    const res = await this.transport.get(`${this.config.zipkinUrl}${path}`, {
      operation,
      query,
      ...(allowStatus === undefined ? {} : { allowStatus }),
      ...(signal === undefined ? {} : { signal }),
    });
    return { status: res.status, data: res.data };
  }
}
