// Service-owned: the native SkyWalking query model (GraphQL) and its conversion into the SPEC §5
// trace model.

import {
  sortSpans,
  type DependencyLink,
  type Span,
  type SpanAttributes,
  type SpanKindName,
  type Trace,
} from "./tracing/index.js";

/** A `{key, value}` pair (span tags, log tags, log data). */
export interface KeyValue {
  key: string;
  value: string;
}

/** Cross-segment reference of a native span. */
export interface NativeSpanRef {
  traceId: string;
  parentSegmentId: string;
  parentSpanId: number;
  /** `CROSS_PROCESS` or `CROSS_THREAD`. */
  type: string;
}

/** A log entry recorded on a native span. */
export interface NativeSpanLog {
  /** Epoch milliseconds. */
  time: number;
  data: KeyValue[];
}

/** A span as stored by SkyWalking (agents, `/v3/segments`); identity = `(segmentId, spanId)`. */
export interface NativeSpan {
  /** Opaque native trace id (e.g. `<hex>.<n>.<ms>`), or a hex id from an OTel context. */
  traceId: string;
  segmentId: string;
  spanId: number;
  /** Parent within the segment; `-1` = segment root (parent in `refs`). */
  parentSpanId: number;
  refs: NativeSpanRef[];
  serviceCode: string;
  serviceInstanceName: string;
  /** Epoch milliseconds. */
  startTime: number;
  /** Epoch milliseconds. */
  endTime: number;
  endpointName: string;
  /** `Entry`, `Exit` or `Local`. */
  type: string;
  peer: string;
  component: string;
  isError: boolean;
  layer: string;
  tags: KeyValue[];
  logs: NativeSpanLog[];
}

/** A native trace: every segment's spans. */
export interface NativeTrace {
  traceId: string;
  spans: NativeSpan[];
}

/** A node of the service topology. */
export interface TopologyNode {
  id: string;
  name: string;
  type: string;
  isReal: boolean;
  layers: string[];
}

/** An edge of the service topology. */
export interface TopologyCall {
  id: string;
  source: string;
  target: string;
  detectPoints: string[];
  sourceComponents: string[];
  targetComponents: string[];
}

/** `getGlobalTopology` / `getServiceTopology` result. */
export interface Topology {
  nodes: TopologyNode[];
  calls: TopologyCall[];
}

/** GraphQL selection used for native spans. */
export const NATIVE_SPAN_FIELDS =
  "traceId segmentId spanId parentSpanId refs{traceId parentSegmentId parentSpanId type} " +
  "serviceCode serviceInstanceName startTime endTime endpointName type peer component isError " +
  "layer tags{key value} logs{time data{key value}}";

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function str(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function num(value: unknown, fallback = 0): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((v): v is string => typeof v === "string") : [];
}

/** Decodes a `[{key, value}]` list (non-string values are stringified, `null` → `""`). */
export function keyValues(value: unknown): KeyValue[] {
  if (!Array.isArray(value)) return [];
  return value.filter(isObject).map((kv) => ({
    key: str(kv.key),
    value:
      typeof kv.value === "string"
        ? kv.value
        : kv.value === null || kv.value === undefined
          ? ""
          : JSON.stringify(kv.value),
  }));
}

/** Decodes one native span from a GraphQL response (missing fields get neutral defaults). */
export function decodeNativeSpan(raw: unknown): NativeSpan {
  const s = isObject(raw) ? raw : {};
  return {
    traceId: str(s.traceId),
    segmentId: str(s.segmentId),
    spanId: num(s.spanId),
    parentSpanId: num(s.parentSpanId, -1),
    refs: (Array.isArray(s.refs) ? s.refs : []).filter(isObject).map((r) => ({
      traceId: str(r.traceId),
      parentSegmentId: str(r.parentSegmentId),
      parentSpanId: num(r.parentSpanId),
      type: str(r.type),
    })),
    serviceCode: str(s.serviceCode),
    serviceInstanceName: str(s.serviceInstanceName),
    startTime: num(s.startTime),
    endTime: num(s.endTime),
    endpointName: str(s.endpointName),
    type: str(s.type),
    peer: str(s.peer),
    component: str(s.component),
    isError: s.isError === true,
    layer: str(s.layer),
    tags: keyValues(s.tags),
    logs: (Array.isArray(s.logs) ? s.logs : [])
      .filter(isObject)
      .map((l) => ({ time: num(l.time), data: keyValues(l.data) })),
  };
}

/** Decodes a list of native spans into a {@link NativeTrace}; `null` when empty. */
export function nativeTraceFromSpans(raw: unknown): NativeTrace | null {
  const spans = (Array.isArray(raw) ? raw : []).map(decodeNativeSpan);
  const first = spans[0];
  return first === undefined ? null : { traceId: first.traceId, spans };
}

