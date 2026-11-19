// Integration tests against the real SkyWalking OAP 10.4.0 + BanyanDB (docker/compose.yml).
// Every public function is called at least once with a round-trip assertion (write → read back).
// Native traces are seeded over OAP's documented HTTP API (/v3/segments) because no SkyWalking
// agent runs in the test container; OTLP spans come from the package's own initTracing.

import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { ROOT_CONTEXT, defaultTextMapGetter, defaultTextMapSetter } from "@opentelemetry/api";
import {
  Severity,
  SkyWalkingClient,
  SkyWalkingConfig,
  Sw8Propagator,
  createLogClient,
  decodeSw8,
  decodeSw8Correlation,
  encodeSw8,
  encodeSw8Correlation,
  getSw8Context,
  initTracing,
  serviceId,
  sw8Attributes,
  withSw8Context,
  type LogRecord,
  type Sw8Context,
  type TracingHandle,
} from "@grow-codes/apache-skywalking";
import { loadBootstrapEnv, runId } from "./env.ts";

const run = `${runId}-${Math.random().toString(16).slice(2, 8)}`
  .toLowerCase()
  .replace(/[^a-z0-9-]/g, "");
const SVC_A = `it-native-a-${run}`;
const SVC_B = `it-native-b-${run}`;
const SVC_OTLP = `it-otlp-${run}`;
const SVC_LOGS = `it-logs-${run}`;
const keepAlive = setInterval(() => undefined, 1000);

let client: SkyWalkingClient;
let handle: TracingHandle;
let otlpTraceId = "";
let nativeTraceId = "";
let segA = "";
let segB = "";

async function poll<T>(
  label: string,
  fn: () => Promise<T>,
  ok: (value: T) => boolean,
  timeoutMs = 120_000,
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let last: T | undefined;
  let lastError: unknown;
  while (Date.now() < deadline) {
    try {
      last = await fn();
      if (ok(last)) return last;
    } catch (err) {
      lastError = err;
    }
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  throw new Error(
    `${label}: not satisfied within ${timeoutMs} ms; last=${JSON.stringify(last)} error=${String(lastError)}`,
  );
}

function hex(n: number): string {
  let out = "";
  while (out.length < n) out += Math.random().toString(16).slice(2);
  return out.slice(0, n);
}

/** Seeds a native A → B trace; B's ref is built by decoding the sw8 header of A's exit span. */
async function seedNativeTrace(): Promise<void> {
  const now = Date.now();
  nativeTraceId = `${hex(32)}.1.${now}`;
  segA = `${hex(32)}.1.${now}`;
  segB = `${hex(32)}.2.${now}`;
  const header = encodeSw8({
    sample: true,
    traceId: nativeTraceId,
    parentTraceSegmentId: segA,
    parentSpanId: 1,
    parentService: SVC_A,
    parentServiceInstance: `inst-a-${run}`,
    parentEndpoint: "/it/entry",
    networkAddressUsedAtPeer: `${SVC_B}:8080`,
  });
  const ref = decodeSw8(header);
  assert.ok(ref);
  const segments = [
    {
      traceId: nativeTraceId,
      traceSegmentId: segA,
      service: SVC_A,
      serviceInstance: `inst-a-${run}`,
      spans: [
        {
          spanId: 1,
          parentSpanId: 0,
          startTime: now - 80,
          endTime: now - 20,
          operationName: "/it/downstream",
          peer: `${SVC_B}:8080`,
          spanType: "Exit",
          spanLayer: "Http",
          componentId: 2,
          isError: false,
          tags: [{ key: "http.method", value: "GET" }],
        },
        {
          spanId: 0,
          parentSpanId: -1,
          startTime: now - 100,
          endTime: now,
          operationName: "/it/entry",
          peer: "",
          spanType: "Entry",
          spanLayer: "Http",
          componentId: 1,
          isError: false,
          tags: [
            { key: "http.method", value: "GET" },
            { key: "run_id", value: run },
          ],
        },
      ],
    },
    {
      traceId: ref.traceId,
      traceSegmentId: segB,
      service: SVC_B,
      serviceInstance: `inst-b-${run}`,
      spans: [
        {
          spanId: 0,
          parentSpanId: -1,
          startTime: now - 70,
          endTime: now - 30,
          operationName: "/it/downstream",
          peer: "",
          spanType: "Entry",
          spanLayer: "Http",
          componentId: 1,
          isError: true,
          refs: [
            {
              refType: "CrossProcess",
              traceId: ref.traceId,
              parentTraceSegmentId: ref.parentTraceSegmentId,
              parentSpanId: ref.parentSpanId,
              parentService: ref.parentService,
              parentServiceInstance: ref.parentServiceInstance,
              parentEndpoint: ref.parentEndpoint,
              networkAddressUsedAtPeer: ref.networkAddressUsedAtPeer,
            },
          ],
          tags: [{ key: "run_id", value: run }],
          logs: [
            {
              time: now - 40,
              data: [
                { key: "event", value: "error" },
                { key: "message", value: "boom" },
              ],
            },
          ],
        },
      ],
    },
  ];
  const res = await fetch(`${client.config.url}/v3/segments`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(segments),
  });
  assert.equal(res.status, 200, await res.text());
}

