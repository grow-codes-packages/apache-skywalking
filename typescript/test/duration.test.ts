import { describe, expect, it } from "vitest";
import {
  DEFAULT_RANGE_MS,
  endpointId,
  formatDurationTime,
  instanceId,
  parseTimezone,
  resolveRange,
  serviceId,
  toGraphQLDuration,
} from "../src/index.js";

const T = new Date("2026-11-18T21:05:09Z");

describe("duration and ids", () => {
  it("parses OAP timezones", () => {
    expect(parseTimezone("+0000")).toBe(0);
    expect(parseTimezone("+0800")).toBe(480);
    expect(parseTimezone("-05:30")).toBe(-330);
    expect(parseTimezone("UTC")).toBe(0);
    expect(parseTimezone(undefined)).toBe(0);
  });

  it("formats each step in the OAP timezone", () => {
    expect(formatDurationTime(T, "DAY")).toBe("2026-11-18");
    expect(formatDurationTime(T, "HOUR", 180)).toBe("2026-11-19 00");
    expect(formatDurationTime(T, "MINUTE")).toBe("2026-11-18 2105");
    expect(formatDurationTime(T, "SECOND", -60)).toBe("2026-11-18 200509");
  });

  it("resolves ranges with defaults and validates", () => {
    expect(resolveRange(undefined, T.getTime(), "op")).toEqual({
      startMs: T.getTime() - DEFAULT_RANGE_MS,
      endMs: T.getTime(),
      step: "MINUTE",
    });
    expect(() => resolveRange({ start: new Date(NaN) }, 1, "op")).toThrow(/invalid time range/);
    expect(() => resolveRange({ step: "WEEK" as never }, 1, "op")).toThrow(/invalid step/);
    expect(
      toGraphQLDuration({ startMs: T.getTime(), endMs: T.getTime(), step: "SECOND" }, 0),
    ).toEqual({
      start: "2026-11-18 210509",
      end: "2026-11-18 210509",
      step: "SECOND",
    });
  });

  it("derives SkyWalking ids", () => {
    expect(serviceId("sw-native-a-1fddf66a")).toBe("c3ctbmF0aXZlLWEtMWZkZGY2NmE=.1");
    expect(serviceId("User", false)).toBe("VXNlcg==.0");
    expect(instanceId("sw-native-a-1fddf66a", "inst-a-1fddf66a")).toBe(
      "c3ctbmF0aXZlLWEtMWZkZGY2NmE=.1_aW5zdC1hLTFmZGRmNjZh",
    );
    expect(endpointId("s", "/e", false)).toBe("cw==.0_L2U=");
  });
});
