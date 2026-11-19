import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  Metadata,
  Server as GrpcServer,
  ServerCredentials,
  type ServiceError,
} from "@grpc/grpc-js";
import { afterEach, describe, expect, it } from "vitest";
import {
  AUTHENTICATION_HEADER,
  ConfigError,
  SkyWalkingConfig,
  createSkyWalkingSpanExporter,
  initTracing,
  otlpTracesUrl,
  resolveTracingConfig,
  skywalkingTracingDefaults,
} from "../src/index.js";

const TRACE_METHOD = "/opentelemetry.proto.collector.trace.v1.TraceService/Export";
const cleanups: (() => Promise<void> | void)[] = [];
afterEach(async () => {
  for (const fn of cleanups.splice(0).reverse()) await fn();
});

async function grpcServer(): Promise<{
  url: string;
  calls: { body: Buffer; metadata: Metadata }[];
}> {
  const calls: { body: Buffer; metadata: Metadata }[] = [];
  const server = new GrpcServer();
  const id = (v: Buffer): Buffer => v;
  server.addService(
    {
      Export: {
        path: TRACE_METHOD,
        requestStream: false,
        responseStream: false,
        requestSerialize: id,
        requestDeserialize: id,
        responseSerialize: id,
        responseDeserialize: id,
      },
    },
    {
      Export: (
        call: { request: Buffer; metadata: Metadata },
        callback: (e: ServiceError | null, v?: Buffer) => void,
      ) => {
        calls.push({ body: call.request, metadata: call.metadata });
        callback(null, Buffer.alloc(0));
      },
    } as never,
  );
  const port = await new Promise<number>((resolve, reject) =>
    server.bindAsync("127.0.0.1:0", ServerCredentials.createInsecure(), (err, p) =>
      err ? reject(err) : resolve(p),
    ),
  );
  cleanups.push(() => new Promise<void>((resolve) => server.tryShutdown(() => resolve())));
  return { url: `http://127.0.0.1:${port}`, calls };
}

describe("initTracing (OTLP/gRPC to OAP)", () => {
  it("exports spans with the authentication metadata", async () => {
    const grpc = await grpcServer();
    const handle = initTracing({
      serviceName: "sw-test",
      setGlobal: false,
      propagators: ["tracecontext", "sw8"],
      skywalking: {
        otlpEndpoint: grpc.url,
        authentication: "oap-token-1",
        otlpHeaders: { "x-tenant": "t" },
      },
    });
    expect(handle.config.protocol).toBe("grpc");
    expect(handle.config.endpoint).toBe(grpc.url);
    expect(String(handle.config)).not.toContain("oap-token-1");
    const span = handle.tracer("t").startSpan("GET /checkout");
    span.end();
    await handle.forceFlush(10_000);
    expect(grpc.calls).toHaveLength(1);
    expect(grpc.calls[0]?.metadata.get(AUTHENTICATION_HEADER)).toEqual(["oap-token-1"]);
    expect(grpc.calls[0]?.metadata.get("x-tenant")).toEqual(["t"]);
    expect(grpc.calls[0]?.body.toString("latin1")).toContain("GET /checkout");
    expect(handle.propagator.fields()).toContain("sw8");
    await handle.shutdown();
  });

  it("http/protobuf posts to <endpoint>/v1/traces (collector setups)", async () => {
    const urls: string[] = [];
    const server = createServer((req, res) => {
      urls.push(req.url ?? "");
      req.resume();
      req.on("end", () => {
        res.writeHead(200, { "content-type": "application/x-protobuf" });
        res.end();
      });
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    cleanups.push(() => new Promise<void>((resolve) => server.close(() => resolve())));
    const port = (server.address() as AddressInfo).port;
    const handle = initTracing({
      serviceName: "sw-http",
      setGlobal: false,
      compression: "none",
      skywalking: new SkyWalkingConfig({
        otlpProtocol: "http/protobuf",
        otlpEndpoint: `http://127.0.0.1:${port}`,
      }),
    });
    handle.tracer("t").startSpan("x").end();
    await handle.forceFlush(10_000);
    await handle.shutdown();
    expect(urls).toEqual(["/v1/traces"]);
  });

  it("defaults come from SkyWalkingConfig.fromEnv(); TracingConfig fields win", async () => {
    const key = "GROW_APACHE_SKYWALKING_OTLP_ENDPOINT";
    process.env[key] = "http://env-oap:11800";
    try {
      const handle = initTracing({ serviceName: "e", setGlobal: false });
      expect(handle.config.endpoint).toBe("http://env-oap:11800");
      await handle.shutdown();
      const override = initTracing(
        { serviceName: "e", setGlobal: false, endpoint: "http://other:1" },
        { defaults: { samplerRatio: 0.5 } },
      );
      expect(override.config.endpoint).toBe("http://other:1");
      expect(override.config.samplerRatio).toBe(0.5);
      await override.shutdown();
    } finally {
      delete process.env[key];
    }
    expect(skywalkingTracingDefaults(new SkyWalkingConfig())).toEqual({
      protocol: "grpc",
      endpoint: "http://127.0.0.1:11800",
      headers: {},
      tls: {},
    });
  });

  it("builds exporters for every TLS / protocol combination", async () => {
    const dir = mkdtempSync(join(tmpdir(), "sw-tls-"));
    cleanups.push(() => rmSync(dir, { recursive: true, force: true }));
    const pem = "-----BEGIN CERTIFICATE-----\n-----END CERTIFICATE-----";
    writeFileSync(join(dir, "ca.pem"), pem);
    const base = { serviceName: "x" };
    const grpcTls = createSkyWalkingSpanExporter(
      resolveTracingConfig({
        ...base,
        protocol: "grpc",
        endpoint: "https://oap.example:11800",
        tls: { caCertPath: join(dir, "ca.pem") },
      }),
    );
    await grpcTls.shutdown();
    const grpcPlainHttps = createSkyWalkingSpanExporter(
      resolveTracingConfig({ ...base, protocol: "grpc", endpoint: "https://oap.example:11800" }),
    );
    await grpcPlainHttps.shutdown();
    const grpcDefault = createSkyWalkingSpanExporter(
      resolveTracingConfig({ ...base, protocol: "grpc" }),
    );
    await grpcDefault.shutdown();
    const http = createSkyWalkingSpanExporter(
      resolveTracingConfig({
        ...base,
        protocol: "http/protobuf",
        endpoint: "https://collector:4318/v1/traces",
        tls: {
          insecureSkipVerify: true,
          caCertPem: pem,
          clientCertPath: join(dir, "ca.pem"),
          clientKeyPath: join(dir, "ca.pem"),
        },
      }),
    );
    await http.shutdown();
    expect(() =>
      createSkyWalkingSpanExporter(resolveTracingConfig({ ...base, protocol: "native" })),
    ).toThrow(ConfigError);
    expect(otlpTracesUrl("http://c:4318/")).toBe("http://c:4318/v1/traces");
    expect(otlpTracesUrl("http://c:4318/v1/traces")).toBe("http://c:4318/v1/traces");
  });
});
