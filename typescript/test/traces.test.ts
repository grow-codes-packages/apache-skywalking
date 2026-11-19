import { afterEach, describe, expect, it } from "vitest";
import {
  annotationToEvent,
  decodeNativeSpan,
  decodeTopology,
  isHexTraceId,
  nativeSpanToSpan,
  nativeTraceToTrace,
  stringsFromBody,
  summarize,
  topologyToDependencies,
  zipkinSpanToSpan,
  zipkinTraceId,
  zipkinTraceToTrace,
  zipkinTracesFromBody,
  type TraceBackend,
} from "../src/index.js";
import { clientFor, closeOaps, data, gql, graphqlCalls, reply, startOap } from "./oap-mock.js";
import type { Route } from "./oap-mock.js";

afterEach(closeOaps);

const NOW = Date.parse("2026-11-18T21:15:30Z");
const OTLP_ID = "055ffba8ecc69a64950538fda698a308";
const NATIVE_ID = "1fddf66a1c64db2c1345419627769f1f.1.1795036241775";

async function traces(backend: TraceBackend, ...routes: Route[]) {
  const s = await startOap(...routes);
  const client = clientFor(s, { traceBackend: backend }, { now: () => NOW });
  return { s, client, t: client.traces };
}

function paths(s: Awaited<ReturnType<typeof startOap>>): string[] {
  return s.requests.map((r) => r.url);
}

