// Service-owned: logging for SkyWalking — the native log exporter (`POST /v3/logs`) and the
// LogQueryClient over GraphQL `queryLogs` with client-side text filtering.

import { type HttpTransport, ValidationError } from "./core/index.js";
import {
  BaseLogQueryClient,
  Severity,
  parseSeverity,
  severityText,
  type AttrValue,
  type Attributes,
  type ExportOptions,
  type ExportResult,
  type LogExporter,
  type LogRecord,
  type LogSearchOptions,
  type LogSearchResult,
} from "./logging/index.js";
import type { NativeLog, QueryLogsOptions, SkyWalkingQueryClient } from "./query.js";
import type { KeyValue } from "./native.js";

/** Service name used when a record has no `service.name` resource attribute. */
export const DEFAULT_LOG_SERVICE_NAME = "unknown_service";

/** Raw page size used while filtering free text client side. */
export const LOG_SCAN_PAGE_SIZE = 1000;

/** Default number of raw pages one `search` call may scan. */
export const DEFAULT_MAX_SCAN_PAGES = 10;

/** One entry of the native `/v3/logs` body (protobuf JSON mapping of `LogData`). */
export interface NativeLogData {
  /** Epoch milliseconds. */
  timestamp: number;
  service: string;
  serviceInstance?: string;
  endpoint?: string;
  body: { text: { text: string } };
  traceContext?: { traceId: string };
  tags: { data: KeyValue[] };
  layer: string;
}

const HEX32 = /^[0-9a-f]{32}$/;
const HEX16 = /^[0-9a-f]{16}$/;

/** Converts an attribute value to SkyWalking's string tag form (JSON for arrays/objects). */
export function tagText(value: AttrValue): string {
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  return JSON.stringify(value);
}

function resourceText(resource: Attributes, key: string): string | undefined {
  const v = resource[key];
  return v === undefined ? undefined : tagText(v);
}

/**
 * Maps a SPEC {@link LogRecord} to native `LogData`: `resource["service.name"]` → `service`,
 * `service.instance.id` → `serviceInstance`, body → `body.text.text`, `severityText` → tag
 * `level`, attributes (stringified, nested values as JSON) → tags, `traceId` →
 * `traceContext.traceId` (enables `relatedTrace` queries), `spanId` → tag `span_id`.
 */
export function recordToLogData(
  record: LogRecord,
  defaultService = DEFAULT_LOG_SERVICE_NAME,
): NativeLogData {
  const tags: KeyValue[] = [
    { key: "level", value: record.severityText || severityText(record.severity) },
  ];
  for (const [key, value] of Object.entries(record.attributes)) {
    if (key === "level") continue;
    tags.push({ key, value: tagText(value) });
  }
  if (record.spanId !== undefined && record.spanId !== "")
    tags.push({ key: "span_id", value: record.spanId });
  const out: NativeLogData = {
    timestamp: record.timestamp.getTime(),
    service: resourceText(record.resource, "service.name") ?? defaultService,
    body: { text: { text: record.body } },
    tags: { data: tags },
    layer: "GENERAL",
  };
  const instance = resourceText(record.resource, "service.instance.id");
  if (instance !== undefined) out.serviceInstance = instance;
  if (record.traceId !== undefined && record.traceId !== "")
    out.traceContext = { traceId: record.traceId };
  return out;
}

/**
 * Maps a stored log back to a SPEC {@link LogRecord}: tag `level` → severity/severityText
 * (default INFO), `span_id` → `spanId` (16 hex), a 32-hex `traceId` → `traceId` (native trace
 * ids go to attribute `sw.trace_id`), other tags → attributes (strings), service/instance →
 * resource `service.name`/`service.instance.id`, endpoint → resource `sw.endpoint`.
 */
export function logToRecord(log: NativeLog): LogRecord {
  const attributes: Attributes = {};
  let level: string | undefined;
  let spanId: string | undefined;
  for (const { key, value } of log.tags) {
    if (key === "level") level = value;
    else if (key === "span_id" && HEX16.test(value)) spanId = value;
    else attributes[key] = value;
  }
  const resource: Attributes = { "service.name": log.serviceName };
  if (log.serviceInstanceName !== "") resource["service.instance.id"] = log.serviceInstanceName;
  if (log.endpointName !== "") resource["sw.endpoint"] = log.endpointName;
  const severity = level === undefined ? Severity.Info : (parseSeverity(level) ?? Severity.Info);
  const record: LogRecord = {
    timestamp: new Date(log.timestamp),
    severity,
    severityText: level ?? "INFO",
    body: log.content,
    attributes,
    resource,
  };
  const traceId = log.traceId.toLowerCase();
  if (HEX32.test(traceId)) record.traceId = traceId;
  else if (log.traceId !== "") attributes["sw.trace_id"] = log.traceId;
  if (spanId !== undefined) record.spanId = spanId;
  return record;
}

