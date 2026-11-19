// Service-owned (taken over from the sdk-kit skeleton): runs the core conformance scenarios
// against the mock OAP that answers with recorded responses.

import { fileURLToPath } from "node:url";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { SkyWalkingClient } from "../../src/client.js";
import {
  type ConformanceOptions,
  conformanceCases,
  formatReport,
  runConformance,
} from "../../src/testing/index.js";
import type { MockServer } from "../helpers/server.js";
import { closeOaps, startOap } from "../oap-mock.js";

const scenariosDir = fileURLToPath(new URL("../../../conformance/scenarios/", import.meta.url));
let server: MockServer;

beforeAll(async () => {
  server = await startOap();
});

afterAll(closeOaps);

function options(): ConformanceOptions {
  return {
    scenariosDir,
    categories: ["core"],
    factories: { core: () => new SkyWalkingClient({ url: server.url }) },
    capabilities: { core: ["version"] },
  };
}

describe("conformance (core) against the recorded OAP", () => {
  it("every core scenario passes", async () => {
    const report = await runConformance(options());
    expect(report.failed, formatReport(report)).toBe(0);
    expect(report.skipped).toBe(0);
    expect(report.results.map((r) => r.id)).toContain("core.health");
  });

  for (const c of conformanceCases(options())) {
    it.skipIf(c.skip !== undefined)(`case ${c.id}`, async () => {
      expect((await c.run()).status).toBe("passed");
    });
  }
});
