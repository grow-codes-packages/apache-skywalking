// Mock SkyWalking OAP for unit tests: GraphQL, /healthcheck, /v3/logs and the Zipkin query API on
// one port, answering with the responses recorded from the real OAP 10.4.0 (test/fixtures, copied
// from the GRO-1383 prep run).

import { readFileSync } from "node:fs";
import {
  SkyWalkingClient,
  type ClientInternals,
  type SkyWalkingConfigOptions,
} from "../src/index.js";
import {
  type MockResponse,
  type MockServer,
  type RecordedRequest,
  startServer,
} from "./helpers/server.js";

interface Fixture {
  request: { method: string; url: string; body?: unknown };
  response: { status: number; headers?: Record<string, string>; body: unknown };
}

/** Loads a recorded exchange. */
export function fixture(name: string): Fixture {
  return JSON.parse(
    readFileSync(new URL(`./fixtures/${name}.json`, import.meta.url), "utf8"),
  ) as Fixture;
}

/** The recorded response of a fixture as a mock response. */
export function reply(name: string): MockResponse {
  const f = fixture(name);
  const body = f.response.body;
  return {
    status: f.response.status,
    headers: { ...f.response.headers },
    body: typeof body === "string" ? body : (body as object),
  };
}

/** A GraphQL `data` answer. */
export function data(value: Record<string, unknown>): MockResponse {
  return { body: { data: value } };
}

/** Parsed GraphQL request body. */
export interface GraphQLBody {
  query: string;
  variables: Record<string, unknown>;
}

/** Parses a recorded GraphQL request. */
export function gql(req: RecordedRequest): GraphQLBody {
  return JSON.parse(req.body) as GraphQLBody;
}

/** A route: return a response to answer, `undefined` to fall through to the defaults. */
export type Route = (req: RecordedRequest, index: number) => MockResponse | undefined;

/** Default answers of the mock OAP. */
export function defaultRoute(req: RecordedRequest): MockResponse {
  const path = req.url.split("?")[0] ?? "";
  if (path === "/healthcheck") return reply("health.http");
  if (path === "/v3/logs") return reply("reportLogs.ok");
  if (path.startsWith("/zipkin/api/v2/trace/")) {
    return path.endsWith("055ffba8ecc69a64950538fda698a308")
      ? reply("getTrace.zipkin")
      : reply("getTrace.zipkin-404");
  }
  if (path === "/zipkin/api/v2/traces") return reply("searchTraces.by-service");
  if (path === "/zipkin/api/v2/services") return reply("listServices.zipkin");
  if (path === "/zipkin/api/v2/spans") return reply("listOperations.zipkin");
  if (path === "/zipkin/api/v2/dependencies") return reply("getDependencies.zipkin-404");
  if (path !== "/graphql" || req.method !== "POST") return { status: 404, body: "not found" };
  const { query, variables } = gql(req);
  if (query.includes("getTimeInfo")) return reply("ping.ok");
  if (query.includes("checkHealth")) return reply("health.graphql");
  if (query.includes("hasQueryTracesV2Support")) return reply("capabilities.ok");
  if (query.includes("listServices")) return reply("listServices.all");
  if (query.includes("findService")) {
    return String(variables.n).startsWith("no-such")
      ? reply("findService.missing")
      : reply("findService.ok");
  }
  if (query.includes("findEndpoint")) return reply("listEndpoints.ok");
  if (query.includes("listInstances")) return reply("listInstances.ok");
  if (query.includes("queryTraces")) return reply("queryTraces.by-service");
  if (query.includes("queryTrace(")) {
    return String(variables.id).startsWith("1fddf66a1c64")
      ? reply("getTrace.native")
      : reply("getTrace.native-otlp-id");
  }
  if (query.includes("queryLogTagAutocompleteKeys")) return reply("queryLogTagAutocompleteKeys.ok");
  if (query.includes("queryLogs")) return reply("queryLogs.by-service");
  if (query.includes("getGlobalTopology")) return reply("topology.global");
  if (query.includes("getServiceTopology")) return reply("topology.service");
  if (query.includes("{version}")) return reply("serverVersion.ok");
  return reply("graphql.validation-error");
}

const servers: MockServer[] = [];

/** Starts a mock OAP; `routes` are tried before the defaults. */
export async function startOap(...routes: Route[]): Promise<MockServer> {
  const server = await startServer((req, index) => {
    for (const route of routes) {
      const res = route(req, index);
      if (res !== undefined) return res;
    }
    return defaultRoute(req);
  });
  servers.push(server);
  return server;
}

/** Closes every server started by {@link startOap}. */
export async function closeOaps(): Promise<void> {
  await Promise.all(servers.splice(0).map((s) => s.close()));
}

/** Fast retry settings for tests. */
export const fast = { retry: { maxRetries: 1, initialBackoffMs: 1 } };

/** A client against a mock OAP (REST + Zipkin on the same port). */
export function clientFor(
  server: MockServer,
  options: SkyWalkingConfigOptions = {},
  internals: ClientInternals = {},
): SkyWalkingClient {
  return new SkyWalkingClient(
    { url: server.url, zipkinUrl: `${server.url}/zipkin`, ...fast, ...options },
    { sleep: () => Promise.resolve(), ...internals },
  );
}

/** GraphQL requests seen by a server whose query contains `needle`. */
export function graphqlCalls(server: MockServer, needle: string): GraphQLBody[] {
  return server.requests
    .filter((r) => r.url === "/graphql" && r.method === "POST")
    .map(gql)
    .filter((b) => b.query.includes(needle));
}
