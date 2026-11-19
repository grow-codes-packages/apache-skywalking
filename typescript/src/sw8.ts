// Service-owned: SkyWalking cross-process propagation headers `sw8` (v3) and `sw8-correlation`.

import {
  createContextKey,
  propagation,
  type Context,
  type TextMapGetter,
  type TextMapPropagator,
  type TextMapSetter,
} from "@opentelemetry/api";
import type { SpanAttributes } from "./tracing/index.js";

/** Header name of the SkyWalking v3 trace context. */
export const SW8_HEADER = "sw8";
/** Header name of the SkyWalking correlation context. */
export const SW8_CORRELATION_HEADER = "sw8-correlation";

/** Decoded `sw8` header (SkyWalking cross-process propagation v3). */
export interface Sw8Context {
  /** Whether the upstream sampled the trace. */
  sample: boolean;
  /** Native trace id (opaque string, e.g. `<hex>.<n>.<ms>`). */
  traceId: string;
  /** Segment id of the parent (calling) segment. */
  parentTraceSegmentId: string;
  /** Span id of the exit span in the parent segment. */
  parentSpanId: number;
  parentService: string;
  parentServiceInstance: string;
  /** Endpoint (operation) of the parent's entry span. */
  parentEndpoint: string;
  /** Address the caller used to reach this service (`host:port`). */
  networkAddressUsedAtPeer: string;
}

/** {@link Sw8Context} plus an optional correlation map, for {@link withSw8Context}. */
export interface Sw8InjectContext extends Sw8Context {
  /** Written as `sw8-correlation`. */
  correlation?: Record<string, string>;
}

const B64 = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;

function enc(text: string): string {
  return Buffer.from(text, "utf8").toString("base64");
}

function dec(text: string): string | undefined {
  return B64.test(text) ? Buffer.from(text, "base64").toString("utf8") : undefined;
}

/** Encodes an `sw8` header value: `sample-b64(traceId)-b64(segment)-spanId-b64(service)-b64(instance)-b64(endpoint)-b64(peer)`. */
export function encodeSw8(ctx: Sw8Context): string {
  return [
    ctx.sample ? "1" : "0",
    enc(ctx.traceId),
    enc(ctx.parentTraceSegmentId),
    String(Math.trunc(ctx.parentSpanId)),
    enc(ctx.parentService),
    enc(ctx.parentServiceInstance),
    enc(ctx.parentEndpoint),
    enc(ctx.networkAddressUsedAtPeer),
  ].join("-");
}

/** Decodes an `sw8` header value; `null` when it is malformed (wrong field count, bad Base64, …). */
export function decodeSw8(header: string | null | undefined): Sw8Context | null {
  const parts = (header ?? "").trim().split("-");
  if (parts.length !== 8) return null;
  const [sample, traceId, segment, spanId, service, instance, endpoint, peer] = parts as [
    string,
    string,
    string,
    string,
    string,
    string,
    string,
    string,
  ];
  if (sample !== "0" && sample !== "1") return null;
  if (!/^-?\d+$/.test(spanId)) return null;
  const decoded = [traceId, segment, service, instance, endpoint, peer].map(dec);
  if (decoded.some((d) => d === undefined)) return null;
  const [t, s, sv, inst, ep, pr] = decoded as string[];
  if (t === "" || s === "") return null;
  return {
    sample: sample === "1",
    traceId: t as string,
    parentTraceSegmentId: s as string,
    parentSpanId: Number(spanId),
    parentService: sv as string,
    parentServiceInstance: inst as string,
    parentEndpoint: ep as string,
    networkAddressUsedAtPeer: pr as string,
  };
}

/** Encodes `sw8-correlation`: `b64(key):b64(value),…` in insertion order. */
export function encodeSw8Correlation(entries: Record<string, string>): string {
  return Object.entries(entries)
    .map(([k, v]) => `${enc(k)}:${enc(v)}`)
    .join(",");
}