before(async () => {
  loadBootstrapEnv();
  assert.ok(process.env.GROW_APACHE_SKYWALKING_URL, "GROW_APACHE_SKYWALKING_URL must be set");
  client = SkyWalkingClient.fromEnv();
  await seedNativeTrace();
  handle = client.initTracing({
    serviceName: SVC_OTLP,
    setGlobal: false,
    propagators: ["tracecontext", "baggage", "sw8"],
  });
  const tracer = handle.tracer("it");
  const root = tracer.startSpan("GET /checkout", { attributes: { run_id: run, retries: 2 } });
  otlpTraceId = root.spanContext().traceId;
  const { trace, context } = await import("@opentelemetry/api");
  const child = tracer.startSpan("select orders", {}, trace.setSpan(context.active(), root));
  child.addEvent("cache.miss", { k: "v" });
  child.end();
  root.end();
  await handle.forceFlush(30_000);
  const logs = client.logClient({
    serviceName: SVC_LOGS,
    serviceInstance: `inst-${run}`,
    batch: { flushIntervalMs: 200 },
  });
  logs.send({
    message: `order created native-log-${run}`,
    severity: Severity.Warn,
    attributes: { run_id: run, n: 1 },
  });
  logs.send({
    message: `payment failed native-log-${run}`,
    severity: Severity.Error,
    attributes: { run_id: run },
    traceId: otlpTraceId,
    spanId: root.spanContext().spanId,
  });
  await logs.flush();
  assert.equal(logs.stats().sent, 2);
});

after(async () => {
  await client.close();
  clearInterval(keepAlive);
});

test("health, ping, serverVersion, graphql, timeInfo, capabilities", async () => {
  assert.deepEqual(await client.health(), { healthy: true, status: "ok", details: {} });
  assert.ok((await client.ping()) >= 0);
  const version = await client.serverVersion();
  assert.match(version, /^10\.4\./);
  const raw = await client.graphql<{ version: string }>("{version}");
  assert.equal(raw.data?.version, version);
  const viaQuery = await client.query.graphql<{ version: string }>("query{version}");
  assert.equal(viaQuery.data?.version, version);
  await assert.rejects(client.graphql("{nope}"), { code: "VALIDATION" });
  const time = await client.query.timeInfo();
  assert.equal(typeof time.timezone, "string");
  assert.ok(Math.abs(time.currentTimestamp - Date.now()) < 300_000);
  assert.deepEqual(await client.query.capabilities(), {
    queryTracesV2: true,
    logsByKeywords: false,
  });
});

test("traces (Zipkin store): waitForTrace/getTrace round-trips the OTLP spans", async () => {
  const trace = await client.traces.waitForTrace(otlpTraceId, {
    timeoutMs: 90_000,
    intervalMs: 2000,
    minSpans: 2,
  });
  assert.equal(trace.traceId, otlpTraceId);
  const root = trace.spans.find((s) => s.parentSpanId === undefined);
  const child = trace.spans.find((s) => s.parentSpanId !== undefined);
  assert.equal(root?.name, "get /checkout");
  assert.equal(root?.serviceName, SVC_OTLP);
  assert.equal(root?.attributes.run_id, run);
  assert.equal(child?.parentSpanId, root?.spanId);
  assert.deepEqual(child?.events[0]?.attributes, { k: "v" });
  assert.equal((await client.traces.getTrace(otlpTraceId))?.spans.length, 2);
  assert.equal(await client.traces.getTrace("0000000000000000000000000000beef"), null);
});

test("traces.searchTraces finds the OTLP trace by service and tag", async () => {
  const hits = await poll(
    "searchTraces",
    () =>
      client.traces.searchTraces({
        service: SVC_OTLP,
        tags: { run_id: run },
        start: new Date(Date.now() - 600_000),
      }),
    (h) => h.some((x) => x.traceId === otlpTraceId),
  );
  const hit = hits.find((x) => x.traceId === otlpTraceId);
  assert.equal(hit?.rootName, "get /checkout");
  assert.equal(hit?.spanCount, 2);
});

