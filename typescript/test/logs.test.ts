import { afterEach, describe, expect, it } from "vitest";
import {
  HttpTransport,
  Severity,
  SkyWalkingConfig,
  SkyWalkingLogExporter,
  logToRecord,
  matchesText,
  parseLogQuery,
  recordToLogData,
  tagText,
  type LogRecord,
  type NativeLog,
} from "../src/index.js";
import { clientFor, closeOaps, data, fast, gql, graphqlCalls, startOap } from "./oap-mock.js";
import type { RecordedRequest } from "./helpers/server.js";

afterEach(closeOaps);

const NOW = Date.parse("2026-11-18T21:15:30Z");

function record(overrides: Partial<LogRecord> = {}): LogRecord {
  return {
    timestamp: new Date(NOW),
    severity: Severity.Error,
    severityText: "ERROR",
    body: "payment failed",
    attributes: { order: 7, ok: false, nested: { a: [1, "b"] }, level: "ignored" },
    resource: { "service.name": "pay", "service.instance.id": "pay-1" },
    traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
    spanId: "00f067aa0ba902b7",
    ...overrides,
  };
}

function nativeLog(i: number, content: string, ts = NOW - i * 10): Record<string, unknown> {
  return {
    serviceName: "svc",
    serviceId: "c3Zj.1",
    serviceInstanceName: "",
    endpointName: null,
    traceId: null,
    timestamp: ts,
    contentType: "TEXT",
    content,
    tags: [
      { key: "level", value: "INFO" },
      { key: "run", value: `r${i % 2}` },
    ],
  };
}

/** Mock that pages `logs` by the GraphQL paging argument. */
function pagedLogs(logs: Record<string, unknown>[]) {
  return (r: RecordedRequest) => {
    if (r.url !== "/graphql" || !gql(r).query.includes("queryLogs")) return undefined;
    const c = gql(r).variables.c as { paging: { pageNum: number; pageSize: number } };
    const from = (c.paging.pageNum - 1) * c.paging.pageSize;
    return data({
      queryLogs: { errorReason: null, logs: logs.slice(from, from + c.paging.pageSize) },
    });
  };
}

describe("log mapping", () => {
  it("recordToLogData maps resource, level, attributes and trace context", () => {
    expect(recordToLogData(record())).toEqual({
      timestamp: NOW,
      service: "pay",
      serviceInstance: "pay-1",
      body: { text: { text: "payment failed" } },
      traceContext: { traceId: "4bf92f3577b34da6a3ce929d0e0e4736" },
      tags: {
        data: [
          { key: "level", value: "ERROR" },
          { key: "order", value: "7" },
          { key: "ok", value: "false" },
          { key: "nested", value: '{"a":[1,"b"]}' },
          { key: "span_id", value: "00f067aa0ba902b7" },
        ],
      },
      layer: "GENERAL",
    });
    const bare = recordToLogData(
      record({ resource: {}, traceId: "", spanId: "", severityText: "", severity: Severity.Warn }),
      "fallback",
    );
    expect(bare.service).toBe("fallback");
    expect(bare.serviceInstance).toBeUndefined();
    expect(bare.traceContext).toBeUndefined();
    expect(bare.tags.data[0]).toEqual({ key: "level", value: "WARN" });
    expect(tagText(["x", 1])).toBe('["x",1]');
  });

  it("logToRecord maps back", () => {
    const log: NativeLog = {
      serviceName: "pay",
      serviceId: "x",
      serviceInstanceName: "pay-1",
      endpointName: "/pay",
      traceId: "4BF92F3577B34DA6A3CE929D0E0E4736",
      timestamp: NOW,
      contentType: "TEXT",
      content: "payment failed",
      tags: [
        { key: "level", value: "warning" },
        { key: "span_id", value: "00f067aa0ba902b7" },
        { key: "order", value: "7" },
      ],
    };
    expect(logToRecord(log)).toEqual({
      timestamp: new Date(NOW),
      severity: Severity.Warn,
      severityText: "warning",
      body: "payment failed",
      attributes: { order: "7" },
      resource: { "service.name": "pay", "service.instance.id": "pay-1", "sw.endpoint": "/pay" },
      traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
      spanId: "00f067aa0ba902b7",
    });
    const native = logToRecord({
      ...log,
      serviceInstanceName: "",
      endpointName: "",
      traceId: "a.1.2",
      tags: [
        { key: "level", value: "noise" },
        { key: "span_id", value: "nothex" },
      ],
    });
    expect(native).toMatchObject({
      severity: Severity.Info,
      severityText: "noise",
      attributes: { span_id: "nothex", "sw.trace_id": "a.1.2" },
    });
    expect(native.traceId).toBeUndefined();
    const plain = logToRecord({ ...log, traceId: "", tags: [] });
    expect(plain).toMatchObject({ severity: Severity.Info, severityText: "INFO", attributes: {} });
  });

  it("parseLogQuery and matchesText", () => {
    expect(
      parseLogQuery(
        'service=pay instance=p1 endpoint="/a b" trace_id=t1 level=ERROR hello "two words"',
      ),
    ).toEqual({
      service: "pay",
      instance: "p1",
      endpoint: "/a b",
      traceId: "t1",
      tags: { level: "ERROR" },
      text: ["hello", "two words"],
    });
    expect(parseLogQuery('traceId=x ""')).toEqual({ traceId: "x", tags: {}, text: [] });
    const log = { content: "Hello World", tags: [{ key: "k", value: "TagValue" }] } as NativeLog;
    expect(matchesText(log, [])).toBe(true);
    expect(matchesText(log, ["hello", "tagvalue"])).toBe(true);
    expect(matchesText(log, ["missing"])).toBe(false);
  });
});

