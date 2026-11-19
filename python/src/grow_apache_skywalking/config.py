"""Configuration for the Apache SkyWalking client.

Service-owned: connection settings for the OAP server (GraphQL/REST on 12800, OTLP gRPC on
11800, Zipkin query API on 9412).
"""

from __future__ import annotations

import dataclasses
import re
from collections.abc import Mapping
from types import MappingProxyType
from typing import Any, ClassVar, Literal, TypeVar
from urllib.parse import unquote, urlsplit

from ._core import REDACTED, BaseConfig, ConfigError, EnvReader, Secret, redact_url
from ._core.redaction import is_sensitive_header, safe_repr

__all__ = [
    "DEFAULT_URL",
    "OTLP_GRPC_PORT",
    "ZIPKIN_QUERY_PORT",
    "VENDOR_ENV",
    "OtlpProtocol",
    "TraceBackend",
    "SkyWalkingConfig",
    "base_from_graphql_url",
    "collector_to_url",
    "parse_otlp_headers",
    "zipkin_query_url",
]

#: Default REST/GraphQL endpoint when neither the config nor the environment sets one.
DEFAULT_URL = "http://127.0.0.1:12800"
#: OAP gRPC port (OTLP receiver).
OTLP_GRPC_PORT = 11800
#: OAP Zipkin query port (``SW_QUERY_ZIPKIN``).
ZIPKIN_QUERY_PORT = 9412

#: Which store the trace query client reads: ``auto``, ``zipkin`` or ``native``.
TraceBackend = Literal["auto", "zipkin", "native"]
#: OTLP protocol of the trace exporter. OAP 10.4 only serves ``grpc``.
OtlpProtocol = Literal["grpc", "http/protobuf"]

_BACKENDS: tuple[str, ...] = ("auto", "zipkin", "native")
_PROTOCOLS: tuple[str, ...] = ("grpc", "http/protobuf")
_SCHEME = re.compile(r"^[a-z][a-z0-9+.-]*://", re.IGNORECASE)
_BAD_PERCENT = re.compile(r"%(?![0-9a-fA-F]{2})")

#: Vendor environment variables, consulted after ``GROW_APACHE_SKYWALKING_<NAME>``, in order.
VENDOR_ENV: Mapping[str, tuple[str, ...]] = MappingProxyType(
    {
        "URL": ("GROW_SKYWALKING_URL", "SW_GRAPHQL_URL", "SW_OAP_ADDRESS"),
        "CA_CERT": ("GROW_SKYWALKING_CA_CERT",),
        "CLIENT_CERT": ("GROW_SKYWALKING_CLIENT_CERT",),
        "CLIENT_KEY": ("GROW_SKYWALKING_CLIENT_KEY",),
        "INSECURE_SKIP_VERIFY": ("GROW_SKYWALKING_INSECURE_SKIP_VERIFY",),
        "PROXY": ("GROW_SKYWALKING_PROXY",),
        "OTLP_ENDPOINT": (
            "GROW_SKYWALKING_OTLP_ENDPOINT",
            "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
            "OTEL_EXPORTER_OTLP_ENDPOINT",
            "SW_AGENT_COLLECTOR_BACKEND_SERVICES",
        ),
        "OTLP_PROTOCOL": (
            "GROW_SKYWALKING_OTLP_PROTOCOL",
            "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL",
            "OTEL_EXPORTER_OTLP_PROTOCOL",
        ),
        "OTLP_HEADERS": ("OTEL_EXPORTER_OTLP_TRACES_HEADERS", "OTEL_EXPORTER_OTLP_HEADERS"),
        "AUTHENTICATION": (
            "GROW_SKYWALKING_AUTHENTICATION",
            "SW_AUTHENTICATION",
            "SW_AGENT_AUTHENTICATION",
        ),
        "ZIPKIN_URL": ("GROW_SKYWALKING_ZIPKIN_URL", "SW_ZIPKIN_ADDRESS"),
        "TRACE_BACKEND": ("GROW_SKYWALKING_TRACE_BACKEND",),
    }
)

_C = TypeVar("_C", bound="SkyWalkingConfig")


def base_from_graphql_url(url: str) -> str:
    """Strip a trailing ``/graphql`` (with or without slash) from a GraphQL URL."""
    base = url.rstrip("/")
    if base.lower().endswith("/graphql"):
        base = base[: -len("/graphql")]
    return base.rstrip("/")


def collector_to_url(value: str) -> str:
    """``host:port[,host:port]`` (SkyWalking agent collector address) → ``http://host:port``."""
    first = value.split(",", maxsplit=1)[0].strip()
    return first if _SCHEME.match(first) else f"http://{first}"


