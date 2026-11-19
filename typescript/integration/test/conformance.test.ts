// Runs the shared conformance scenarios (conformance/scenarios) against the real OAP.
// Capabilities: core declares "version"; logging declares "query" (free text is filtered client
// side because BanyanDB ignores content keywords); tracing declares "query" and "services".
// OAP lists a new service ~30-35 s after its first span, and the runner calls listServices once,
// so the tracing service is warmed up first (span sent, then listServices polled).

import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import {
  SkyWalkingClient,
  SkyWalkingConfig,
  createLogClient,
  initTracing,
  type BatchOptions,
} from "@grow-codes/apache-skywalking";
import {
  formatReport,
  runConformance,
  type LoggingTarget,
  type TracingTarget,
} from "@grow-codes/apache-skywalking/testing";
import { loadBootstrapEnv, runId, scenariosDir } from "./env.ts";

const keepAlive = setInterval(() => undefined, 1000);
let client: SkyWalkingClient;

before(() => {
  loadBootstrapEnv();
  client = SkyWalkingClient.fromEnv();
});

after(async () => {
  await client.close();
  clearInterval(keepAlive);
});

test("conformance: core", async () => {
  const report = await runConformance({
    scenariosDir,
    categories: ["core"],
    factories: { core: () => SkyWalkingClient.fromEnv() },
    capabilities: { core: ["version"] },
  });
  console.log(formatReport(report));
  assert.equal(report.failed, 0, formatReport(report));
  assert.equal(report.skipped, 0, formatReport(report));
});

test("conformance: logging", async () => {
  const report = await runConformance({
    scenariosDir,
    categories: ["logging"],
    factories: {
      logging: (ctx): LoggingTarget => ({
        client: createLogClient(SkyWalkingConfig.fromEnv(), {
          serviceName: `conformance-logs-${ctx.run}`,
          batch: (ctx.batchOptions ?? {}) as BatchOptions,
        }),
        query: client.logs,
      }),
    },
    capabilities: { logging: ["query"] },
  });
  console.log(formatReport(report));
  assert.equal(report.failed, 0, formatReport(report));
  assert.equal(report.skipped, 0, formatReport(report));
});

test("conformance: tracing", async () => {
  const serviceName = `conformance-traces-${runId}-${Math.random().toString(16).slice(2, 8)}`
    .toLowerCase()
    .replace(/[^a-z0-9-]/g, "");
  const warm = initTracing({ serviceName, setGlobal: false });
  warm.tracer("warm-up").startSpan("warm-up").end();
  await warm.forceFlush();
  await warm.shutdown();
  const deadline = Date.now() + 120_000;
  while (!(await client.traces.listServices()).includes(serviceName)) {
    assert.ok(Date.now() < deadline, `warm-up: ${serviceName} never listed`);
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  const report = await runConformance({
    scenariosDir,
    categories: ["tracing"],
    factories: {
      tracing: (): TracingTarget => ({
        handle: initTracing({ serviceName, setGlobal: false }),
        query: client.traces,
      }),
    },
    capabilities: { tracing: ["query", "services"] },
  });
  console.log(formatReport(report));
  assert.equal(report.failed, 0, formatReport(report));
  assert.equal(report.skipped, 0, formatReport(report));
});