describe("SkyWalkingLogExporter", () => {
  it("posts JSON arrays and surfaces errors", async () => {
    let status = 200;
    const s = await startOap((r) => (r.url === "/v3/logs" ? { status, body: "" } : undefined));
    const cfg = new SkyWalkingConfig({ url: s.url, ...fast });
    const transport = new HttpTransport({ config: cfg, sleep: () => Promise.resolve() });
    const exporter = new SkyWalkingLogExporter(transport, {
      serviceName: "dflt",
      ownsTransport: true,
    });
    expect(await exporter.export([], { gzip: true })).toEqual({ accepted: 0, rejected: 0 });
    expect(await exporter.export([record(), record({ resource: {} })], { gzip: true })).toEqual({
      accepted: 2,
      rejected: 0,
    });
    const body = JSON.parse(s.requests[0]?.body ?? "") as { service: string }[];
    expect(body.map((b) => b.service)).toEqual(["pay", "dflt"]);
    expect(s.requests[0]?.headers["content-type"]).toBe("application/json");
    status = 400;
    await expect(exporter.export([record()], { gzip: false })).rejects.toMatchObject({
      code: "VALIDATION",
    });
    await expect(
      exporter.export([record()], { gzip: false, signal: AbortSignal.abort() }),
    ).rejects.toMatchObject({ code: "CANCELLED" });
    await exporter.shutdown();
    await exporter.shutdown();
    expect(transport.closed).toBe(true);
    const shared = new SkyWalkingLogExporter(new HttpTransport({ config: cfg }));
    await shared.shutdown();
  });
});