def zipkin_query_url(value: str) -> str:
    """A Zipkin address with or without the ``/zipkin`` path → ``<address>/zipkin``."""
    base = value.rstrip("/")
    return base if base.lower().endswith("/zipkin") else f"{base}/zipkin"


def parse_otlp_headers(raw: str, source: str = "headers") -> dict[str, str]:
    """Parse OTEL-style headers ``k1=v1,k2=v2`` (keys lower-cased, values percent-decoded).

    Raises :class:`ConfigError` naming ``source`` (never the value) for a malformed entry.
    """
    out: dict[str, str] = {}
    for part in raw.split(","):
        if not part.strip():
            continue
        key, sep, value = part.partition("=")
        if not sep or not key.strip():
            raise ConfigError(f"config: invalid value in {source}: expected key=value pairs")
        value = value.strip()
        if _BAD_PERCENT.search(value):
            raise ConfigError(f"config: invalid value in {source}: bad percent-encoding")
        out[key.strip().lower()] = unquote(value)
    return out


def _host_url(url: str, port: int, path: str = "") -> str:
    try:
        parts = urlsplit(url)
        host = parts.hostname
    except ValueError:
        return ""
    if not host or parts.scheme not in ("http", "https"):
        return ""
    if ":" in host:
        host = f"[{host}]"
    return f"{parts.scheme}://{host}:{port}{path}"


def _check_http_url(value: str, field: str) -> None:
    try:
        parts = urlsplit(value)
        ok = parts.scheme in ("http", "https") and bool(parts.hostname)
    except ValueError:
        ok = False
    if not ok:
        raise ConfigError(f"config: {field} must be an http(s) URL")


