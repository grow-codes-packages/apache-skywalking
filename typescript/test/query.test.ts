import { afterEach, describe, expect, it } from "vitest";
import {
  SkyWalkingClient,
  decodeNativeLog,
  endpointId,
  graphqlError,
  graphqlErrors,
  instanceId,
  serviceId,
} from "../src/index.js";
import {
  clientFor,
  closeOaps,
  data,
  fixture,
  gql,
  graphqlCalls,
  reply,
  startOap,
} from "./oap-mock.js";

afterEach(closeOaps);

const NOW = Date.parse("2026-11-18T21:15:30Z");

async function setup(...routes: Parameters<typeof startOap>) {
  const s = await startOap(...routes);
  const client = clientFor(s, {}, { now: () => NOW });
  return { s, client, q: client.query };
}

describe("SkyWalkingQueryClient", () => {
  it("listServices passes the layer and decodes services", async () => {
    const { s, client, q } = await setup();
    const all = await q.listServices();
    expect(all.map((x) => x.name)).toEqual([
      "sw-otlp-1fddf66a",
      "sw-native-b-1fddf66a",
      "sw-native-a-1fddf66a",
    ]);
    expect(all[0]).toEqual({
      id: "c3ctb3RscC0xZmRkZjY2YQ==.1",
      name: "sw-otlp-1fddf66a",
      group: "",
      shortName: "sw-otlp-1fddf66a",
      layers: ["GENERAL"],
      normal: true,
    });
    await q.listServices("GENERAL", { start: new Date(NOW - 1000) });
    expect(graphqlCalls(s, "listServices").map((b) => b.variables.l)).toEqual([null, "GENERAL"]);
    await client.close();
  });

  it("findService returns the service or null", async () => {
    const { q, client } = await setup();
    expect((await q.findService("sw-native-a-1fddf66a"))?.id).toBe(
      serviceId("sw-native-a-1fddf66a"),
    );
    expect(await q.findService("no-such-service")).toBeNull();
    await expect(q.findService(" ")).rejects.toMatchObject({ code: "VALIDATION" });
    await client.close();
  });

  it("listEndpoints derives the service id, sends keyword and limit, no duration", async () => {
    const { s, q, client } = await setup();
    const eps = await q.listEndpoints("sw-native-a-1fddf66a");
    expect(eps.map((e) => e.name)).toEqual(["/native/entry"]);
    await q.listEndpoints("sw-native-a-1fddf66a", "downstream", { limit: 5 });
    const calls = graphqlCalls(s, "findEndpoint");
    expect(calls[0]?.variables).toEqual({ k: null, s: "c3ctbmF0aXZlLWEtMWZkZGY2NmE=.1", l: 100 });
    expect(calls[1]?.variables).toMatchObject({ k: "downstream", l: 5 });
    expect(calls[0]?.query).not.toContain("duration");
    await expect(q.listEndpoints("a", undefined, { limit: 0 })).rejects.toMatchObject({
      code: "VALIDATION",
    });
    await client.close();
  });

  it("listInstances sends a MINUTE duration in the OAP timezone", async () => {
    const { s, q, client } = await setup((r) =>
      r.url === "/graphql" && gql(r).query.includes("getTimeInfo")
        ? data({ getTimeInfo: { timezone: "+0100", currentTimestamp: NOW } })
        : undefined,
    );
    const inst = await q.listInstances("sw-native-a-1fddf66a");
    expect(inst).toEqual([
      {
        id: "c3ctbmF0aXZlLWEtMWZkZGY2NmE=.1_aW5zdC1hLTFmZGRmNjZh",
        name: "inst-a-1fddf66a",
        language: "GO",
        instanceUUID: "c3ctbmF0aXZlLWEtMWZkZGY2NmE=.1_aW5zdC1hLTFmZGRmNjZh",
        attributes: [{ name: "ipv4s", value: "" }],
      },
    ]);
    const call = graphqlCalls(s, "listInstances")[0];
    expect(call?.variables.d).toEqual({
      start: "2026-11-18 2115",
      end: "2026-11-18 2215",
      step: "MINUTE",
    });
    await q.listInstances("x", {
      start: new Date(NOW - 7_200_000),
      end: new Date(NOW),
      step: "HOUR",
    });
    expect(graphqlCalls(s, "listInstances")[1]?.variables.d).toEqual({
      start: "2026-11-18 20",
      end: "2026-11-18 22",
      step: "HOUR",
    });
    expect(graphqlCalls(s, "getTimeInfo")).toHaveLength(1);
    await expect(
      q.listInstances("x", { start: new Date(NOW), end: new Date(NOW - 1) }),
    ).rejects.toMatchObject({ code: "VALIDATION" });
    await client.close();
  });

  it("queryTraces builds the condition and decodes whole traces", async () => {
    const { s, q, client } = await setup();
    const res = await q.queryTraces({
      service: "sw-native-b-1fddf66a",
      instance: "inst-b",
      endpoint: "/native/downstream",
      traceState: "ERROR",
      minDurationMs: 10,
      maxDurationMs: 2000.4,
      tags: { run_id: "1fddf66a" },
      paging: { pageNum: 2, pageSize: 5 },
      order: "BY_DURATION",
    });
    expect(res.traces).toHaveLength(1);
    expect(res.traces[0]?.traceId).toBe("1fddf66a1c64db2c1345419627769f1f.1.1795036241775");
    expect(res.traces[0]?.spans).toHaveLength(3);
    expect(res.traces[0]?.spans[2]).toMatchObject({
      serviceCode: "sw-native-b-1fddf66a",
      isError: true,
    });
    expect(res.retrievedTimeRange).toEqual({ startTime: 1795034400000, endTime: 1795036560000 });
    const c = graphqlCalls(s, "queryTraces")[0]?.variables.c as Record<string, unknown>;
    expect(c).toMatchObject({
      serviceId: serviceId("sw-native-b-1fddf66a"),
      serviceInstanceId: instanceId("sw-native-b-1fddf66a", "inst-b"),
      endpointId: endpointId("sw-native-b-1fddf66a", "/native/downstream"),
      traceState: "ERROR",
      queryOrder: "BY_DURATION",
      paging: { pageNum: 2, pageSize: 5 },
      minTraceDuration: 10,
      maxTraceDuration: 2000,
      tags: [{ key: "run_id", value: "1fddf66a" }],
    });
    expect(c.queryDuration).toBeDefined();
    await q.queryTraces({ traceId: "abc.1.2" });
    const byId = graphqlCalls(s, "queryTraces")[1]?.variables.c as Record<string, unknown>;
    expect(byId).toEqual({
      traceState: "ALL",
      queryOrder: "BY_START_TIME",
      paging: { pageNum: 1, pageSize: 20 },
      traceId: "abc.1.2",
    });
    await client.close();
  });

  it("queryTraces validates input", async () => {
    const { q, client } = await setup();
    await expect(q.queryTraces({ endpoint: "/x" })).rejects.toMatchObject({ code: "VALIDATION" });
    await expect(q.queryTraces({ minDurationMs: -1 })).rejects.toMatchObject({
      code: "VALIDATION",
    });
    await expect(q.queryTraces({ paging: { pageNum: 0 } })).rejects.toMatchObject({
      code: "VALIDATION",
    });
    await expect(q.queryTraces({ paging: { pageSize: 10_001 } })).rejects.toMatchObject({
      code: "VALIDATION",
    });
    await expect(q.queryTraces({ traceId: "" })).rejects.toMatchObject({ code: "VALIDATION" });
    await client.close();
  });

  it("queryTraces tolerates an empty or odd response", async () => {
    const { q, client } = await setup((r) =>
      r.url === "/graphql" && gql(r).query.includes("queryTraces")
        ? data({
            queryTraces: { traces: [{ spans: [] }, { spans: [{}] }], retrievedTimeRange: null },
          })
        : undefined,
    );
    const res = await q.queryTraces();
    expect(res.traces).toHaveLength(1);
    expect(res.traces[0]?.spans[0]).toMatchObject({
      traceId: "",
      parentSpanId: -1,
      isError: false,
      tags: [],
    });
    expect(res.retrievedTimeRange).toBeUndefined();
    await client.close();
  });

  it("getTrace returns the native trace or null", async () => {
    const { q, client } = await setup();
    const t = await q.getTrace("1fddf66a1c64db2c1345419627769f1f.1.1795036241775");
    expect(t?.spans.length).toBeGreaterThan(1);
    expect(t?.spans[0]).toMatchObject({
      segmentId: expect.any(String) as string,
      spanId: 0,
      parentSpanId: -1,
    });
    expect(await q.getTrace("055ffba8ecc69a64950538fda698a308")).toBeNull();
    await expect(q.getTrace("")).rejects.toMatchObject({ code: "VALIDATION" });
    await client.close();
  });

  it("queryLogs: condition, client-side keyword filter on BanyanDB, errorReason", async () => {
    let reason: string | null = null;
    const { s, q, client } = await setup((r) =>
      r.url === "/graphql" && gql(r).query.includes("queryLogs") && reason !== null
        ? data({ queryLogs: { errorReason: reason, logs: [] } })
        : undefined,
    );
    const all = await q.queryLogs({
      service: "sw-otlp-1fddf66a",
      tags: { run_id: "1fddf66a" },
      order: "ASC",
    });
    expect(all.logs.length).toBeGreaterThan(0);
    expect(all.errorReason).toBeUndefined();
    const contents = all.logs.map((l) => l.content);
    const word = (contents[0] ?? "").split(" ")[0] ?? "";
    const filtered = await q.queryLogs({
      keywords: [word.toUpperCase()],
      excludingKeywords: ["zzz-none"],
    });
    expect(filtered.logs.every((l) => l.content.toLowerCase().includes(word.toLowerCase()))).toBe(
      true,
    );
    const none = await q.queryLogs({ excludingKeywords: [word] });
    expect(none.logs.every((l) => !l.content.includes(word))).toBe(true);
    const conds = graphqlCalls(s, "queryLogs").map((b) => b.variables.c as Record<string, unknown>);
    expect(conds[0]).toMatchObject({
      serviceId: serviceId("sw-otlp-1fddf66a"),
      tags: [{ key: "run_id", value: "1fddf66a" }],
      queryOrder: "ASC",
      paging: { pageNum: 1, pageSize: 20 },
    });
    expect(conds[1]?.keywordsOfContent).toBeUndefined();
    await q.queryLogs({ traceId: "t.1.2", segmentId: "seg", spanId: 1 });
    const related = graphqlCalls(s, "queryLogs")[3]?.variables.c as Record<string, unknown>;
    expect(related.relatedTrace).toEqual({ traceId: "t.1.2", segmentId: "seg", spanId: 1 });
    expect(related.queryDuration).toBeUndefined();
    reason = "too wide";
    expect(await q.queryLogs({ instance: "i", service: "s", endpoint: "e" })).toEqual({
      logs: [],
      errorReason: "too wide",
    });
    await client.close();
  });

  it("queryLogs passes keywords to servers that support them", async () => {
    const { s, q, client } = await setup((r) =>
      r.url === "/graphql" && gql(r).query.includes("hasQueryTracesV2Support")
        ? data({ hasQueryTracesV2Support: true, supportQueryLogsByKeywords: true })
        : undefined,
    );
    expect(await q.capabilities()).toEqual({ queryTracesV2: true, logsByKeywords: true });
    await q.queryLogs({ keywords: ["a"], excludingKeywords: ["b"] });
    await q.queryLogs({ keywords: ["a"] });
    await q.queryLogs({ excludingKeywords: ["b"] });
    const conds = graphqlCalls(s, "queryLogs").map((b) => b.variables.c as Record<string, unknown>);
    expect(conds[0]).toMatchObject({ keywordsOfContent: ["a"], excludingKeywordsOfContent: ["b"] });
    expect(conds[1]?.excludingKeywordsOfContent).toBeUndefined();
    expect(conds[2]?.keywordsOfContent).toBeUndefined();
    expect(graphqlCalls(s, "hasQueryTracesV2Support")).toHaveLength(1);
    await client.close();
  });

  it("capabilities are recorded from the real server and retried after a failure", async () => {
    let fail = true;
    const { q, client } = await setup((r) => {
      if (r.url === "/graphql" && gql(r).query.includes("hasQueryTracesV2Support") && fail) {
        fail = false;
        return { status: 400, body: "bad" };
      }
      return undefined;
    });
    await expect(q.capabilities()).rejects.toMatchObject({ code: "VALIDATION" });
    expect(await q.capabilities()).toEqual({ queryTracesV2: true, logsByKeywords: false });
    await client.close();
  });

  it("listLogTagKeys, timeInfo and topology", async () => {
    const { s, q, client } = await setup();
    expect(await q.listLogTagKeys()).toEqual(
      [
        ...(
          fixture("queryLogTagAutocompleteKeys.ok").response.body as {
            data: { queryLogTagAutocompleteKeys: string[] };
          }
        ).data.queryLogTagAutocompleteKeys,
      ].sort(),
    );
    expect((await q.timeInfo()).timezone).toBe("+0000");
    const topo = await q.topology();
    expect(topo.nodes.map((n) => n.name)).toContain("User");
    expect(topo.calls[0]).toMatchObject({ detectPoints: ["CLIENT", "SERVER"] });
    await q.topology({ start: new Date(NOW - 60_000) }, { layer: "GENERAL" });
    expect(graphqlCalls(s, "getGlobalTopology")[1]?.variables.l).toBe("GENERAL");
    const svc = await q.topology(undefined, { service: "sw-native-a-1fddf66a" });
    expect(svc.nodes.length).toBeGreaterThan(0);
    expect(graphqlCalls(s, "getServiceTopology")[0]?.variables.s).toBe(
      serviceId("sw-native-a-1fddf66a"),
    );
    await client.close();
  });

  it("timeInfo falls back for missing fields; the timezone probe is retried after failure", async () => {
    let n = 0;
    const { q, client } = await setup((r) => {
      if (r.url !== "/graphql" || !gql(r).query.includes("getTimeInfo")) return undefined;
      n++;
      return n === 1 ? { status: 400, body: "x" } : data({ getTimeInfo: {} });
    });
    await expect(q.listInstances("x")).rejects.toMatchObject({ code: "VALIDATION" });
    expect(await q.timeInfo()).toEqual({ timezone: "+0000", currentTimestamp: NOW });
    expect(await q.listInstances("x")).toHaveLength(1);
    await client.close();
  });

  it("maps resolver errors: UnsupportedOperationException → UNSUPPORTED, others → VALIDATION", async () => {
    const { q, client } = await setup((r) => {
      if (r.url !== "/graphql") return undefined;
      const query = gql(r).query;
      if (query.includes("queryTraces")) return reply("queryBasicTraces.unsupported");
      if (query.includes("getGlobalTopology")) return reply("graphql.fetch-error");
      return undefined;
    });
    await expect(q.queryTraces()).rejects.toMatchObject({ code: "UNSUPPORTED" });
    const err = await q.topology().catch((e: unknown) => e);
    expect(err).toMatchObject({ code: "VALIDATION" });
    expect((err as Error).message).toMatch(/^query\.topology: Exception while fetching data/);
    await client.close();
  });

  it("graphqlErrors/graphqlError helpers", () => {
    expect(graphqlErrors(null)).toEqual([]);
    expect(graphqlErrors({ errors: [{ message: 1 }, "x"] })).toEqual([{ message: "" }]);
    expect(graphqlError("op", []).message).toBe("op: GraphQL error");
    const long = graphqlError("op", [{ message: "token=abcdefgh " + "x".repeat(400) }]);
    expect(long.message.length).toBeLessThanOrEqual(305);
    expect(long.message).not.toContain("abcdefgh");
    expect(decodeNativeLog(null)).toMatchObject({ content: "", timestamp: 0, tags: [] });
  });

  it("standalone client from fromEnv works", async () => {
    const s = await startOap();
    const key = `GROW_APACHE_SKYWALKING_URL`;
    process.env[key] = s.url;
    try {
      const c = SkyWalkingClient.fromEnv();
      expect((await c.query.listServices()).length).toBe(3);
      await c.close();
    } finally {
      delete process.env[key];
    }
  });
});