/** Options of {@link SkyWalkingLogExporter}. */
export interface SkyWalkingLogExporterOptions {
  /** Service name for records without `service.name`. Default `unknown_service`. */
  serviceName?: string;
  /** Close the transport on {@link SkyWalkingLogExporter.shutdown} (standalone exporters). */
  ownsTransport?: boolean;
}

/**
 * {@link LogExporter} that posts batches to OAP's native log API (`POST /v3/logs`, JSON array,
 * port 12800). Native logs carry the trace context, register the service and are queryable a
 * few seconds later. The body is sent uncompressed (gzip is not verified on OAP's HTTP receiver).
 */
export class SkyWalkingLogExporter implements LogExporter {
  readonly #transport: HttpTransport;
  readonly #serviceName: string;
  readonly #owns: boolean;
  #shutdown = false;

  constructor(transport: HttpTransport, options: SkyWalkingLogExporterOptions = {}) {
    this.#transport = transport;
    this.#serviceName = options.serviceName ?? DEFAULT_LOG_SERVICE_NAME;
    this.#owns = options.ownsTransport === true;
  }

  /** Posts one batch; transport errors are thrown (the batch processor retries retryable ones). */
  async export(records: LogRecord[], opts: ExportOptions): Promise<ExportResult> {
    if (records.length === 0) return { accepted: 0, rejected: 0 };
    await this.#transport.post("/v3/logs", {
      operation: "logging.export",
      json: records.map((r) => recordToLogData(r, this.#serviceName)),
      responseType: "none",
      ...(opts.signal === undefined ? {} : { signal: opts.signal }),
    });
    return { accepted: records.length, rejected: 0 };
  }