@dataclasses.dataclass(frozen=True, repr=False)
class SkyWalkingConfig(BaseConfig):
    """Immutable settings for :class:`~grow_apache_skywalking.SkyWalkingClient`.

    Common fields (``url``, timeouts, ``retry``, ``tls``, ``proxy``, ``headers``,
    ``user_agent_suffix``, ``hooks``, ``logger``) come from :class:`BaseConfig`. Durations are
    float seconds; ``datetime.timedelta`` is accepted.

    Service fields:

    * ``url`` - OAP REST/GraphQL base (port 12800), default ``http://127.0.0.1:12800``; a
      trailing ``/graphql`` is stripped.
    * ``graphql_path`` - GraphQL path below ``url`` (default ``/graphql``).
    * ``otlp_endpoint`` - OTLP endpoint for :func:`init_tracing` (default
      ``http://<url host>:11800``, the OAP gRPC receiver).
    * ``otlp_protocol`` - ``grpc`` (default; OAP 10.4 has no OTLP/HTTP receiver) or
      ``http/protobuf`` (only for an OpenTelemetry Collector in front of OAP).
    * ``otlp_headers`` - extra OTLP exporter headers (gRPC metadata), keys lower-cased.
    * ``zipkin_url`` - Zipkin v2 query API base (default ``http://<url host>:9412/zipkin``).
    * ``authentication`` - OAP gRPC token (``SW_AUTHENTICATION``), a :class:`Secret`, sent as
      the ``authentication`` metadata on OTLP exports. OAP does not protect GraphQL or
      ``/v3/*`` with it.
    * ``trace_backend`` - trace store read by ``client.traces``: ``zipkin`` (OTLP spans),
      ``native`` (SkyWalking agent segments) or ``auto`` (default).

    Environment (:meth:`from_env`): explicit keyword > ``GROW_APACHE_SKYWALKING_<NAME>`` >
    vendor variables (:data:`VENDOR_ENV`, in order) > default. Names: ``URL``,
    ``GRAPHQL_PATH``, ``OTLP_ENDPOINT``, ``OTLP_PROTOCOL``, ``OTLP_HEADERS``,
    ``AUTHENTICATION``, ``ZIPKIN_URL``, ``TRACE_BACKEND`` plus the common ``CONNECT_TIMEOUT``,
    ``REQUEST_TIMEOUT``, ``TOTAL_TIMEOUT``, ``MAX_RETRIES``, ``CA_CERT``, ``CLIENT_CERT``,
    ``CLIENT_KEY``, ``INSECURE_SKIP_VERIFY``, ``PROXY``. ``SW_GRAPHQL_URL`` loses a trailing
    ``/graphql``, ``SW_AGENT_COLLECTOR_BACKEND_SERVICES`` (``host:port``) becomes
    ``http://host:port`` and ``SW_ZIPKIN_ADDRESS`` gains ``/zipkin``.
    """

    ENV_VENDOR: ClassVar[Mapping[str, tuple[str, ...]]] = VENDOR_ENV

    url: str = DEFAULT_URL
    graphql_path: str = "/graphql"
    otlp_endpoint: str = dataclasses.field(default="", repr=False)
    otlp_protocol: OtlpProtocol = "grpc"
    otlp_headers: Mapping[str, str] = dataclasses.field(default_factory=dict, repr=False)
    zipkin_url: str = dataclasses.field(default="", repr=False)
    authentication: Secret | None = None
    trace_backend: TraceBackend = "auto"

    def __post_init__(self) -> None:
        super().__post_init__()
        object.__setattr__(self, "url", base_from_graphql_url(self.url))
        endpoint = self.otlp_endpoint or _host_url(self.url, OTLP_GRPC_PORT)
        object.__setattr__(self, "otlp_endpoint", endpoint.rstrip("/"))
        zipkin = self.zipkin_url or _host_url(self.url, ZIPKIN_QUERY_PORT, "/zipkin")
        object.__setattr__(self, "zipkin_url", zipkin.rstrip("/"))
        headers = {str(k).lower(): str(v) for k, v in dict(self.otlp_headers).items()}
        object.__setattr__(self, "otlp_headers", MappingProxyType(headers))

    @property
    def graphql_url(self) -> str:
        """Absolute GraphQL URL (``url`` + ``graphql_path``)."""
        return self.url.rstrip("/") + self.graphql_path

    def known_secrets(self) -> list[str]:
        """Credential values to redact: ``authentication`` and sensitive OTLP headers."""
        out = super().known_secrets()
        out.extend(v for k, v in self.otlp_headers.items() if is_sensitive_header(k) and v)
        return out

    def __repr__(self) -> str:
        base = safe_repr(self)[:-1]
        extras = (
            f"otlp_endpoint={redact_url(self.otlp_endpoint)!r}",
            f"otlp_headers={dict.fromkeys(self.otlp_headers, REDACTED)!r}",
            f"zipkin_url={redact_url(self.zipkin_url)!r}",
        )
        return base + ", " + ", ".join(extras) + ")"

    __str__ = __repr__

    def validate(self: _C) -> _C:
        """Common checks plus service rules; raises :class:`ConfigError`. Returns ``self``."""
        super().validate()
        if not self.graphql_path.startswith("/"):
            raise ConfigError("config: graphql_path must start with /")
        _check_http_url(self.otlp_endpoint, "otlp_endpoint")
        _check_http_url(self.zipkin_url, "zipkin_url")
        if self.otlp_protocol not in _PROTOCOLS:
            raise ConfigError("config: otlp_protocol must be grpc or http/protobuf")
        if self.trace_backend not in _BACKENDS:
            raise ConfigError("config: trace_backend must be auto, zipkin or native")
        if self.authentication is not None and not self.authentication.reveal().strip():
            raise ConfigError("config: authentication must not be blank")
        return self

    @classmethod
    def _env_values(cls, reader: EnvReader) -> dict[str, Any]:
        values = super()._env_values(reader)
        vendor = cls.ENV_VENDOR
        otlp = reader.lookup("OTLP_ENDPOINT", vendor.get("OTLP_ENDPOINT", ()))
        if otlp is not None:
            name, raw = otlp
            values["otlp_endpoint"] = (
                collector_to_url(raw) if name == "SW_AGENT_COLLECTOR_BACKEND_SERVICES" else raw
            )
        protocol = reader.lookup("OTLP_PROTOCOL", vendor.get("OTLP_PROTOCOL", ()))
        if protocol is not None:
            if protocol[1] not in _PROTOCOLS:
                raise ConfigError(f"config: {protocol[0]} must be grpc or http/protobuf")
            values["otlp_protocol"] = protocol[1]
        headers = reader.lookup("OTLP_HEADERS", vendor.get("OTLP_HEADERS", ()))
        if headers is not None:
            values["otlp_headers"] = parse_otlp_headers(
                headers[1], f"environment variable {headers[0]}"
            )
        auth = reader.string("AUTHENTICATION", vendor.get("AUTHENTICATION", ()))
        if auth is not None:
            values["authentication"] = Secret(auth)
        zipkin = reader.string("ZIPKIN_URL", vendor.get("ZIPKIN_URL", ()))
        if zipkin is not None:
            values["zipkin_url"] = zipkin_query_url(zipkin)
        backend = reader.lookup("TRACE_BACKEND", vendor.get("TRACE_BACKEND", ()))
        if backend is not None:
            if backend[1] not in _BACKENDS:
                raise ConfigError(f"config: {backend[0]} must be auto, zipkin or native")
            values["trace_backend"] = backend[1]
        graphql_path = reader.string("GRAPHQL_PATH")
        if graphql_path is not None:
            values["graphql_path"] = graphql_path
        return values
