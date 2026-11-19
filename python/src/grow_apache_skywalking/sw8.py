"""SkyWalking cross-process propagation headers ``sw8`` (v3) and ``sw8-correlation``
(service-owned)."""

from __future__ import annotations

import base64
import binascii
import dataclasses
import re
from collections.abc import Mapping
from typing import Any

from opentelemetry import baggage as otel_baggage
from opentelemetry.context import Context, create_key, get_value, set_value
from opentelemetry.propagators.textmap import (
    CarrierT,
    Getter,
    Setter,
    TextMapPropagator,
    default_getter,
    default_setter,
)

__all__ = [
    "SW8_CORRELATION_HEADER",
    "SW8_HEADER",
    "Sw8Context",
    "Sw8InjectContext",
    "Sw8Propagator",
    "decode_sw8",
    "decode_sw8_correlation",
    "encode_sw8",
    "encode_sw8_correlation",
    "get_sw8_context",
    "sw8_attributes",
    "with_sw8_context",
]

#: Header name of the SkyWalking v3 trace context.
SW8_HEADER = "sw8"
#: Header name of the SkyWalking correlation context.
SW8_CORRELATION_HEADER = "sw8-correlation"

_B64 = re.compile(r"^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")
_INT = re.compile(r"^-?\d+$")
_EXTRACTED_KEY = create_key("grow-codes skywalking sw8 extracted")
_INJECT_KEY = create_key("grow-codes skywalking sw8 inject")


@dataclasses.dataclass(frozen=True)
class Sw8Context:
    """Decoded ``sw8`` header (SkyWalking cross-process propagation v3)."""

    #: Whether the upstream sampled the trace.
    sample: bool
    #: Native trace id (opaque string, e.g. ``<hex>.<n>.<ms>``).
    trace_id: str
    #: Segment id of the parent (calling) segment.
    parent_trace_segment_id: str
    #: Span id of the exit span in the parent segment.
    parent_span_id: int
    parent_service: str
    parent_service_instance: str
    #: Endpoint (operation) of the parent's entry span.
    parent_endpoint: str
    #: Address the caller used to reach this service (``host:port``).
    network_address_used_at_peer: str


@dataclasses.dataclass(frozen=True)
class Sw8InjectContext(Sw8Context):
    """:class:`Sw8Context` plus an optional correlation map (written as ``sw8-correlation``)."""

    correlation: Mapping[str, str] = dataclasses.field(default_factory=dict)


def _enc(text: str) -> str:
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


def _dec(text: str) -> str | None:
    if not _B64.match(text):
        return None
    try:
        return base64.b64decode(text, validate=True).decode("utf-8")
    except (binascii.Error, UnicodeDecodeError):
        return None


def encode_sw8(ctx: Sw8Context) -> str:
    """Encode an ``sw8`` value: ``sample-b64(traceId)-b64(segment)-spanId-b64(service)-
    b64(instance)-b64(endpoint)-b64(peer)``."""
    return "-".join(
        [
            "1" if ctx.sample else "0",
            _enc(ctx.trace_id),
            _enc(ctx.parent_trace_segment_id),
            str(int(ctx.parent_span_id)),
            _enc(ctx.parent_service),
            _enc(ctx.parent_service_instance),
            _enc(ctx.parent_endpoint),
            _enc(ctx.network_address_used_at_peer),
        ]
    )


def decode_sw8(header: str | None) -> Sw8Context | None:
    """Decode an ``sw8`` value; ``None`` when malformed (wrong field count, bad Base64 …)."""
    parts = (header or "").strip().split("-")
    if len(parts) != 8:
        return None
    sample, trace_id, segment, span_id, service, instance, endpoint, peer = parts
    if sample not in ("0", "1") or not _INT.match(span_id):
        return None
    decoded = [_dec(p) for p in (trace_id, segment, service, instance, endpoint, peer)]
    t, s, sv, inst, ep, pr = decoded
    if t is None or s is None or sv is None or inst is None or ep is None or pr is None:
        return None
    if not t or not s:
        return None
    return Sw8Context(sample == "1", t, s, int(span_id), sv, inst, ep, pr)