/** Decodes `sw8-correlation`; malformed entries are skipped. */
export function decodeSw8Correlation(header: string | null | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  for (const part of (header ?? "").split(",")) {
    const [k, v, extra] = part.trim().split(":");
    if (k === undefined || v === undefined || extra !== undefined) continue;
    const key = dec(k);
    const value = dec(v);
    if (key === undefined || value === undefined || key === "") continue;
    out[key] = value;
  }
  return out;
}

const EXTRACTED_KEY = createContextKey("grow-codes skywalking sw8 extracted");
const INJECT_KEY = createContextKey("grow-codes skywalking sw8 inject");

/** The `sw8` context extracted from an incoming request, if any. */
export function getSw8Context(ctx: Context): Sw8Context | undefined {
  return ctx.getValue(EXTRACTED_KEY) as Sw8Context | undefined;
}

/**
 * Returns a context whose outgoing requests carry this native metadata as `sw8` (and
 * `sw8-correlation`). The SDK cannot derive native metadata from an OpenTelemetry span; supply
 * it only when you bridge to SkyWalking agents.
 */
export function withSw8Context(ctx: Context, sw8: Sw8InjectContext): Context {
  return ctx.setValue(INJECT_KEY, sw8);
}

/** Span attributes describing an extracted `sw8` parent (`sw8.parent.*`, `sw8.peer`). */
export function sw8Attributes(sw8: Sw8Context): SpanAttributes {
  return {
    "sw8.parent.trace_id": sw8.traceId,
    "sw8.parent.segment_id": sw8.parentTraceSegmentId,
    "sw8.parent.span_id": sw8.parentSpanId,
    "sw8.parent.service": sw8.parentService,
    "sw8.parent.service_instance": sw8.parentServiceInstance,
    "sw8.parent.endpoint": sw8.parentEndpoint,
    "sw8.peer": sw8.networkAddressUsedAtPeer,
  };
}

function first(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

/**
 * OpenTelemetry propagator for SkyWalking headers (propagator name `sw8`).
 *
 * - **extract** (default use): a valid `sw8` header is stored in the context
 *   ({@link getSw8Context}, {@link sw8Attributes}); its trace id is not hex, so the W3C context
 *   stays the OpenTelemetry trace. `sw8-correlation` entries are added to the baggage.
 * - **inject**: only when native metadata was set with {@link withSw8Context}; otherwise
 *   nothing is written (an OpenTelemetry span has no segment id or native span id).
 */
export class Sw8Propagator implements TextMapPropagator {
  /** Writes `sw8`/`sw8-correlation` when the context carries {@link withSw8Context} metadata. */
  inject(ctx: Context, carrier: unknown, setter: TextMapSetter): void {
    const sw8 = ctx.getValue(INJECT_KEY) as Sw8InjectContext | undefined;
    if (sw8 === undefined) return;
    setter.set(carrier, SW8_HEADER, encodeSw8(sw8));
    if (sw8.correlation !== undefined && Object.keys(sw8.correlation).length > 0)
      setter.set(carrier, SW8_CORRELATION_HEADER, encodeSw8Correlation(sw8.correlation));
  }

  /** Reads `sw8` into the context and `sw8-correlation` into the baggage. */
  extract(ctx: Context, carrier: unknown, getter: TextMapGetter): Context {
    let out = ctx;
    const sw8 = decodeSw8(first(getter.get(carrier, SW8_HEADER)));
    if (sw8 !== null) out = out.setValue(EXTRACTED_KEY, sw8);
    const correlation = decodeSw8Correlation(first(getter.get(carrier, SW8_CORRELATION_HEADER)));
    const entries = Object.entries(correlation);
    if (entries.length > 0) {
      let baggage = propagation.getBaggage(out) ?? propagation.createBaggage();
      for (const [k, v] of entries) baggage = baggage.setEntry(k, { value: v });
      out = propagation.setBaggage(out, baggage);
    }
    return out;
  }

  /** The header names this propagator uses. */
  fields(): string[] {
    return [SW8_HEADER, SW8_CORRELATION_HEADER];
  }
}
