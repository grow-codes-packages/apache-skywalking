import { inspect } from "node:util";
import { describe, expect, it } from "vitest";
import {
  ConfigError,
  PACKAGE_KEY,
  REDACTED,
  RetryPolicy,
  Secret,
  SkyWalkingConfig,
  baseFromGraphqlUrl,
  collectorToUrl,
  parseOtlpHeaders,
  zipkinQueryUrl,
} from "../src/index.js";

const P = SkyWalkingConfig.ENV_PREFIX;

function configError(fn: () => unknown): ConfigError {
  try {
    fn();
  } catch (e) {
    expect(e).toBeInstanceOf(ConfigError);
    return e as ConfigError;
  }
  throw new Error("expected ConfigError");
}

describe("SkyWalkingConfig", () => {
  it("uses defaults derived from the url and freezes", () => {
    const c = new SkyWalkingConfig();
    expect(c.url).toBe("http://127.0.0.1:12800");
    expect(c.graphqlPath).toBe("/graphql");
    expect(c.graphqlUrl).toBe("http://127.0.0.1:12800/graphql");
    expect(c.otlpEndpoint).toBe("http://127.0.0.1:11800");
    expect(c.otlpProtocol).toBe("grpc");
    expect(c.zipkinUrl).toBe("http://127.0.0.1:9412/zipkin");
    expect(c.traceBackend).toBe("auto");
    expect(c.authentication).toBeUndefined();
    expect(c.otlpHeaders).toEqual({});
    expect(c.retry).toBeInstanceOf(RetryPolicy);
    expect(Object.isFrozen(c)).toBe(true);
    expect(SkyWalkingConfig.from(c)).toBe(c);
    expect(SkyWalkingConfig.from().url).toBe(c.url);
    expect(P).toBe(`GROW_${PACKAGE_KEY.toUpperCase().replaceAll("-", "_")}_`);
  });

  it("derives OTLP and Zipkin endpoints from the url host and strips /graphql", () => {
    const c = new SkyWalkingConfig({ url: "https://oap.example:8443/graphql/" });
    expect(c.url).toBe("https://oap.example:8443");
    expect(c.otlpEndpoint).toBe("https://oap.example:11800");
    expect(c.zipkinUrl).toBe("https://oap.example:9412/zipkin");
    const explicit = new SkyWalkingConfig({
      url: "http://a:1",
      otlpEndpoint: "http://collector:4317/",
      zipkinUrl: "http://z:9412/zipkin/",
      otlpProtocol: "http/protobuf",
      otlpHeaders: { "X-Tenant": "t1" },
      traceBackend: "native",
      authentication: new Secret("tok-1234"),
      graphqlPath: "/api/graphql",
    });
    expect(explicit.otlpEndpoint).toBe("http://collector:4317");
    expect(explicit.zipkinUrl).toBe("http://z:9412/zipkin");
    expect(explicit.otlpHeaders).toEqual({ "x-tenant": "t1" });
    expect(explicit.graphqlUrl).toBe("http://a:1/api/graphql");
    expect(explicit.authentication?.reveal()).toBe("tok-1234");
  });

  it("keeps optional common settings", () => {
    const hooks = {};
    const logger = { debug() {}, info() {}, warn() {}, error() {} };
    const c = new SkyWalkingConfig({
      proxy: "http://p:1",
      userAgentSuffix: "s",
      hooks,
      logger,
      authentication: "abcd-token",
      otlpHeaders: { authorization: "Bearer xyz9", "x-plain": "v" },
    });
    expect(c.proxy).toBe("http://p:1");
    expect(c.userAgentSuffix).toBe("s");
    expect(c.hooks).toBe(hooks);
    expect(c.logger).toBe(logger);
    expect(c.knownSecrets()).toEqual(["abcd-token", "Bearer xyz9"]);
  });

  it("validate() raises ConfigError", () => {
    configError(() => new SkyWalkingConfig({ url: "ftp://x" }));
    configError(() => new SkyWalkingConfig({ url: "not a url" }));
    configError(() => new SkyWalkingConfig({ authentication: "   " }));
    configError(() => new SkyWalkingConfig({ graphqlPath: "graphql" }));
    configError(() => new SkyWalkingConfig({ otlpEndpoint: "grpc://x:1" }));
    configError(() => new SkyWalkingConfig({ zipkinUrl: "x" }));
    configError(() => new SkyWalkingConfig({ otlpProtocol: "native" as never }));
    configError(() => new SkyWalkingConfig({ traceBackend: "jaeger" as never }));
  });

  it("core.redaction.config: toString/JSON/inspect hide credentials", () => {
    const c = new SkyWalkingConfig({
      authentication: "super-secret-tok",
      headers: { "x-api-key": "hdr-secret" },
      otlpHeaders: { "x-key": "otlp-secret" },
    });
    for (const form of [String(c), JSON.stringify(c), inspect(c), `${c.authentication}`]) {
      expect(form).not.toContain("super-secret-tok");
      expect(form).not.toContain("hdr-secret");
      expect(form).not.toContain("otlp-secret");
    }
    expect(String(c)).toContain(REDACTED);
    expect(String(c)).toMatch(/^SkyWalkingConfig\(/);
    expect(new SkyWalkingConfig().toJSON().authentication).toBeNull();
    expect(c.toJSON().traceBackend).toBe("auto");
  });

  it("core.config.env-precedence: explicit > GROW_* > vendor > default", () => {
    const env = {
      [`${P}URL`]: "http://grow:1",
      SW_GRAPHQL_URL: "http://vendor:2/graphql",
      [`${P}REQUEST_TIMEOUT`]: "5s",
      [`${P}MAX_RETRIES`]: "7",
      SW_AUTHENTICATION: "vendor-token",
    };
    const fromEnv = SkyWalkingConfig.fromEnv({}, env);
    expect(fromEnv.url).toBe("http://grow:1");
    expect(fromEnv.authentication?.reveal()).toBe("vendor-token");
    expect(fromEnv.requestTimeoutMs).toBe(5000);
    expect(fromEnv.retry.maxRetries).toBe(7);
    expect(SkyWalkingConfig.fromEnv({}, { SW_GRAPHQL_URL: "http://vendor:2/graphql" }).url).toBe(
      "http://vendor:2",
    );
    expect(SkyWalkingConfig.fromEnv({}, { SW_OAP_ADDRESS: "http://oap:12800" }).url).toBe(
      "http://oap:12800",
    );
    expect(
      SkyWalkingConfig.fromEnv(
        {},
        { GROW_SKYWALKING_URL: "http://short:1", SW_OAP_ADDRESS: "http://x:2" },
      ).url,
    ).toBe("http://short:1");
    const explicit = SkyWalkingConfig.fromEnv(
      {
        url: "http://explicit:3",
        authentication: "explicit-token",
        retry: { initialBackoffMs: 1 },
      },
      env,
    );
    expect(explicit.url).toBe("http://explicit:3");
    expect(explicit.authentication?.reveal()).toBe("explicit-token");
    expect(explicit.retry.maxRetries).toBe(7);
    expect(SkyWalkingConfig.fromEnv({}, {}).url).toBe(SkyWalkingConfig.DEFAULT_URL);
    expect(SkyWalkingConfig.fromEnv().url).toBeTypeOf("string");
  });

  it("reads the service variables with their vendor transforms", () => {
    const c = SkyWalkingConfig.fromEnv(
      {},
      {
        SW_AGENT_COLLECTOR_BACKEND_SERVICES: "oap:11800,oap2:11800",
        OTEL_EXPORTER_OTLP_PROTOCOL: "http/protobuf",
        OTEL_EXPORTER_OTLP_HEADERS: "X-Tenant=a%20b, other = 1",
        SW_AGENT_AUTHENTICATION: "agent-token",
        SW_ZIPKIN_ADDRESS: "http://oap:9412",
        GROW_SKYWALKING_TRACE_BACKEND: "zipkin",
        [`${P}GRAPHQL_PATH`]: "/gql",
      },
    );
    expect(c.otlpEndpoint).toBe("http://oap:11800");
    expect(c.otlpProtocol).toBe("http/protobuf");
    expect(c.otlpHeaders).toEqual({ "x-tenant": "a b", other: "1" });
    expect(c.authentication?.reveal()).toBe("agent-token");
    expect(c.zipkinUrl).toBe("http://oap:9412/zipkin");
    expect(c.traceBackend).toBe("zipkin");
    expect(c.graphqlPath).toBe("/gql");
    const grow = SkyWalkingConfig.fromEnv(
      { otlpHeaders: { a: "1" } },
      {
        [`${P}OTLP_ENDPOINT`]: "http://grow-otlp:11800",
        OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: "http://otel:4317",
        [`${P}OTLP_PROTOCOL`]: "grpc",
        [`${P}ZIPKIN_URL`]: "http://z:9412/zipkin",
        [`${P}TRACE_BACKEND`]: "native",
        OTEL_EXPORTER_OTLP_HEADERS: "b=2",
      },
    );
    expect(grow.otlpEndpoint).toBe("http://grow-otlp:11800");
    expect(grow.otlpProtocol).toBe("grpc");
    expect(grow.zipkinUrl).toBe("http://z:9412/zipkin");
    expect(grow.traceBackend).toBe("native");
    expect(grow.otlpHeaders).toEqual({ a: "1" });
    expect(
      SkyWalkingConfig.fromEnv({}, { OTEL_EXPORTER_OTLP_ENDPOINT: "http://otel:4317" })
        .otlpEndpoint,
    ).toBe("http://otel:4317");
  });

  it("invalid env values name the variable, never the value", () => {
    const cases: Record<string, string>[] = [
      { [`${P}TOTAL_TIMEOUT`]: "forever-and-secret" },
      { OTEL_EXPORTER_OTLP_PROTOCOL: "secret-proto" },
      { [`${P}TRACE_BACKEND`]: "secret-backend" },
      { OTEL_EXPORTER_OTLP_HEADERS: "secret-no-equals" },
      { OTEL_EXPORTER_OTLP_HEADERS: "k=%E0%A4%A" },
    ];
    for (const env of cases) {
      const name = Object.keys(env)[0] as string;
      const err = configError(() => SkyWalkingConfig.fromEnv({}, env));
      expect(err.message).toContain(name);
      expect(err.message).not.toContain(env[name]);
    }
  });

  it("helpers", () => {
    expect(baseFromGraphqlUrl("http://a:1/graphql")).toBe("http://a:1");
    expect(baseFromGraphqlUrl("http://a:1/")).toBe("http://a:1");
    expect(collectorToUrl("oap:11800")).toBe("http://oap:11800");
    expect(collectorToUrl("https://oap:11800")).toBe("https://oap:11800");
    expect(zipkinQueryUrl("http://z:9412/")).toBe("http://z:9412/zipkin");
    expect(zipkinQueryUrl("http://z:9412/zipkin")).toBe("http://z:9412/zipkin");
    expect(parseOtlpHeaders("a=1,,B=2")).toEqual({ a: "1", b: "2" });
    expect(() => parseOtlpHeaders("=x")).toThrow(ConfigError);
  });
});