def encode_sw8_correlation(entries: Mapping[str, str]) -> str:
    """Encode ``sw8-correlation``: ``b64(key):b64(value),…`` in insertion order."""
    return ",".join(f"{_enc(k)}:{_enc(v)}" for k, v in entries.items())


def decode_sw8_correlation(header: str | None) -> dict[str, str]:
    """Decode ``sw8-correlation``; malformed entries are skipped."""
    out: dict[str, str] = {}
    for part in (header or "").split(","):
        pieces = part.strip().split(":")
        if len(pieces) != 2:
            continue
        key, value = _dec(pieces[0]), _dec(pieces[1])
        if key and value is not None:
            out[key] = value
    return out


def get_sw8_context(context: Context | None = None) -> Sw8Context | None:
    """The ``sw8`` context extracted from an incoming request, if any."""
    value = get_value(_EXTRACTED_KEY, context)
    return value if isinstance(value, Sw8Context) else None


def with_sw8_context(sw8: Sw8InjectContext | Sw8Context, context: Context | None = None) -> Context:
    """A context whose outgoing requests carry this native metadata as ``sw8`` (and
    ``sw8-correlation``).

    The SDK cannot derive native metadata from an OpenTelemetry span; supply it only when you
    bridge to SkyWalking agents.
    """
    return set_value(_INJECT_KEY, sw8, context)


def sw8_attributes(sw8: Sw8Context) -> dict[str, Any]:
    """Span attributes describing an extracted ``sw8`` parent (``sw8.parent.*``,
    ``sw8.peer``)."""
    return {
        "sw8.parent.trace_id": sw8.trace_id,
        "sw8.parent.segment_id": sw8.parent_trace_segment_id,
        "sw8.parent.span_id": sw8.parent_span_id,
        "sw8.parent.service": sw8.parent_service,
        "sw8.parent.service_instance": sw8.parent_service_instance,
        "sw8.parent.endpoint": sw8.parent_endpoint,
        "sw8.peer": sw8.network_address_used_at_peer,
    }


def _first(value: Any) -> str | None:
    if isinstance(value, (list, tuple)):
        return str(value[0]) if value else None
    return None if value is None else str(value)


class Sw8Propagator(TextMapPropagator):
    """OpenTelemetry propagator for SkyWalking headers (propagator name ``sw8``).

    * **extract** (default use): a valid ``sw8`` header is stored in the context
      (:func:`get_sw8_context`, :func:`sw8_attributes`); its trace id is not hex, so the W3C
      context stays the OpenTelemetry trace. ``sw8-correlation`` entries are added to the
      baggage.
    * **inject**: only when native metadata was set with :func:`with_sw8_context`; otherwise
      nothing is written (an OpenTelemetry span has no segment id or native span id).
    """

    def inject(
        self,
        carrier: CarrierT,
        context: Context | None = None,
        setter: Setter[CarrierT] = default_setter,
    ) -> None:
        """Write ``sw8``/``sw8-correlation`` when the context carries :func:`with_sw8_context`
        metadata."""
        sw8 = get_value(_INJECT_KEY, context)
        if not isinstance(sw8, Sw8Context):
            return
        setter.set(carrier, SW8_HEADER, encode_sw8(sw8))
        correlation = getattr(sw8, "correlation", None)
        if correlation:
            setter.set(carrier, SW8_CORRELATION_HEADER, encode_sw8_correlation(correlation))

    def extract(
        self,
        carrier: CarrierT,
        context: Context | None = None,
        getter: Getter[CarrierT] = default_getter,
    ) -> Context:
        """Read ``sw8`` into the context and ``sw8-correlation`` into the baggage."""
        out = context if context is not None else Context()
        sw8 = decode_sw8(_first(getter.get(carrier, SW8_HEADER)))
        if sw8 is not None:
            out = set_value(_EXTRACTED_KEY, sw8, out)
        for key, value in decode_sw8_correlation(
            _first(getter.get(carrier, SW8_CORRELATION_HEADER))
        ).items():
            out = otel_baggage.set_baggage(key, value, out)
        return out

    @property
    def fields(self) -> set[str]:
        """The header names this propagator uses."""
        return {SW8_HEADER, SW8_CORRELATION_HEADER}
