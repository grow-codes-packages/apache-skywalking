// Service-owned: GraphQL `Duration` formatting and SkyWalking id derivation.

import { ValidationError } from "./core/index.js";

/** Granularity of a GraphQL `Duration`. */
export type Step = "DAY" | "HOUR" | "MINUTE" | "SECOND";

/**
 * Time window of a metadata/trace/log query. Defaults: `end` = now, `start` = `end` - 1 h,
 * `step` = `MINUTE`.
 */
export interface TimeRange {
  start?: Date;
  end?: Date;
  step?: Step;
}

/** The GraphQL `Duration` input (times in the OAP timezone, formatted per step). */
export interface GraphQLDuration {
  start: string;
  end: string;
  step: Step;
}

/** Default query window: one hour. */
export const DEFAULT_RANGE_MS = 3_600_000;

const STEPS: readonly Step[] = ["DAY", "HOUR", "MINUTE", "SECOND"];

/**
 * Parses an OAP timezone (`getTimeInfo.timezone`, e.g. `+0000`, `-0530`, `+08:00`) into minutes
 * east of UTC. Unknown text → 0.
 */
export function parseTimezone(tz: string | null | undefined): number {
  const m = /^([+-])(\d{2}):?(\d{2})$/.exec((tz ?? "").trim());
  if (m === null) return 0;
  const minutes = Number(m[2]) * 60 + Number(m[3]);
  return m[1] === "-" ? -minutes : minutes;
}

function pad(n: number, width = 2): string {
  return String(n).padStart(width, "0");
}

/** Formats `date` for a `Duration` field: `DAY yyyy-MM-dd`, `HOUR yyyy-MM-dd HH`, `MINUTE yyyy-MM-dd HHmm`, `SECOND yyyy-MM-dd HHmmss`. */
export function formatDurationTime(date: Date, step: Step, offsetMinutes = 0): string {
  const d = new Date(date.getTime() + offsetMinutes * 60_000);
  const day = `${pad(d.getUTCFullYear(), 4)}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
  switch (step) {
    case "DAY":
      return day;
    case "HOUR":
      return `${day} ${pad(d.getUTCHours())}`;
    case "SECOND":
      return `${day} ${pad(d.getUTCHours())}${pad(d.getUTCMinutes())}${pad(d.getUTCSeconds())}`;
    default:
      return `${day} ${pad(d.getUTCHours())}${pad(d.getUTCMinutes())}`;
  }
}

/** Resolved window in epoch ms. */
export interface ResolvedRange {
  startMs: number;
  endMs: number;
  step: Step;
}

/** Applies the {@link TimeRange} defaults and validates; `ValidationError` on bad input. */
export function resolveRange(
  range: TimeRange | undefined,
  nowMs: number,
  operation: string,
): ResolvedRange {
  const endMs = range?.end?.getTime() ?? nowMs;
  const startMs = range?.start?.getTime() ?? endMs - DEFAULT_RANGE_MS;
  const step = range?.step ?? "MINUTE";
  if (!Number.isFinite(startMs) || !Number.isFinite(endMs))
    throw new ValidationError(`${operation}: invalid time range`);
  if (startMs > endMs) throw new ValidationError(`${operation}: start must not be after end`);
  if (!STEPS.includes(step)) throw new ValidationError(`${operation}: invalid step`);
  return { startMs, endMs, step };
}

/** Builds the GraphQL `Duration` for a window. */
export function toGraphQLDuration(range: ResolvedRange, offsetMinutes: number): GraphQLDuration {
  return {
    start: formatDurationTime(new Date(range.startMs), range.step, offsetMinutes),
    end: formatDurationTime(new Date(range.endMs), range.step, offsetMinutes),
    step: range.step,
  };
}

function b64(text: string): string {
  return Buffer.from(text, "utf8").toString("base64");
}

/**
 * SkyWalking service id: `base64(name) + ".1"` for a real (normal) service, `".0"` for a
 * virtual one.
 */
export function serviceId(name: string, normal = true): string {
  return `${b64(name)}.${normal ? 1 : 0}`;
}

/** Service instance id: `serviceId + "_" + base64(instanceName)`. */
export function instanceId(service: string, instance: string, normal = true): string {
  return `${serviceId(service, normal)}_${b64(instance)}`;
}

/** Endpoint id: `serviceId + "_" + base64(endpointName)`. */
export function endpointId(service: string, endpoint: string, normal = true): string {
  return `${serviceId(service, normal)}_${b64(endpoint)}`;
}