/** Decodes a topology result. */
export function decodeTopology(raw: unknown): Topology {
  const t = isObject(raw) ? raw : {};
  return {
    nodes: (Array.isArray(t.nodes) ? t.nodes : []).filter(isObject).map((n) => ({
      id: str(n.id),
      name: str(n.name),
      type: str(n.type),
      isReal: n.isReal === true,
      layers: strings(n.layers),
    })),
    calls: (Array.isArray(t.calls) ? t.calls : []).filter(isObject).map((c) => ({
      id: str(c.id),
      source: str(c.source),
      target: str(c.target),
      detectPoints: strings(c.detectPoints),
      sourceComponents: strings(c.sourceComponents),
      targetComponents: strings(c.targetComponents),
    })),
  };
}

const KINDS: Record<string, SpanKindName> = {
  Entry: "server",
  Exit: "client",
  Local: "internal",
};

/** Composite SPEC span id of a native span: `<segmentId>-<spanId>`. */
export function nativeSpanKey(segmentId: string, spanId: number): string {
  return `${segmentId}-${spanId}`;
}

/**
 * Converts a native span into the SPEC §5 model. Ids are kept verbatim (DECISIONS: native trace
 * ids are not hex): `spanId = "<segmentId>-<spanId>"`; the parent is the same segment's
 * `parentSpanId` when ≥ 0, else `refs[0]` (`"<parentSegmentId>-<parentSpanId>"`); further refs
 * become links. `kind`: Entry → server, Exit → client, Local → internal. Times ms → µs. Status
 * `error` when `isError`. Attributes = tags plus `sw.component`, `sw.layer`, `sw.peer`,
 * `sw.service_instance` (when set). Span logs become events (name = the `event` data value, else
 * `log`).
 */
export function nativeSpanToSpan(span: NativeSpan): Span {
  const attributes: SpanAttributes = {};
  for (const kv of span.tags) attributes[kv.key] = kv.value;
  if (span.component !== "") attributes["sw.component"] = span.component;
  if (span.layer !== "") attributes["sw.layer"] = span.layer;
  if (span.peer !== "") attributes["sw.peer"] = span.peer;
  if (span.serviceInstanceName !== "") attributes["sw.service_instance"] = span.serviceInstanceName;
  const start = span.startTime * 1000;
  const end = Math.max(span.endTime, span.startTime) * 1000;
  const out: Span = {
    traceId: span.traceId,
    spanId: nativeSpanKey(span.segmentId, span.spanId),
    name: span.endpointName,
    kind: KINDS[span.type] ?? "unspecified",
    serviceName: span.serviceCode,
    startMicros: start,
    endMicros: end,
    durationMicros: end - start,
    status: span.isError ? { code: "error" } : { code: "unset" },
    attributes,
    events: span.logs.map((log) => {
      const data: SpanAttributes = {};
      for (const kv of log.data) data[kv.key] = kv.value;
      const name = log.data.find((kv) => kv.key === "event")?.value ?? "log";
      return { name, timeMicros: log.time * 1000, attributes: data };
    }),
    links: span.refs.slice(span.parentSpanId >= 0 ? 0 : 1).map((r) => ({
      traceId: r.traceId,
      spanId: nativeSpanKey(r.parentSegmentId, r.parentSpanId),
      attributes: { "sw.ref_type": r.type },
    })),
  };
  if (span.parentSpanId >= 0) out.parentSpanId = nativeSpanKey(span.segmentId, span.parentSpanId);
  else {
    const ref = span.refs[0];
    if (ref !== undefined) out.parentSpanId = nativeSpanKey(ref.parentSegmentId, ref.parentSpanId);
  }
  return out;
}

/** Converts a native trace into a SPEC {@link Trace} (spans sorted); `null` when empty. */
export function nativeTraceToTrace(trace: NativeTrace | null): Trace | null {
  if (trace === null || trace.spans.length === 0) return null;
  return { traceId: trace.traceId, spans: sortSpans(trace.spans.map(nativeSpanToSpan)) };
}

/**
 * Topology → dependency links (`parent` = source node name, `child` = target node name,
 * `callCount` = 0 because the topology type carries no call counts). Sorted by parent, child.
 */
export function topologyToDependencies(topology: Topology): DependencyLink[] {
  const names = new Map(topology.nodes.map((n) => [n.id, n.name]));
  return topology.calls
    .map((c) => ({
      parent: names.get(c.source) ?? c.source,
      child: names.get(c.target) ?? c.target,
      callCount: 0,
    }))
    .sort((a, b) =>
      a.parent !== b.parent ? (a.parent < b.parent ? -1 : 1) : a.child < b.child ? -1 : 1,
    );
}