describe("SkyWalkingTraceQueryClient", () => {
  it("auto getTrace: hex id via Zipkin, mapped to the SPEC model", async () => {
    const { s, t, client } = await traces("auto");
    const trace = await t.getTrace(OTLP_ID.toUpperCase());
    expect(trace?.traceId).toBe(OTLP_ID);
    const root = trace?.spans[0];
    expect(root).toMatchObject({
      spanId: "e891943fdadfebf9",
      name: "get /checkout",
      kind: "server",
      serviceName: "sw-otlp-1fddf66a",
      startMicros: 1795036241725000,
      durationMicros: 50000,
      endMicros: 1795036241775000,
      status: { code: "ok" },
    });
    expect(root?.attributes).toMatchObject({ retries: "2", run_id: "1fddf66a" });
    expect(Object.keys(root?.attributes ?? {}).some((k) => k.startsWith("otel."))).toBe(false);
    expect(root?.attributes["w3c.tracestate"]).toBeUndefined();
    expect(root?.events).toEqual([
      { name: "cache.miss", timeMicros: 1795036241735000, attributes: { k: "v" } },
    ]);
    expect(trace?.spans[1]?.parentSpanId).toBe("e891943fdadfebf9");
    expect(paths(s)).toEqual([`/zipkin/api/v2/trace/${OTLP_ID}`]);
    await client.close();
  });

  it("auto getTrace: Zipkin 404 falls back to native; native ids go to GraphQL", async () => {
    const { s, t, client } = await traces("auto");
    expect(await t.getTrace("0000000000000000000000000000beef")).toBeNull();
    expect(paths(s)).toEqual(["/zipkin/api/v2/trace/0000000000000000000000000000beef", "/graphql"]);
    const native = await t.getTrace(NATIVE_ID);
    expect(native?.traceId).toBe(NATIVE_ID);
    const root = native?.spans.find((sp) => sp.parentSpanId === undefined);
    expect(root).toMatchObject({
      kind: "server",
      name: "/native/entry",
      serviceName: "sw-native-a-1fddf66a",
    });
    expect(root?.spanId).toMatch(/-0$/);
    const ids = new Set(native?.spans.map((sp) => sp.spanId));
    const crossProcess = native?.spans.find((sp) => sp.serviceName === "sw-native-b-1fddf66a");
    expect(crossProcess?.parentSpanId).toBeDefined();
    expect(ids.has(crossProcess?.parentSpanId ?? "")).toBe(true);
    await expect(t.getTrace(" ")).rejects.toMatchObject({ code: "VALIDATION" });
    await client.close();
  });

  it("zipkin and native backends read only their store", async () => {
    const z = await traces("zipkin");
    await expect(z.t.getTrace("not-hex")).rejects.toMatchObject({ code: "VALIDATION" });
    expect(await z.t.getTrace("0000000000000000000000000000beef")).toBeNull();
    expect(z.s.requests).toHaveLength(1);
    const n = await traces("native");
    expect(await n.t.getTrace(OTLP_ID)).toBeNull();
    expect(paths(n.s)).toEqual(["/graphql"]);
    await z.client.close();
    await n.client.close();
  });

  it("zipkin searchTraces maps the SPEC query", async () => {
    const { s, t, client } = await traces("auto");
    const hits = await t.searchTraces({
      service: "sw-otlp-1fddf66a",
      operation: "GET /checkout",
      tags: { run_id: "1fddf66a", "http.method": "GET" },
      minDurationMicros: 1000,
      maxDurationMicros: 90_000.4,
      start: new Date(NOW - 60_000),
      end: new Date(NOW),
      limit: 5,
    });
    const hit = hits.find((h) => h.traceId === OTLP_ID);
    expect(hit).toMatchObject({
      rootName: "get /checkout",
      rootServiceName: "sw-otlp-1fddf66a",
      spanCount: 2,
    });
    const url = new URL(s.requests[0]?.url ?? "", "http://x");
    expect(url.pathname).toBe("/zipkin/api/v2/traces");
    expect(Object.fromEntries(url.searchParams)).toEqual({
      serviceName: "sw-otlp-1fddf66a",
      spanName: "get /checkout",
      annotationQuery: "run_id=1fddf66a and http.method=GET",
      minDuration: "1000",
      maxDuration: "90000",
      endTs: String(NOW),
      lookback: "60000",
      limit: "5",
    });
    await t.searchTraces();
    const plain = new URL(s.requests[1]?.url ?? "", "http://x");
    expect(Object.fromEntries(plain.searchParams)).toEqual({ endTs: String(NOW), limit: "20" });
    await client.close();
  });

  it("searchTraces validates input", async () => {
    const { t, client } = await traces("zipkin");
    await expect(t.searchTraces({ limit: 0 })).rejects.toMatchObject({ code: "VALIDATION" });
    await expect(t.searchTraces({ minDurationMicros: -1 })).rejects.toMatchObject({
      code: "VALIDATION",
    });
    await expect(t.searchTraces({ end: new Date(NaN) })).rejects.toMatchObject({
      code: "VALIDATION",
    });
    await expect(
      t.searchTraces({ start: new Date(NOW), end: new Date(NOW - 1) }),
    ).rejects.toMatchObject({ code: "VALIDATION" });
    await expect(t.searchTraces({ tags: { a: "x and y" } })).rejects.toMatchObject({
      code: "VALIDATION",
    });
    await client.close();
  });

  it("native searchTraces uses queryTraces", async () => {
    const { s, t, client } = await traces("native");
    const hits = await t.searchTraces({
      service: "sw-native-b-1fddf66a",
      operation: "/native/downstream",
      tags: { run_id: "1fddf66a" },
      minDurationMicros: 1500,
      maxDurationMicros: 2500,
      limit: 3,
    });
    expect(hits).toHaveLength(1);
    expect(hits[0]).toMatchObject({
      traceId: NATIVE_ID,
      spanCount: 3,
      rootServiceName: "sw-native-a-1fddf66a",
    });
    const c = graphqlCalls(s, "queryTraces")[0]?.variables.c as Record<string, unknown>;
    expect(c).toMatchObject({
      minTraceDuration: 1,
      maxTraceDuration: 3,
      paging: { pageNum: 1, pageSize: 3 },
      tags: [{ key: "run_id", value: "1fddf66a" }],
    });
    expect(c.endpointId).toBeDefined();
    await t.searchTraces({ start: new Date(NOW - 1000) });
    await expect(t.searchTraces({ operation: "/x" })).rejects.toMatchObject({ code: "VALIDATION" });
    await client.close();
  });

  it("listServices / listOperations: union in auto mode, one store otherwise", async () => {
    const auto = await traces("auto");
    const services = await auto.t.listServices();
    expect(services).toContain("sw-otlp-1fddf66a");
    expect(services).toContain("sw-native-a-1fddf66a");
    expect([...services].sort()).toEqual(services);
    expect(new Set(services).size).toBe(services.length);
    const ops = await auto.t.listOperations("sw-otlp-1fddf66a");
    expect(ops).toContain("get /checkout");
    expect(ops).toContain("/native/entry");
    await expect(auto.t.listOperations("")).rejects.toMatchObject({ code: "VALIDATION" });
    const z = await traces("zipkin");
    expect(await z.t.listServices()).not.toContain("sw-native-a-1fddf66a");
    expect(await z.t.listOperations("sw-otlp-1fddf66a")).not.toContain("/native/entry");
    const n = await traces("native", (r) =>
      r.url === "/graphql" && gql(r).query.includes("listServices")
        ? data({
            listServices: [
              { id: "x", name: "virtual", normal: false },
              { id: "y", name: "real" },
            ],
          })
        : undefined,
    );
    expect(await n.t.listServices()).toEqual(["real"]);
    expect(await n.t.listOperations("real")).toEqual(["/native/entry"]);
    expect(n.s.requests.every((r) => r.url === "/graphql")).toBe(true);
    await Promise.all([auto.client.close(), z.client.close(), n.client.close()]);
  });

  it("getDependencies: topology for auto/native, UNSUPPORTED for zipkin", async () => {
    const { s, t, client } = await traces("auto");
    const deps = await t.getDependencies({ start: new Date(NOW - 60_000), end: new Date(NOW) });
    expect(deps).toEqual([
      { parent: "User", child: "sw-native-a-1fddf66a", callCount: 0 },
      { parent: "sw-native-a-1fddf66a", child: "sw-native-b-1fddf66a", callCount: 0 },
    ]);
    await t.getDependencies();
    expect(graphqlCalls(s, "getGlobalTopology")).toHaveLength(2);
    const z = await traces("zipkin");
    await expect(z.t.getDependencies()).rejects.toMatchObject({ code: "UNSUPPORTED" });
    await client.close();
    await z.client.close();
  });

  it("waitForTrace polls native and hex ids", async () => {
    let calls = 0;
    const { t, client } = await traces("native", (r) => {
      if (r.url !== "/graphql" || !gql(r).query.includes("queryTrace(")) return undefined;
      calls++;
      return calls < 2 ? reply("getTrace.native-missing") : undefined;
    });
    const trace = await t.waitForTrace(NATIVE_ID, { intervalMs: 1, timeoutMs: 5000, minSpans: 2 });
    expect(trace.traceId).toBe(NATIVE_ID);
    expect(calls).toBe(2);
    await expect(
      t.waitForTrace("missing.1.2", {
        intervalMs: 1,
        timeoutMs: 20,
        signal: AbortSignal.timeout(5000),
      }),
    ).rejects.toMatchObject({ code: "TIMEOUT" });
    const z = await traces("zipkin");
    expect((await z.t.waitForTrace(OTLP_ID, { intervalMs: 1 })).spans).toHaveLength(2);
    await client.close();
    await z.client.close();
  });
});