test("traces.listServices / listOperations list the OTLP and native services", async () => {
  const services = await poll(
    "traces.listServices",
    () => client.traces.listServices(),
    (s) => s.includes(SVC_OTLP) && s.includes(SVC_A),
  );
  assert.ok(services.includes(SVC_B));
  const ops = await poll(
    "traces.listOperations",
    () => client.traces.listOperations(SVC_OTLP),
    (o) => o.includes("get /checkout"),
  );
  assert.ok(ops.includes("select orders"));
  const nativeOps = await poll(
    "traces.listOperations native",
    () => client.traces.listOperations(SVC_A),
    (o) => o.includes("/it/entry"),
  );
  assert.ok(nativeOps.length >= 1);
});

test("traces: native trace by native id, sw8-linked parent, dependencies", async () => {
  const trace = await client.traces.waitForTrace(nativeTraceId, {
    timeoutMs: 90_000,
    intervalMs: 2000,
    minSpans: 3,
  });
  assert.equal(trace.traceId, nativeTraceId);
  const b = trace.spans.find((s) => s.serviceName === SVC_B);
  assert.equal(b?.parentSpanId, `${segA}-1`);
  assert.equal(b?.status.code, "error");
  assert.equal(b?.events[0]?.name, "error");
  const deps = await poll(
    "getDependencies",
    () => client.traces.getDependencies({ start: new Date(Date.now() - 900_000) }),
    (d) => d.some((l) => l.parent === SVC_A && l.child === SVC_B),
  );
  assert.equal(deps.find((l) => l.parent === SVC_A)?.callCount, 0);
});

test("query: services, endpoints, instances", async () => {
  const services = await poll(
    "query.listServices",
    () => client.query.listServices("GENERAL"),
    (s) => s.some((x) => x.name === SVC_A),
  );
  assert.equal(services.find((x) => x.name === SVC_A)?.id, serviceId(SVC_A));
  assert.deepEqual(
    await client.query.listServices("NO_SUCH_LAYER", { start: new Date(Date.now() - 60_000) }),
    [],
  );
  assert.equal((await client.query.findService(SVC_A))?.name, SVC_A);
  assert.equal(await client.query.findService(`no-such-${run}`), null);
  const eps = await poll(
    "listEndpoints",
    () => client.query.listEndpoints(SVC_A),
    (e) => e.some((x) => x.name === "/it/entry"),
  );
  assert.ok(eps.length >= 1);
  const kw = await client.query.listEndpoints(SVC_B, "downstream", { limit: 5 });
  assert.deepEqual(
    kw.map((e) => e.name),
    ["/it/downstream"],
  );
  const inst = await poll(
    "listInstances",
    () => client.query.listInstances(SVC_A, { start: new Date(Date.now() - 900_000) }),
    (i) => i.length > 0,
  );
  assert.equal(inst[0]?.name, `inst-a-${run}`);
});

test("query: queryTraces, getTrace, topology", async () => {
  const res = await poll(
    "queryTraces",
    () =>
      client.query.queryTraces({
        service: SVC_A,
        tags: { run_id: run },
        range: { start: new Date(Date.now() - 900_000) },
      }),
    (r) => r.traces.some((t) => t.traceId === nativeTraceId),
  );
  const t = res.traces.find((x) => x.traceId === nativeTraceId);
  assert.equal(t?.spans.length, 3);
  const errors = await client.query.queryTraces({
    service: SVC_B,
    traceState: "ERROR",
    range: { start: new Date(Date.now() - 900_000) },
  });
  assert.ok(errors.traces.some((x) => x.traceId === nativeTraceId));
  const none = await client.query.queryTraces({
    service: SVC_A,
    minDurationMs: 60_000,
    range: { start: new Date(Date.now() - 900_000) },
  });
  assert.equal(none.traces.length, 0);
  const native = await client.query.getTrace(nativeTraceId);
  const bSpan = native?.spans.find((s) => s.serviceCode === SVC_B);
  assert.equal(bSpan?.refs[0]?.parentSegmentId, segA);
  assert.equal(bSpan?.refs[0]?.type, "CROSS_PROCESS");
  assert.equal(await client.query.getTrace(otlpTraceId), null);
  const topo = await poll(
    "topology",
    () => client.query.topology({ start: new Date(Date.now() - 900_000) }),
    (tp) => tp.calls.some((c) => c.source === serviceId(SVC_A) && c.target === serviceId(SVC_B)),
  );
  assert.ok(topo.nodes.some((n) => n.name === SVC_B));
  const svcTopo = await client.query.topology(
    { start: new Date(Date.now() - 900_000) },
    { service: SVC_A },
  );
  assert.ok(svcTopo.nodes.some((n) => n.name === SVC_B));
});

