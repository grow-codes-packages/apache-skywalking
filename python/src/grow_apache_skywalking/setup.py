"""``init_tracing``: OpenTelemetry tracing exporting OTLP/gRPC to the OAP receiver
(service-owned)."""

from __future__ import annotations

import dataclasses
from collections.abc import Mapping, Sequence
from typing import Any

from opentelemetry.sdk.trace import SpanProcessor
from opentelemetry.sdk.trace.export import SpanExporter
from opentelemetry.sdk.trace.id_generator import IdGenerator

from ._core import ConfigError, TlsConfig, user_agent
from ._tracing import PropagatorFactory, TracingConfig, TracingHandle, init_tracing_with
from .config import OTLP_GRPC_PORT, SkyWalkingConfig
from .sw8 import Sw8Propagator

__all__ = [
    "AUTHENTICATION_HEADER",
    "init_tracing",
    "otlp_traces_url",
    "skywalking_exporter_factory",
    "skywalking_tracing_defaults",
]

#: gRPC metadata key OAP reads the token from (``SW_AUTHENTICATION``).
AUTHENTICATION_HEADER = "authentication"

_TEMPLATE_DEFAULTS = TracingConfig(service_name="-")


def skywalking_tracing_defaults(config: SkyWalkingConfig) -> dict[str, Any]:
    """Service defaults for :class:`TracingConfig`: ``protocol``/``endpoint`` from
    ``otlp_protocol``/``otlp_endpoint``, ``headers`` from ``otlp_headers`` plus the
    ``authentication`` token, ``tls`` from the client TLS settings."""
    headers = dict(config.otlp_headers)
    if config.authentication is not None:
        headers[AUTHENTICATION_HEADER] = config.authentication.reveal()
    return {
        "protocol": config.otlp_protocol,
        "endpoint": config.otlp_endpoint,
        "headers": headers,
        "tls": config.tls,
    }


def otlp_traces_url(endpoint: str) -> str:
    """``<endpoint>/v1/traces`` unless the endpoint already names that path."""
    base = endpoint.rstrip("/")
    return base if base.endswith("/v1/traces") else f"{base}/v1/traces"


def _read(path: str | None) -> bytes | None:
    if path is None:
        return None
    try:
        with open(path, "rb") as fh:
            return fh.read()
    except OSError:
        raise ConfigError("tracing: cannot read a TLS file (check the configured path)") from None


def _grpc_exporter(config: TracingConfig, endpoint: str) -> SpanExporter:
    import grpc  # noqa: PLC0415 - heavy import, only for the gRPC exporter
    from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import (  # noqa: PLC0415
        OTLPSpanExporter,
    )

    tls = config.tls or TlsConfig()
    if tls.insecure_skip_verify and endpoint.startswith("https:"):
        raise ConfigError("tracing: insecure_skip_verify is not supported by the gRPC exporter")
    ca = _read(tls.ca_cert_path) or (tls.ca_cert_pem.encode() if tls.ca_cert_pem else None)
    cert = _read(tls.client_cert_path)
    key = _read(tls.client_key_path)
    secure = endpoint.startswith("https:")
    credentials = (
        grpc.ssl_channel_credentials(root_certificates=ca, private_key=key, certificate_chain=cert)
        if secure
        else None
    )
    headers = {k.lower(): v for k, v in config.headers.items()}
    return OTLPSpanExporter(
        endpoint=endpoint,
        insecure=not secure,
        credentials=credentials,
        headers=headers,
        timeout=config.batch.export_timeout_s,
        compression=grpc.Compression.Gzip
        if config.compression == "gzip"
        else grpc.Compression.NoCompression,
    )


