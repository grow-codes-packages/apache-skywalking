"""init_tracing: SkyWalking defaults, exporter factory, a real OTLP/gRPC export to a fake OAP."""

from __future__ import annotations

import threading
from collections.abc import Iterator
from concurrent import futures
from pathlib import Path
from typing import Any

import grpc
import pytest
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import (
    OTLPSpanExporter as GrpcExporter,
)
from opentelemetry.exporter.otlp.proto.http.trace_exporter import (
    OTLPSpanExporter as HttpExporter,
)
from opentelemetry.proto.collector.trace.v1 import trace_service_pb2 as pb

import grow_apache_skywalking as sdk


class FakeOapReceiver:
    """In-process gRPC server implementing ``TraceService/Export`` like OAP's receiver."""

    def __init__(self, token: str | None = None) -> None:
        self.token = token
        self.requests: list[pb.ExportTraceServiceRequest] = []
        self.metadata: list[dict[str, str]] = []
        self.received = threading.Event()
        self.server = grpc.server(futures.ThreadPoolExecutor(max_workers=2))
        handler = grpc.method_handlers_generic_handler(
            "opentelemetry.proto.collector.trace.v1.TraceService",
            {
                "Export": grpc.unary_unary_rpc_method_handler(
                    self._export,
                    request_deserializer=pb.ExportTraceServiceRequest.FromString,
                    response_serializer=pb.ExportTraceServiceResponse.SerializeToString,
                )
            },
        )
        self.server.add_generic_rpc_handlers((handler,))
        self.port = self.server.add_insecure_port("127.0.0.1:0")
        self.server.start()

    def _export(self, request: Any, context: grpc.ServicerContext) -> Any:
        md = dict(context.invocation_metadata())
        self.metadata.append(md)
        if self.token is not None and md.get("authentication") != self.token:
            context.abort(grpc.StatusCode.PERMISSION_DENIED, "bad token")
        self.requests.append(request)
        self.received.set()
        return pb.ExportTraceServiceResponse()

    @property
    def endpoint(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def stop(self) -> None:
        self.server.stop(0)


@pytest.fixture
def receiver() -> Iterator[FakeOapReceiver]:
    srv = FakeOapReceiver(token="oap-token-1")
    yield srv
    srv.stop()


def test_defaults_from_skywalking_config() -> None:
    sw = sdk.SkyWalkingConfig(
        url="http://oap:12800", authentication="tok-1", otlp_headers={"X-Tenant": "t"}
    )
    assert sdk.skywalking_tracing_defaults(sw) == {
        "protocol": "grpc",
        "endpoint": "http://oap:11800",
        "headers": {"x-tenant": "t", "authentication": "tok-1"},
        "tls": sw.tls,
    }
    assert sdk.otlp_traces_url("http://c:4318/") == "http://c:4318/v1/traces"
    assert sdk.otlp_traces_url("http://c:4318/v1/traces") == "http://c:4318/v1/traces"


def test_init_tracing_exports_otlp_grpc_with_token(receiver: FakeOapReceiver) -> None:
    sw = sdk.SkyWalkingConfig(otlp_endpoint=receiver.endpoint, authentication="oap-token-1")
    handle = sdk.init_tracing(
        sdk.TracingConfig(
            service_name="checkout",
            set_global=False,
            headers={"x-extra": "1"},
            propagators=("tracecontext", "baggage", "sw8"),
        ),
        skywalking=sw,
    )
    assert handle.config.protocol == "grpc"
    assert handle.config.endpoint == receiver.endpoint
    assert handle.config.headers == {"authentication": "oap-token-1", "x-extra": "1"}
    with handle.tracer("t").start_as_current_span("GET /checkout"):
        pass
    handle.force_flush(10)
    handle.shutdown()
    assert receiver.received.wait(5)
    spans = receiver.requests[0].resource_spans[0]
    assert spans.scope_spans[0].spans[0].name == "GET /checkout"
    attrs = {a.key: a.value.string_value for a in spans.resource.attributes}
    assert attrs["service.name"] == "checkout"
    md = receiver.metadata[0]
    assert md["authentication"] == "oap-token-1" and md["x-extra"] == "1"
    assert md["user-agent"].startswith("OTel-OTLP-Exporter-Python")


def test_wrong_token_is_rejected_by_receiver(receiver: FakeOapReceiver) -> None:
    exporter = sdk.skywalking_exporter_factory(
        sdk.TracingConfig(
            service_name="x",
            protocol="grpc",
            endpoint=receiver.endpoint,
            headers={"authentication": "wrong"},
            compression="none",
        )
    )
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import SimpleSpanProcessor, SpanExportResult

    provider = TracerProvider()
    results: list[SpanExportResult] = []

    class Recording(SimpleSpanProcessor):
        def on_end(self, span: Any) -> None:
            results.append(exporter.export([span]))

    provider.add_span_processor(Recording(exporter))
    provider.get_tracer("t").start_span("x").end()
    assert results == [SpanExportResult.FAILURE]
    assert receiver.metadata and not receiver.requests
    exporter.shutdown()


def test_resolution_rules(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("GROW_APACHE_SKYWALKING_URL", "http://env-oap:12800")
    handle = sdk.init_tracing(service_name="svc", set_global=False)
    assert handle.config.endpoint == "http://env-oap:11800"
    assert handle.config.protocol == "grpc"
    handle.shutdown()
    sw = sdk.SkyWalkingConfig(otlp_protocol="http/protobuf", otlp_endpoint="http://col:4318")
    handle = sdk.init_tracing(service_name="svc", skywalking=sw, set_global=False)
    assert handle.config.protocol == "http/protobuf"
    handle.shutdown()
    explicit = sdk.init_tracing(
        sdk.TracingConfig(service_name="svc", protocol="grpc", endpoint="http://e:1"),
        skywalking=sw,
        set_global=False,
        protocol=None,
    )
    assert explicit.config.protocol == "grpc" and explicit.config.endpoint == "http://e:1"
    explicit.shutdown()
    with pytest.raises(sdk.ConfigError, match="service_name"):
        sdk.init_tracing(skywalking=sw)
    with pytest.raises(sdk.ConfigError, match="native"):
        sdk.init_tracing(service_name="svc", protocol="native", skywalking=sw, set_global=False)
    custom = sdk.init_tracing(
        service_name="svc",
        skywalking=sw,
        set_global=False,
        propagators=("sw8",),
        propagator_factories={"sw8": sdk.Sw8Propagator},
    )
    assert custom.propagator.fields == {"sw8", "sw8-correlation"}
    custom.shutdown()


def test_exporter_factory_variants(tmp_path: Path) -> None:
    http = sdk.skywalking_exporter_factory(
        sdk.TracingConfig(
            service_name="x",
            protocol="http/protobuf",
            endpoint="http://col:4318",
            tls=sdk.TlsConfig(insecure_skip_verify=True),
        )
    )
    assert isinstance(http, HttpExporter)
    assert http._endpoint == "http://col:4318/v1/traces"
    http.shutdown()
    plain = sdk.skywalking_exporter_factory(sdk.TracingConfig(service_name="x", protocol="grpc"))
    assert isinstance(plain, GrpcExporter)
    plain.shutdown()
    ca = tmp_path / "ca.pem"
    ca.write_text("-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n")
    secure = sdk.skywalking_exporter_factory(
        sdk.TracingConfig(
            service_name="x",
            protocol="grpc",
            endpoint="https://oap:11800",
            tls=sdk.TlsConfig(ca_cert_path=str(ca)),
        )
    )
    assert isinstance(secure, GrpcExporter)
    secure.shutdown()
    pem = sdk.skywalking_exporter_factory(
        sdk.TracingConfig(
            service_name="x",
            protocol="grpc",
            endpoint="https://oap:11800",
            tls=sdk.TlsConfig(ca_cert_pem=ca.read_text()),
        )
    )
    pem.shutdown()
    with pytest.raises(sdk.ConfigError, match="TLS file"):
        sdk.skywalking_exporter_factory(
            sdk.TracingConfig(
                service_name="x",
                protocol="grpc",
                endpoint="https://oap:11800",
                tls=sdk.TlsConfig(ca_cert_path=str(tmp_path / "missing.pem")),
            )
        )
    with pytest.raises(sdk.ConfigError, match="insecure_skip_verify"):
        sdk.skywalking_exporter_factory(
            sdk.TracingConfig(
                service_name="x",
                protocol="grpc",
                endpoint="https://oap:11800",
                tls=sdk.TlsConfig(insecure_skip_verify=True),
            )
        )