describe("trace model mapping", () => {
  it("zipkin helpers", () => {
    expect(isHexTraceId("ABCDEF0123456789")).toBe(true);
    expect(isHexTraceId(NATIVE_ID)).toBe(false);
    expect(() => zipkinTraceId("0000000000000000", "op")).toThrow(/16 or 32/);
    expect(() => zipkinTraceId(undefined as never, "op")).toThrow();
    expect(stringsFromBody({})).toEqual([]);
    expect(zipkinTracesFromBody("x")).toEqual([]);
    expect(zipkinTracesFromBody([[{ traceId: "a", id: "b" }], [], "x"])).toHaveLength(1);
    expect(zipkinTraceToTrace([])).toBeNull();
    expect(annotationToEvent({ timestamp: 1, value: "plain" })).toEqual({
      name: "plain",
      timeMicros: 1,
      attributes: {},
    });
    expect(annotationToEvent({ timestamp: 1, value: "a||0" })).toEqual({
      name: "a",
      timeMicros: 1,
      attributes: {},
    });
    expect(annotationToEvent({ timestamp: 1, value: "a|not json|0" }).name).toBe("a|not json|0");
    expect(annotationToEvent({ timestamp: 1, value: "a|[1]|0" }).name).toBe("a|[1]|0");
  });

  it("zipkin status and kind mapping", () => {
    const base = { traceId: "00000000000000ab", id: "00000000000000cd" };
    expect(zipkinSpanToSpan(base)).toMatchObject({
      traceId: "000000000000000000000000000000ab",
      kind: "internal",
      status: { code: "unset" },
      name: "",
      serviceName: "",
      startMicros: 0,
    });
    expect(
      zipkinSpanToSpan({
        ...base,
        tags: {
          "otel.status_code": "STATUS_CODE_ERROR",
          "otel.status_description": "boom",
          error: "true",
        },
      }).status,
    ).toEqual({ code: "error", message: "boom" });
    expect(zipkinSpanToSpan({ ...base, tags: { "otel.status_code": "ERROR" } }).status).toEqual({
      code: "error",
    });
    expect(zipkinSpanToSpan({ ...base, tags: { "otel.status_code": "OK" } }).status).toEqual({
      code: "ok",
    });
    expect(zipkinSpanToSpan({ ...base, tags: { error: "" } }).status).toEqual({ code: "error" });
    expect(zipkinSpanToSpan({ ...base, kind: "PRODUCER", parentId: "" }).kind).toBe("producer");
    expect(zipkinSpanToSpan({ ...base, kind: "WEIRD" as never }).kind).toBe("unspecified");
  });

  it("native mapping: kinds, refs, links, events, defaults", () => {
    const span = decodeNativeSpan({
      traceId: "t.1",
      segmentId: "seg-b",
      spanId: 0,
      parentSpanId: -1,
      refs: [
        { traceId: "t.1", parentSegmentId: "seg-a", parentSpanId: 1, type: "CROSS_PROCESS" },
        { traceId: "t.1", parentSegmentId: "seg-c", parentSpanId: 2, type: "CROSS_THREAD" },
      ],
      serviceCode: "b",
      startTime: 10,
      endTime: 5,
      endpointName: "/b",
      type: "Exit",
      peer: "db:5432",
      component: "pg",
      isError: true,
      layer: "Database",
      tags: [
        { key: "db.type", value: "sql" },
        { key: "n", value: 3 },
        { key: "nil", value: null },
      ],
      logs: [
        {
          time: 7,
          data: [
            { key: "event", value: "error" },
            { key: "message", value: "x" },
          ],
        },
        { time: 8, data: [{ key: "k", value: "v" }] },
      ],
    });
    const out = nativeSpanToSpan(span);
    expect(out).toMatchObject({
      spanId: "seg-b-0",
      parentSpanId: "seg-a-1",
      kind: "client",
      durationMicros: 0,
      status: { code: "error" },
      attributes: {
        "db.type": "sql",
        n: "3",
        nil: "",
        "sw.component": "pg",
        "sw.layer": "Database",
        "sw.peer": "db:5432",
      },
      links: [{ traceId: "t.1", spanId: "seg-c-2", attributes: { "sw.ref_type": "CROSS_THREAD" } }],
    });
    expect(out.events.map((e) => e.name)).toEqual(["error", "log"]);
    const local = nativeSpanToSpan(
      decodeNativeSpan({
        segmentId: "s",
        spanId: 2,
        parentSpanId: 1,
        type: "Local",
        serviceInstanceName: "i",
      }),
    );
    expect(local).toMatchObject({
      kind: "internal",
      parentSpanId: "s-1",
      attributes: { "sw.service_instance": "i" },
    });
    expect(nativeSpanToSpan(decodeNativeSpan({ type: "?" })).kind).toBe("unspecified");
    expect(nativeSpanToSpan(decodeNativeSpan(null)).parentSpanId).toBeUndefined();
    expect(nativeTraceToTrace(null)).toBeNull();
    expect(nativeTraceToTrace({ traceId: "x", spans: [] })).toBeNull();
  });

  it("topology helpers", () => {
    expect(decodeTopology(null)).toEqual({ nodes: [], calls: [] });
    const deps = topologyToDependencies(
      decodeTopology({
        nodes: [{ id: "1", name: "a" }],
        calls: [
          { source: "1", target: "2" },
          { source: "1", target: "1" },
          { source: "0", target: "1" },
        ],
      }),
    );
    expect(deps.map((d) => `${d.parent}>${d.child}`)).toEqual(["0>a", "a>2", "a>a"]);
    expect(summarize({ traceId: "x", spans: [] })).toMatchObject({ spanCount: 0 });
  });
});