  /** Idempotent; closes the transport when the exporter owns it. */
  async shutdown(): Promise<void> {
    if (this.#shutdown) return;
    this.#shutdown = true;
    if (this.#owns) await this.#transport.close();
  }
}

/** Parsed form of a log search query (see {@link parseLogQuery}). */
export interface ParsedLogQuery {
  service?: string;
  instance?: string;
  endpoint?: string;
  traceId?: string;
  /** Server-side tag filters (allow-listed keys only). */
  tags: Record<string, string>;
  /** Free-text terms, matched client side. */
  text: string[];
}

const TOKEN = /([A-Za-z_][\w.-]*)=(?:"([^"]*)"|(\S+))|"([^"]*)"|(\S+)/g;

/**
 * Parses the log query syntax: space-separated `key=value` terms (`service=`, `instance=`,
 * `endpoint=`, `trace_id=`/`traceId=` map to the GraphQL condition; any other key is a server
 * tag filter and must be in `SW_SEARCHABLE_LOGS_TAG_KEYS`) plus free-text terms (`"quoted
 * phrases"` allowed). Every free-text term must occur (case-insensitive) in the content or in a
 * tag value.
 */
export function parseLogQuery(query: string): ParsedLogQuery {
  const out: ParsedLogQuery = { tags: {}, text: [] };
  for (const m of query.matchAll(TOKEN)) {
    const key = m[1];
    if (key !== undefined) {
      const value = m[2] ?? m[3] ?? "";
      if (key === "service") out.service = value;
      else if (key === "instance") out.instance = value;
      else if (key === "endpoint") out.endpoint = value;
      else if (key === "trace_id" || key === "traceId") out.traceId = value;
      else out.tags[key] = value;
    } else {
      const text = m[4] ?? m[5] ?? "";
      if (text !== "") out.text.push(text);
    }
  }
  return out;
}

/** True when every term occurs in the log content or one of its tag values. */
export function matchesText(log: NativeLog, terms: readonly string[]): boolean {
  if (terms.length === 0) return true;
  const haystack = [log.content, ...log.tags.map((t) => t.value)].join("\n").toLowerCase();
  return terms.every((t) => haystack.includes(t.toLowerCase()));
}

interface Cursor {
  page: number;
  offset: number;
  size: number;
}

function decodeCursor(cursor: string): Cursor {
  const m = /^(\d+):(\d+):(\d+)$/.exec(cursor);
  const c =
    m === null ? undefined : { page: Number(m[1]), offset: Number(m[2]), size: Number(m[3]) };
  if (c === undefined || c.page < 1 || c.size < 1 || c.size > 10_000)
    throw new ValidationError("logging.search: invalid cursor");
  return c;
}

/** Options of {@link SkyWalkingLogQueryClient}. */
export interface SkyWalkingLogQueryOptions {
  /** Raw pages one `search` call may scan while filtering text. Default 10. */
  maxScanPages?: number;
}

/**
 * {@link LogQueryClient} for SkyWalking (GraphQL `queryLogs`). OAP on BanyanDB ignores content
 * keywords, so free-text terms are filtered client side: `search` reads raw pages of 1000 logs
 * (newest first by default) and keeps the matching ones until `limit` matches or
 * `maxScanPages` pages; `nextCursor` continues where it stopped. Without free text the page size
 * is `limit` and nothing is filtered except the exact time window. Default window: the last
 * hour. Obtain it from `SkyWalkingClient.logs`.
 */
export class SkyWalkingLogQueryClient extends BaseLogQueryClient {
  readonly #query: SkyWalkingQueryClient;
  readonly #now: () => number;
  readonly #maxScanPages: number;

  /** @internal Use `SkyWalkingClient.logs`. */
  constructor(
    query: SkyWalkingQueryClient,
    now: () => number = Date.now,
    options: SkyWalkingLogQueryOptions = {},
  ) {
    super();
    this.#query = query;
    this.#now = now;
    this.#maxScanPages = options.maxScanPages ?? DEFAULT_MAX_SCAN_PAGES;
    if (!Number.isInteger(this.#maxScanPages) || this.#maxScanPages < 1)
      throw new ValidationError("logging: maxScanPages must be a positive integer");
  }

  /** Searches logs (see the class description for the query syntax and paging). */
  async search(query: string, opts: LogSearchOptions = {}): Promise<LogSearchResult> {
    const operation = "logging.search";
    const limit = opts.limit ?? 100;
    if (!Number.isInteger(limit) || limit < 1 || limit > 10_000)
      throw new ValidationError(`${operation}: limit must be 1..10000`);
    const parsed = parseLogQuery(query);
    const endMs = opts.end?.getTime() ?? this.#now();
    const startMs = opts.start?.getTime() ?? endMs - 3_600_000;
    if (!Number.isFinite(startMs) || !Number.isFinite(endMs) || startMs > endMs)
      throw new ValidationError(`${operation}: invalid time window`);
    const cursor =
      opts.cursor === undefined
        ? {
            page: 1,
            offset: 0,
            size: parsed.text.length > 0 ? LOG_SCAN_PAGE_SIZE : limit,
          }
        : decodeCursor(opts.cursor);
    const base: QueryLogsOptions = {
      range: { start: new Date(startMs), end: new Date(endMs), step: "MINUTE" },
      order: opts.order === "asc" ? "ASC" : "DES",
      ...(Object.keys(parsed.tags).length === 0 ? {} : { tags: parsed.tags }),
      ...(opts.signal === undefined ? {} : { signal: opts.signal }),
    };
    if (parsed.service !== undefined) base.service = parsed.service;
    if (parsed.instance !== undefined) base.instance = parsed.instance;
    if (parsed.endpoint !== undefined) base.endpoint = parsed.endpoint;
    if (parsed.traceId !== undefined) base.traceId = parsed.traceId;

    const records: LogRecord[] = [];
    let { page, offset } = cursor;
    for (let scanned = 0; scanned < this.#maxScanPages; scanned++) {
      const res = await this.#query.queryLogs({
        ...base,
        paging: { pageNum: page, pageSize: cursor.size },
      });
      const logs = res.logs;
      for (let i = offset; i < logs.length; i++) {
        const log = logs[i] as NativeLog;
        if (log.timestamp < startMs || log.timestamp > endMs || !matchesText(log, parsed.text))
          continue;
        records.push(logToRecord(log));
        if (records.length >= limit) {
          const more = i + 1 < logs.length || logs.length >= cursor.size;
          const next =
            i + 1 < logs.length
              ? `${page}:${i + 1}:${cursor.size}`
              : `${page + 1}:0:${cursor.size}`;
          return more ? { records, nextCursor: next } : { records };
        }
      }
      if (logs.length < cursor.size) return { records };
      page++;
      offset = 0;
    }
    return { records, nextCursor: `${page}:0:${cursor.size}` };
  }

  /** Service names known to OAP (SkyWalking has no streams; the service is the equivalent). */
  override async listStreams(): Promise<string[]> {
    const services = await this.#query.listServices();
    return [...new Set(services.filter((s) => s.normal).map((s) => s.name))].sort();
  }

  /** Log tag keys seen in the last hour (`queryLogTagAutocompleteKeys`). */
  override listLabels(): Promise<string[]> {
    return this.#query.listLogTagKeys();
  }
}