describe("SkyWalkingLogQueryClient", () => {
  it("search without free text pages with limit and cursors", async () => {
    const logs = Array.from({ length: 5 }, (_, i) => nativeLog(i, `line ${i}`));
    const s = await startOap(pagedLogs(logs));
    const client = clientFor(s, {}, { now: () => NOW });
    const p1 = await client.logs.search("service=svc instance=i endpoint=e trace_id=t.1 run=r0", {
      limit: 2,
      order: "asc",
    });
    expect(p1.records.map((r) => r.body)).toEqual(["line 0", "line 1"]);
    expect(p1.nextCursor).toBe("2:0:2");
    const c = graphqlCalls(s, "queryLogs")[0]?.variables.c as Record<string, unknown>;
    expect(c).toMatchObject({
      queryOrder: "ASC",
      paging: { pageNum: 1, pageSize: 2 },
      tags: [{ key: "run", value: "r0" }],
      relatedTrace: { traceId: "t.1" },
    });
    expect(c.serviceId).toBeDefined();
    expect(c.serviceInstanceId).toBeDefined();
    expect(c.endpointId).toBeDefined();
    expect(c.queryDuration).toMatchObject({ step: "MINUTE" });
    const p3 = await client.logs.search("", { limit: 2, cursor: "3:0:2" });
    expect(p3.records.map((r) => r.body)).toEqual(["line 4"]);
    expect(p3.nextCursor).toBeUndefined();
    await client.close();
  });

  it("free text is filtered client side over raw pages of 1000 with resumable cursors", async () => {
    const logs = Array.from({ length: 2500 }, (_, i) =>
      nativeLog(i, i % 3 === 0 ? `match ${i}` : `other ${i}`, NOW - 1000),
    );
    const s = await startOap(pagedLogs(logs));
    const client = clientFor(s, {}, { now: () => NOW });
    const first = await client.logs.search("MATCH", { limit: 400 });
    expect(first.records).toHaveLength(400);
    expect(first.records.every((r) => r.body.startsWith("match"))).toBe(true);
    expect(first.nextCursor).toBe(`2:${1197 - 1000 + 1}:1000`);
    const rest = await client.logs.search("MATCH", { limit: 1000, cursor: first.nextCursor ?? "" });
    expect(rest.records).toHaveLength(834 - 400);
    expect(rest.nextCursor).toBeUndefined();
    const sizes = graphqlCalls(s, "queryLogs").map(
      (b) => (b.variables.c as { paging: { pageSize: number } }).paging.pageSize,
    );
    expect(new Set(sizes)).toEqual(new Set([1000]));
    const exact = await client.logs.search("match", { limit: 1 });
    expect(exact.nextCursor).toBe("1:1:1000");
    await client.close();
  });

  it("drops logs outside the exact window, stops after maxScanPages, ends cleanly on a full last page", async () => {
    const logs = [
      nativeLog(0, "inside", NOW - 5000),
      nativeLog(1, "too old", NOW - 7_200_000),
      nativeLog(2, "future", NOW + 60_000),
    ];
    const s = await startOap(pagedLogs(logs));
    const client = clientFor(s, {}, { now: () => NOW, maxScanPages: 1 });
    const res = await client.logs.search("", { limit: 10 });
    expect(res.records.map((r) => r.body)).toEqual(["inside"]);
    const scan = await client.logs.search("nothing-matches", { limit: 1 });
    expect(scan.records).toEqual([]);
    expect(scan.nextCursor).toBeUndefined();
    const full = await startOap(pagedLogs([nativeLog(0, "a"), nativeLog(1, "b")]));
    const c2 = clientFor(full, {}, { now: () => NOW, maxScanPages: 1 });
    expect((await c2.logs.search("", { limit: 2 })).nextCursor).toBe("2:0:2");
    expect((await c2.logs.search("", { limit: 1, cursor: "1:0:2" })).nextCursor).toBe("1:1:2");
    expect((await c2.logs.search("zz", { limit: 1, cursor: "1:0:2" })).nextCursor).toBe("2:0:2");
    await client.close();
    await c2.close();
  });

  it("validates input", async () => {
    const s = await startOap();
    const client = clientFor(s, {}, { now: () => NOW });
    await expect(client.logs.search("", { limit: 0 })).rejects.toMatchObject({
      code: "VALIDATION",
    });
    await expect(client.logs.search("", { cursor: "bad" })).rejects.toMatchObject({
      code: "VALIDATION",
    });
    await expect(client.logs.search("", { cursor: "0:0:10" })).rejects.toMatchObject({
      code: "VALIDATION",
    });
    await expect(
      client.logs.search("", { start: new Date(NOW), end: new Date(NOW - 1) }),
    ).rejects.toMatchObject({ code: "VALIDATION" });
    expect(() => clientFor(s, {}, { maxScanPages: 0 })).toThrow(/maxScanPages/);
    await client.close();
  });

  it("listStreams, listLabels, waitForLog and tail", async () => {
    const s = await startOap();
    const client = clientFor(s, {}, { now: () => 1795036300000 });
    expect(await client.logs.listStreams()).toEqual([
      "sw-native-a-1fddf66a",
      "sw-native-b-1fddf66a",
      "sw-otlp-1fddf66a",
    ]);
    expect((await client.logs.listLabels()).length).toBeGreaterThan(0);
    const found = await client.logs.waitForLog("1fddf66a", { timeoutMs: 2000, intervalMs: 10 });
    expect(found.resource["service.name"]).toBe("sw-otlp-1fddf66a");
    const seen: LogRecord[] = [];
    const handle = client.logs.tail("", (r) => seen.push(r), { intervalMs: 5, start: new Date(0) });
    await new Promise((resolve) => setTimeout(resolve, 100));
    handle.stop();
    expect(seen.length).toBeGreaterThan(0);
    await client.close();
  });
});
