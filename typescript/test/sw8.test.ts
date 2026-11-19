import {
  ROOT_CONTEXT,
  defaultTextMapGetter,
  defaultTextMapSetter,
  propagation,
} from "@opentelemetry/api";
import { describe, expect, it } from "vitest";
import {
  SW8_CORRELATION_HEADER,
  SW8_HEADER,
  Sw8Propagator,
  decodeSw8,
  decodeSw8Correlation,
  encodeSw8,
  encodeSw8Correlation,
  getSw8Context,
  sw8Attributes,
  withSw8Context,
  type Sw8Context,
} from "../src/index.js";
import { fixture } from "./oap-mock.js";

interface Vectors {
  sw8: { header: string; decoded: Sw8Context; invalid: string[] };
  sw8Correlation: { header: string; decoded: [string, string][] };
}

const vectors = fixture("sw8.vectors") as unknown as Vectors;

describe("sw8 header codec (recorded vectors)", () => {
  it("decodes and re-encodes the valid vector", () => {
    expect(decodeSw8(vectors.sw8.header)).toEqual(vectors.sw8.decoded);
    expect(encodeSw8(vectors.sw8.decoded)).toBe(vectors.sw8.header);
    expect(encodeSw8({ ...vectors.sw8.decoded, sample: false }).startsWith("0-")).toBe(true);
  });

  it("rejects every invalid vector and more", () => {
    for (const bad of [...vectors.sw8.invalid, null, undefined, "1--YQ==-1-YQ==-YQ==-YQ==-YQ=="]) {
      expect(decodeSw8(bad)).toBeNull();
    }
  });

  it("sw8-correlation", () => {
    expect(Object.entries(decodeSw8Correlation(vectors.sw8Correlation.header))).toEqual(
      vectors.sw8Correlation.decoded,
    );
    expect(encodeSw8Correlation(Object.fromEntries(vectors.sw8Correlation.decoded))).toBe(
      vectors.sw8Correlation.header,
    );
    expect(decodeSw8Correlation("a:b:c,!!:YQ==,:YQ==,YQ==")).toEqual({});
    expect(decodeSw8Correlation(undefined)).toEqual({});
  });
});

describe("Sw8Propagator", () => {
  const p = new Sw8Propagator();

  it("extracts sw8 into the context and correlation into baggage", () => {
    const ctx = p.extract(
      ROOT_CONTEXT,
      {
        [SW8_HEADER]: [vectors.sw8.header],
        [SW8_CORRELATION_HEADER]: vectors.sw8Correlation.header,
      },
      defaultTextMapGetter,
    );
    const sw8 = getSw8Context(ctx);
    expect(sw8).toEqual(vectors.sw8.decoded);
    expect(sw8Attributes(sw8 as Sw8Context)).toMatchObject({
      "sw8.parent.trace_id": vectors.sw8.decoded.traceId,
      "sw8.parent.span_id": 1,
      "sw8.peer": "sw-native-b-1fddf66a:8080",
    });
    expect(propagation.getBaggage(ctx)?.getEntry("tenant")?.value).toBe("acme");
    const none = p.extract(ROOT_CONTEXT, { [SW8_HEADER]: "garbage" }, defaultTextMapGetter);
    expect(getSw8Context(none)).toBeUndefined();
    expect(propagation.getBaggage(none)).toBeUndefined();
    expect(p.fields()).toEqual(["sw8", "sw8-correlation"]);
  });

  it("injects only supplied native metadata (never the extracted parent)", () => {
    const carrier: Record<string, string> = {};
    const extracted = p.extract(ROOT_CONTEXT, { sw8: vectors.sw8.header }, defaultTextMapGetter);
    p.inject(extracted, carrier, defaultTextMapSetter);
    expect(carrier).toEqual({});
    p.inject(withSw8Context(ROOT_CONTEXT, vectors.sw8.decoded), carrier, defaultTextMapSetter);
    expect(carrier).toEqual({ sw8: vectors.sw8.header });
    const withCorrelation: Record<string, string> = {};
    p.inject(
      withSw8Context(ROOT_CONTEXT, { ...vectors.sw8.decoded, correlation: { tenant: "acme" } }),
      withCorrelation,
      defaultTextMapSetter,
    );
    expect(withCorrelation[SW8_CORRELATION_HEADER]).toBe("dGVuYW50:YWNtZQ==");
    const empty: Record<string, string> = {};
    p.inject(
      withSw8Context(ROOT_CONTEXT, { ...vectors.sw8.decoded, correlation: {} }),
      empty,
      defaultTextMapSetter,
    );
    expect(Object.keys(empty)).toEqual(["sw8"]);
  });
});
