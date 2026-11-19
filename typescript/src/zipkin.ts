// Service-owned: OAP's Zipkin v2 span model (how OTLP spans are stored and read back) and its
// conversion into the SPEC §5 trace model.

import { ValidationError } from "./core/index.js";
import {
  normalizeTraceId,
  sortSpans,
  summarizeTrace,
  type Span,
  type SpanAttributes,
  type SpanEvent,
  type SpanKindName,
  type Trace,
  type TraceSummary,
} from "./tracing/index.js";

/** Endpoint of a Zipkin span. */
export interface ZipkinEndpoint {
  serviceName?: string;
  ipv4?: string;
  ipv6?: string;
  port?: number;
}

/** A Zipkin annotation (OAP encodes OTLP events as `name|attributesJSON|droppedCount`). */
export interface ZipkinAnnotation {
  /** Epoch microseconds. */
  timestamp: number;
  value: string;
}

/** A span as returned by OAP's Zipkin query API (`:9412/zipkin/api/v2`). */
export interface ZipkinSpan {
  traceId: string;
  id: string;
  parentId?: string;
  kind?: "CLIENT" | "SERVER" | "PRODUCER" | "CONSUMER";
  /** Lower-cased by OAP. */
  name?: string;
  /** Epoch microseconds. */
  timestamp?: number;
  /** Microseconds. */
  duration?: number;
  localEndpoint?: ZipkinEndpoint;
  remoteEndpoint?: ZipkinEndpoint;
  annotations?: ZipkinAnnotation[];
  tags?: Record<string, string>;
}

const HEX_ID = /^(?:[0-9a-f]{16}|[0-9a-f]{32})$/;

/** True for a 16- or 32-character hex id (OTLP/Zipkin), false for native SkyWalking ids. */
export function isHexTraceId(id: string): boolean {
  return HEX_ID.test(id.trim().toLowerCase());
}

/** Lower-cases and validates a Zipkin trace id; `ValidationError` otherwise. */
export function zipkinTraceId(id: string, operation: string): string {
  const lower = typeof id === "string" ? id.trim().toLowerCase() : "";
  if (!HEX_ID.test(lower) || /^0+$/.test(lower))
    throw new ValidationError(`${operation}: trace id must be 16 or 32 hex characters`);
  return lower;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Keeps the well-formed spans of a response body. */
export function zipkinSpansFromBody(body: unknown): ZipkinSpan[] {
  if (!Array.isArray(body)) return [];
  return body.filter(
    (s): s is ZipkinSpan =>
      isObject(s) && typeof s.traceId === "string" && typeof s.id === "string",
  );
}

/** Keeps the non-empty traces of a `[[span…]…]` body. */
export function zipkinTracesFromBody(body: unknown): ZipkinSpan[][] {
  if (!Array.isArray(body)) return [];
  return body.map(zipkinSpansFromBody).filter((t) => t.length > 0);
}

/** Decodes a `string[]` body. */
export function stringsFromBody(body: unknown): string[] {
  return Array.isArray(body) ? body.filter((s): s is string => typeof s === "string") : [];
}

const KIND_NAMES: Record<string, SpanKindName> = {
  CLIENT: "client",
  SERVER: "server",
  PRODUCER: "producer",
  CONSUMER: "consumer",
};

/** Tags OAP adds as bookkeeping; they are mapped to status/kind and dropped from attributes. */
export function isBookkeepingTag(key: string): boolean {
  return key.startsWith("otel.") || key === "error" || key === "w3c.tracestate";
}

/**
 * Decodes an OAP annotation into an event: `name|{"k":"v"}|0` (split on the first and last
 * `|`) → name + attributes; any other value is the event name without attributes.
 */
export function annotationToEvent(annotation: ZipkinAnnotation): SpanEvent {
  const value = annotation.value;
  const first = value.indexOf("|");
  const last = value.lastIndexOf("|");
  if (first > 0 && last > first) {
    const json = value.slice(first + 1, last);
    try {
      const parsed = JSON.parse(json === "" ? "{}" : json) as unknown;
      if (isObject(parsed))
        return {
          name: value.slice(0, first),
          timeMicros: annotation.timestamp,
          attributes: parsed as SpanAttributes,
        };
    } catch {
      // not the OAP form: the whole value is the name
    }
  }
  return { name: value, timeMicros: annotation.timestamp, attributes: {} };
}

/**
 * Converts a stored Zipkin span into the SPEC §5 model: ids lower-case (trace id padded to 32),
 * `kind` lower-case (absent → `internal`), `serviceName` = `localEndpoint.serviceName`, status
 * from `otel.status_code` (`STATUS_CODE_ERROR` → error with `otel.status_description`,
 * `STATUS_CODE_OK` → ok; an `error` tag alone → error), attributes = tags without the `otel.*`,
 * `error` and `w3c.tracestate` bookkeeping keys (values are strings: OAP stores tags as text),
 * annotations → events. Span names are lower-cased by OAP on ingest.
 */
export function zipkinSpanToSpan(span: ZipkinSpan): Span {
  const tags = span.tags ?? {};
  const start = span.timestamp ?? 0;
  const duration = span.duration ?? 0;
  const code = (tags["otel.status_code"] ?? "").toUpperCase();
  let status: Span["status"] = { code: "unset" };
  if (code === "STATUS_CODE_ERROR" || code === "ERROR") {
    const message = tags["otel.status_description"];
    status =
      message === undefined || message === "" ? { code: "error" } : { code: "error", message };
  } else if (code === "STATUS_CODE_OK" || code === "OK") status = { code: "ok" };
  else if (tags.error !== undefined) status = { code: "error" };
  const attributes: SpanAttributes = {};
  for (const [k, v] of Object.entries(tags)) if (!isBookkeepingTag(k)) attributes[k] = v;
  const out: Span = {
    traceId: normalizeTraceId(span.traceId),
    spanId: span.id.toLowerCase(),
    name: span.name ?? "",
    kind: span.kind === undefined ? "internal" : (KIND_NAMES[span.kind] ?? "unspecified"),
    serviceName: span.localEndpoint?.serviceName ?? "",
    startMicros: start,
    endMicros: start + duration,
    durationMicros: duration,
    status,
    attributes,
    events: (span.annotations ?? []).map(annotationToEvent),
    links: [],
  };
  if (span.parentId !== undefined && span.parentId !== "")
    out.parentSpanId = span.parentId.toLowerCase();
  return out;
}

/** Converts one Zipkin trace into a SPEC {@link Trace}; `null` when empty. */
export function zipkinTraceToTrace(spans: readonly ZipkinSpan[]): Trace | null {
  const first = spans[0];
  if (first === undefined) return null;
  return {
    traceId: normalizeTraceId(first.traceId),
    spans: sortSpans(spans.map(zipkinSpanToSpan)),
  };
}

/** Summarises a SPEC trace; root falls back to the earliest span. */
export function summarize(trace: Trace): TraceSummary {
  const summary = summarizeTrace(trace);
  const earliest = trace.spans[0];
  if (summary.rootName === undefined && earliest !== undefined) {
    summary.rootName = earliest.name;
    summary.rootServiceName = earliest.serviceName;
  }
  return summary;
}
