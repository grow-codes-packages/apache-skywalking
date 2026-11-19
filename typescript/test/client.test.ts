import { afterEach, describe, expect, it } from "vitest";
import {
  PACKAGE_KEY,
  Severity,
  SkyWalkingClient,
  SkyWalkingConfig,
  SkyWalkingError,
  createLogClient,
} from "../src/index.js";
import { closedPort } from "./helpers/server.js";
import {
  clientFor,
  closeOaps,
  data,
  fast,
  graphqlCalls,
  gql,
  reply,
  startOap,
} from "./oap-mock.js";

afterEach(closeOaps);

describe("SkyWalkingClient core", () => {
  it("health, ping and serverVersion against the recorded OAP", async () => {
    const s = await startOap();
    const client = clientFor(s);
    expect(await client.health()).toEqual({ healthy: true, status: "ok", details: {} });
    expect(await client.ping()).toBeGreaterThanOrEqual(0);
    expect(await client.serverVersion()).toBe("10.4.0-b272da3");
    expect(client.transport.userAgent).toContain(`grow-codes-${PACKAGE_KEY}/`);
    expect(s.requests[0]?.url).toBe("/healthcheck");
    expect(graphqlCalls(s, "getTimeInfo")).toHaveLength(1);
    expect(client.query).toBe(client.query);
    expect(client.traces.backend).toBe("auto");
    expect(client.logs).toBeDefined();
    await client.close();
  });

  it("health: 503 → unhealthy, 404 → GraphQL checkHealth score", async () => {
    const down = await startOap((r) =>
      r.url === "/healthcheck" ? { status: 503, body: "down" } : undefined,
    );
    const c1 = clientFor(down);
    expect(await c1.health()).toEqual({
      healthy: false,
      status: "unhealthy",
      details: { httpStatus: "503" },
    });
    const noChecker = await startOap((r) =>
      r.url === "/healthcheck" ? { status: 404 } : undefined,
    );
    const c2 = clientFor(noChecker);
    expect(await c2.health()).toEqual({ healthy: true, status: "ok", details: {} });
    let score = 1;
    const sick = await startOap((r) =>
      r.url === "/healthcheck"
        ? { status: 404 }
        : r.url === "/graphql" && gql(r).query.includes("checkHealth")
          ? data({
              checkHealth: score === 1 ? { score: 1, details: "storage down" } : { score: 2 },
            })
          : undefined,
    );
    const c3 = clientFor(sick);
    expect(await c3.health()).toEqual({
      healthy: false,
      status: "unhealthy",
      details: { score: "1", details: "storage down" },
    });
    score = 2;
    expect(await c3.health()).toEqual({
      healthy: false,
      status: "unhealthy",
      details: { score: "2" },
    });
    const nothing = await startOap((r) =>
      r.url === "/healthcheck" ? { status: 404 } : r.url === "/graphql" ? data({}) : undefined,
    );
    expect((await clientFor(nothing).health()).healthy).toBe(false);
    await Promise.all([c1.close(), c2.close(), c3.close()]);
  });

  it("core.health.unreachable: closed port -> healthy=false, status=unreachable", async () => {
    const port = await closedPort();
    const client = new SkyWalkingClient({ url: `http://127.0.0.1:${port}`, ...fast });
    expect(await client.health()).toEqual({
      healthy: false,
      status: "unreachable",
      details: { error: "UNAVAILABLE" },
    });
    await client.close();
  });

  it("calls on a closed client fail with CANCELLED", async () => {
    const s = await startOap();
    const client = clientFor(s);
    await client.close();
    await expect(client.ping()).rejects.toMatchObject({ code: "CANCELLED" });
    await expect(client.query.listServices()).rejects.toMatchObject({ code: "CANCELLED" });
    await expect(client.traces.listServices()).rejects.toMatchObject({ code: "CANCELLED" });
    expect(s.requests).toHaveLength(0);
  });

  it("health rethrows cancellation", async () => {
    const s = await startOap();
    const client = clientFor(s);
    await expect(client.health({ signal: AbortSignal.abort() })).rejects.toMatchObject({
      code: "CANCELLED",
    });
    await client.close();
  });

  it("ping maps a gateway 401 to AuthenticationError without leaking headers", async () => {
    const s = await startOap(() => ({ status: 401, body: { message: "bad key" } }));
    const client = clientFor(s, { headers: { "x-api-key": "gateway-secret-1" } });
    const err = await client.ping().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(SkyWalkingError);
    expect((err as SkyWalkingError).code).toBe("AUTHENTICATION");
    expect((err as Error).message).not.toContain("gateway-secret-1");
    expect(s.requests[0]?.headers["x-api-key"]).toBe("gateway-secret-1");
    await client.close();
  });

  it("serverVersion raises UnsupportedError when empty", async () => {
    const s = await startOap((r) => (r.url === "/graphql" ? data({ version: "" }) : undefined));
    const client = clientFor(s);
    await expect(client.serverVersion()).rejects.toMatchObject({ code: "UNSUPPORTED" });
    await client.close();
  });

  it("graphql returns data and errors; HTTP 400 throws ValidationError", async () => {
    const s = await startOap((r) =>
      r.url === "/graphql" && gql(r).query.includes("getGlobalTopology")
        ? reply("graphql.fetch-error")
        : undefined,
    );
    const client = clientFor(s);
    const ok = await client.graphql<{ version: string }>("{version}");
    expect(ok).toEqual({ data: { version: "10.4.0-b272da3" }, errors: [] });
    const partial = await client.graphql(
      "query($d:Duration!){getGlobalTopology(duration:$d){nodes{id}}}",
      {
        d: { start: "x", end: "y", step: "MINUTE" },
      },
    );
    expect(partial.data).toEqual({ getGlobalTopology: null });
    expect(partial.errors[0]).toMatchObject({
      path: ["getGlobalTopology"],
      classification: "DataFetchingException",
    });
    const err = await client.graphql("{nope}").catch((e: unknown) => e);
    expect(err).toMatchObject({ code: "VALIDATION", httpStatus: 400 });
    expect((err as Error).message).toContain("FieldUndefined");
    await expect(client.graphql("  ")).rejects.toMatchObject({ code: "VALIDATION" });
    await client.close();
  });

  it("graphql marks mutations non-idempotent (no retry on 500)", async () => {
    const s = await startOap(() => ({ status: 500, body: "boom" }));
    const client = clientFor(s);
    await expect(client.graphql("mutation { x }")).rejects.toMatchObject({ code: "UNAVAILABLE" });
    expect(s.requests).toHaveLength(1);
    await expect(client.graphql("{version}")).rejects.toMatchObject({ code: "UNAVAILABLE" });
    expect(s.requests).toHaveLength(3);
    await client.close();
  });

  it("logClient sends native logs and close() flushes it; close is idempotent", async () => {
    const s = await startOap();
    const client = clientFor(s);
    const logs = client.logClient({
      serviceName: "svc-a",
      serviceInstance: "inst-1",
      batch: { flushIntervalMs: 60_000 },
    });
    logs.send({ message: "hello", severity: Severity.Warn, attributes: { n: 1 } });
    const handle = client.initTracing({ serviceName: "svc-a", setGlobal: false });
    await client.close();
    await client.close();
    const post = s.requests.find((r) => r.url === "/v3/logs");
    expect(post).toBeDefined();
    const body = JSON.parse(post?.body ?? "[]") as Record<string, unknown>[];
    expect(body[0]).toMatchObject({
      service: "svc-a",
      serviceInstance: "inst-1",
      body: { text: { text: "hello" } },
      layer: "GENERAL",
    });
    expect(logs.stats().sent).toBe(1);
    await handle.shutdown();
    await expect(client.ping()).rejects.toMatchObject({ code: "CANCELLED" });
  });

  it("createLogClient owns its connections", async () => {
    const s = await startOap();
    const logs = createLogClient({ url: s.url, ...fast }, { batch: { flushIntervalMs: 60_000 } });
    logs.send({ message: "standalone" });
    await logs.close();
    const body = JSON.parse(s.requests.find((r) => r.url === "/v3/logs")?.body ?? "[]") as Record<
      string,
      unknown
    >[];
    expect(body[0]?.service).toBe("unknown_service");
    const named = createLogClient(
      new SkyWalkingConfig({ url: s.url }),
      { serviceName: "named" },
      {},
    );
    await named.close();
  });

  it("fromEnv builds from GROW_* variables", async () => {
    const s = await startOap();
    const key = `${SkyWalkingConfig.ENV_PREFIX}URL`;
    process.env[key] = s.url;
    try {
      const client = SkyWalkingClient.fromEnv({ traceBackend: "native" });
      expect(client.config.url).toBe(s.url);
      expect(await client.ping()).toBeGreaterThanOrEqual(0);
      await client.close();
    } finally {
      delete process.env[key];
    }
  });
});