def _http_exporter(config: TracingConfig, endpoint: str) -> SpanExporter:
    import requests  # noqa: PLC0415 - dependency of the OTLP/HTTP exporter
    from opentelemetry.exporter.otlp.proto.http import Compression  # noqa: PLC0415
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import (  # noqa: PLC0415
        OTLPSpanExporter,
    )

    tls = config.tls or TlsConfig()
    session = requests.Session()
    session.headers["User-Agent"] = user_agent()
    if tls.insecure_skip_verify:
        session.verify = False
    return OTLPSpanExporter(
        endpoint=otlp_traces_url(endpoint),
        certificate_file=tls.ca_cert_path,
        client_key_file=tls.client_key_path,
        client_certificate_file=tls.client_cert_path,
        headers=dict(config.headers),
        timeout=config.batch.export_timeout_s,
        compression=Compression.Gzip if config.compression == "gzip" else Compression.NoCompression,
        session=session,
    )


def skywalking_exporter_factory(config: TracingConfig) -> SpanExporter:
    """The span exporter for a resolved config (the exporter factory of
    :func:`init_tracing_with`).

    ``grpc`` → OTLP/gRPC to the OAP receiver (headers become gRPC metadata; ``https``
    endpoints use the TLS settings); ``http/protobuf`` → OTLP/HTTP protobuf to
    ``<endpoint>/v1/traces`` (only for an OpenTelemetry Collector in front of OAP - OAP 10.4
    serves no OTLP/HTTP). ``native`` raises :class:`ConfigError`: use the official SkyWalking
    agents.
    """
    if config.protocol == "native":
        raise ConfigError(
            "tracing: the SkyWalking native segment protocol is not supported; use grpc (OTLP) "
            "or the official SkyWalking agents"
        )
    endpoint = config.endpoint or f"http://127.0.0.1:{OTLP_GRPC_PORT}"
    if config.protocol == "http/protobuf":
        return _http_exporter(config, endpoint)
    return _grpc_exporter(config, endpoint)


def _resolve(
    config: TracingConfig | None, sw: SkyWalkingConfig, overrides: Mapping[str, Any]
) -> TracingConfig:
    defaults = skywalking_tracing_defaults(sw)
    explicit: dict[str, Any] = {}
    if config is not None:
        explicit = {f.name: getattr(config, f.name) for f in dataclasses.fields(TracingConfig)}
        # Fields left at the template default take the SkyWalking default instead.
        if config.protocol == _TEMPLATE_DEFAULTS.protocol:
            explicit.pop("protocol")
        for name in ("endpoint", "tls"):
            if explicit[name] is None:
                explicit.pop(name)
    explicit.update({k: v for k, v in overrides.items() if v is not None})
    headers = {**defaults["headers"], **dict(explicit.pop("headers", None) or {})}
    values = {**defaults, **explicit, "headers": headers}
    if "service_name" not in values:
        raise ConfigError("tracing service_name is required")
    return TracingConfig(**values)


def init_tracing(
    config: TracingConfig | None = None,
    *,
    skywalking: SkyWalkingConfig | None = None,
    propagator_factories: Mapping[str, PropagatorFactory] | None = None,
    span_processors: Sequence[SpanProcessor] = (),
    id_generator: IdGenerator | None = None,
    **overrides: Any,
) -> TracingHandle:
    """Set up OpenTelemetry tracing that exports OTLP/gRPC to SkyWalking OAP (SPEC §5).

    Pass a :class:`TracingConfig` and/or its fields as keywords (``service_name="checkout"``).
    Endpoint, protocol, headers and token come from ``skywalking`` (default
    :meth:`SkyWalkingConfig.from_env`): a ``TracingConfig`` field overrides them when it is set
    (``endpoint``/``tls`` not ``None``, ``protocol`` different from the template default
    ``http/protobuf``) and keyword overrides always win; headers are merged. The ``sw8``
    propagator name is available (extract by default; see :class:`Sw8Propagator`). Spans are
    readable with ``client.traces`` (Zipkin query API) about 5-15 s after export.

    Raises :class:`ConfigError` for invalid settings.
    """
    sw = skywalking if skywalking is not None else SkyWalkingConfig.from_env()
    effective = _resolve(config, sw, overrides)
    factories: dict[str, PropagatorFactory] = {"sw8": Sw8Propagator}
    factories.update(propagator_factories or {})
    return init_tracing_with(
        effective,
        skywalking_exporter_factory,
        propagator_factories=factories,
        span_processors=span_processors,
        id_generator=id_generator,
    )