test("query: queryLogs by service, tag, related trace, keywords; listLogTagKeys", async () => {
  const byService = await poll(
    "queryLogs",
    () => client.query.queryLogs({ service: SVC_LOGS }),
    (r) => r.logs.length >= 2,
  );
  assert.ok(byService.logs.every((l) => l.serviceName === SVC_LOGS));
  const byTag = await client.query.queryLogs({ tags: { run_id: run }, order: "ASC" });
  assert.equal(byTag.logs.length, 2);
  assert.ok(byTag.logs[0]?.timestamp <= (byTag.logs[1]?.timestamp ?? 0));
  const related = await client.query.queryLogs({ traceId: otlpTraceId });
  assert.equal(related.logs.length, 1);
  assert.match(related.logs[0]?.content ?? "", /payment failed/);
  const kw = await client.query.queryLogs({
    service: SVC_LOGS,
    keywords: ["payment"],
    excludingKeywords: ["order"],
  });
  assert.deepEqual(
    kw.logs.map((l) => l.content),
    [`payment failed native-log-${run}`],
  );
  const keys = await poll(
    "listLogTagKeys",
    () => client.query.listLogTagKeys(),
    (k) => k.includes("run_id"),
  );
  assert.ok(keys.includes("level"));
});

test("logs (LogQueryClient): search, waitForLog, streams, labels, tail", async () => {
  const found = await client.logs.waitForLog(`payment native-log-${run}`, {
    timeoutMs: 60_000,
    intervalMs: 2000,
  });
  assert.equal(found.severity, Severity.Error);
  assert.equal(found.traceId, otlpTraceId);
  assert.equal(found.attributes.run_id, run);
  assert.equal(found.resource["service.name"], SVC_LOGS);
  const byTag = await client.logs.search(`run_id=${run}`, { limit: 1 });
  assert.equal(byTag.records.length, 1);
  assert.ok(byTag.nextCursor);
  const page2 = await client.logs.search(`run_id=${run}`, { limit: 1, cursor: byTag.nextCursor });
  assert.equal(page2.records.length, 1);
  assert.notEqual(page2.records[0]?.body, byTag.records[0]?.body);
  const byTrace = await client.logs.search(`trace_id=${otlpTraceId}`);
  assert.equal(byTrace.records.length, 1);
  const byService = await client.logs.search(`service=${SVC_LOGS} instance=inst-${run} created`);
  assert.deepEqual(
    byService.records.map((r) => r.body),
    [`order created native-log-${run}`],
  );
  assert.ok((await client.logs.listStreams()).includes(SVC_LOGS));
  assert.ok((await client.logs.listLabels()).includes("run_id"));

  const seen: LogRecord[] = [];
  const tail = client.logs.tail(`tail-${run}`, (r) => seen.push(r), { intervalMs: 1000 });
  const writer = createLogClient(SkyWalkingConfig.fromEnv(), {
    serviceName: SVC_LOGS,
    batch: { flushIntervalMs: 100 },
  });
  try {
    writer.send({ message: `tail-${run} live` });
    await writer.flush();
    await poll(
      "tail",
      () => Promise.resolve(seen.length),
      (n) => n >= 1,
      60_000,
    );
    assert.equal(seen[0]?.body, `tail-${run} live`);
  } finally {
    tail.stop();
    await writer.close();
  }
});

test("standalone initTracing + sw8 propagator against OAP", async () => {
  const standalone = initTracing({
    serviceName: `${SVC_OTLP}-standalone`,
    setGlobal: false,
    propagators: ["sw8"],
  });
  const span = standalone.tracer("it").startSpan("standalone");
  const id = span.spanContext().traceId;
  span.end();
  await standalone.forceFlush();
  await standalone.shutdown();
  assert.equal(
    (await client.traces.waitForTrace(id, { timeoutMs: 90_000, intervalMs: 2000 })).spans[0]?.name,
    "standalone",
  );

  const p = new Sw8Propagator();
  const segments = await client.query.getTrace(nativeTraceId);
  const exit = segments?.spans.find((s) => s.type === "Exit");
  assert.ok(exit);
  const sw8: Sw8Context = {
    sample: true,
    traceId: exit.traceId,
    parentTraceSegmentId: exit.segmentId,
    parentSpanId: exit.spanId,
    parentService: exit.serviceCode,
    parentServiceInstance: exit.serviceInstanceName,
    parentEndpoint: "/it/entry",
    networkAddressUsedAtPeer: exit.peer,
  };
  const carrier: Record<string, string> = {};
  p.inject(
    withSw8Context(ROOT_CONTEXT, { ...sw8, correlation: { run } }),
    carrier,
    defaultTextMapSetter,
  );
  assert.equal(carrier.sw8, encodeSw8(sw8));
  assert.equal(carrier["sw8-correlation"], encodeSw8Correlation({ run }));
  assert.deepEqual(decodeSw8Correlation(carrier["sw8-correlation"]), { run });
  const extracted = getSw8Context(p.extract(ROOT_CONTEXT, carrier, defaultTextMapGetter));
  assert.deepEqual(extracted, sw8);
  assert.equal(sw8Attributes(sw8)["sw8.parent.segment_id"], segA);
});
