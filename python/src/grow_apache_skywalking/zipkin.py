"""OAP's Zipkin v2 span model (how OTLP spans are stored and read back) and its conversion into
the SPEC §5 trace model (service-owned)."""

from __future__ import annotations

import json
import re
from collections.abc import Mapping, Sequence
from typing import Any

from ._core import ValidationError
from ._tracing import (
    Span,
    SpanEvent,
    SpanKindName,
    SpanStatus,
    Trace,
    TraceSummary,
    normalize_trace_id,
)

__all__ = [
    "annotation_to_event",
    "is_bookkeeping_tag",
    "is_hex_trace_id",
    "strings_from_body",
    "summarize",
    "zipkin_span_to_span",
    "zipkin_spans_from_body",
    "zipkin_trace_id",
    "zipkin_trace_to_trace",
    "zipkin_traces_from_body",
]

_HEX_ID = re.compile(r"^(?:[0-9a-f]{16}|[0-9a-f]{32})$")
_KIND_NAMES: dict[str, SpanKindName] = {
    "CLIENT": "client",
    "SERVER": "server",
    "PRODUCER": "producer",
    "CONSUMER": "consumer",
}


def is_hex_trace_id(trace_id: str) -> bool:
    """True for a 16- or 32-character hex id (OTLP/Zipkin), false for native SkyWalking ids."""
    return isinstance(trace_id, str) and bool(_HEX_ID.match(trace_id.strip().lower()))


def zipkin_trace_id(trace_id: str, operation: str) -> str:
    """Lower-case and validate a Zipkin trace id; :class:`ValidationError` otherwise."""
    lower = trace_id.strip().lower() if isinstance(trace_id, str) else ""
    if not _HEX_ID.match(lower) or set(lower) == {"0"}:
        raise ValidationError(f"{operation}: trace id must be 16 or 32 hex characters")
    return lower


def zipkin_spans_from_body(body: Any) -> list[dict[str, Any]]:
    """The well-formed spans (objects with string ``traceId`` and ``id``) of a response body."""
    if not isinstance(body, list):
        return []
    return [
        dict(s)
        for s in body
        if isinstance(s, Mapping)
        and isinstance(s.get("traceId"), str)
        and isinstance(s.get("id"), str)
    ]


def zipkin_traces_from_body(body: Any) -> list[list[dict[str, Any]]]:
    """The non-empty traces of a ``[[span…]…]`` body."""
    if not isinstance(body, list):
        return []
    return [t for t in (zipkin_spans_from_body(x) for x in body) if t]


def strings_from_body(body: Any) -> list[str]:
    """Decode a ``string[]`` body."""
    return [s for s in body if isinstance(s, str)] if isinstance(body, list) else []


def is_bookkeeping_tag(key: str) -> bool:
    """Tags OAP adds as bookkeeping (``otel.*``, ``error``, ``w3c.tracestate``)."""
    return key.startswith("otel.") or key in ("error", "w3c.tracestate")


def annotation_to_event(annotation: Mapping[str, Any]) -> SpanEvent:
    """Decode an OAP annotation into an event.

    ``name|{"k":"v"}|0`` (split on the first and last ``|``) → name + attributes; any other
    value is the event name without attributes.
    """
    value = str(annotation.get("value", ""))
    ts = annotation.get("timestamp")
    time_micros = int(ts) if isinstance(ts, (int, float)) and not isinstance(ts, bool) else 0
    first = value.find("|")
    last = value.rfind("|")
    if first > 0 and last > first:
        raw = value[first + 1 : last] or "{}"
        try:
            parsed = json.loads(raw)
        except ValueError:
            parsed = None
        if isinstance(parsed, dict):
            return SpanEvent(value[:first], time_micros, parsed)
    return SpanEvent(value, time_micros, {})


def _num(value: Any) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return 0
    return int(value)


def zipkin_span_to_span(span: Mapping[str, Any]) -> Span:
    """Convert a stored Zipkin span into the SPEC §5 model.

    Ids lower-case (trace id padded to 32), ``kind`` lower-case (absent → ``internal``),
    ``service_name`` = ``localEndpoint.serviceName``, status from ``otel.status_code``
    (``STATUS_CODE_ERROR`` → error with ``otel.status_description``, ``STATUS_CODE_OK`` → ok; an
    ``error`` tag alone → error), attributes = tags without the bookkeeping keys (values are
    strings: OAP stores tags as text), annotations → events. Span names are lower-cased by OAP
    on ingest.
    """
    raw_tags = span.get("tags")
    tags: Mapping[str, Any] = raw_tags if isinstance(raw_tags, Mapping) else {}
    start = _num(span.get("timestamp"))
    duration = _num(span.get("duration"))
    code = str(tags.get("otel.status_code", "")).upper()
    if code in ("STATUS_CODE_ERROR", "ERROR"):
        message = tags.get("otel.status_description")
        status = SpanStatus("error", str(message) if message else None)
    elif code in ("STATUS_CODE_OK", "OK"):
        status = SpanStatus("ok")
    elif "error" in tags:
        status = SpanStatus("error")
    else:
        status = SpanStatus()
    kind = span.get("kind")
    local = span.get("localEndpoint")
    service = local.get("serviceName") if isinstance(local, Mapping) else None
    annotations = span.get("annotations")
    parent = span.get("parentId")
    return Span(
        trace_id=normalize_trace_id(str(span["traceId"])),
        span_id=str(span["id"]).lower(),
        name=str(span.get("name") or ""),
        start_micros=start,
        end_micros=start + duration,
        parent_span_id=str(parent).lower() if isinstance(parent, str) and parent else None,
        kind="internal" if kind is None else _KIND_NAMES.get(str(kind), "unspecified"),
        service_name=service if isinstance(service, str) else "",
        duration_micros=duration,
        status=status,
        attributes={k: v for k, v in tags.items() if not is_bookkeeping_tag(k)},
        events=[
            annotation_to_event(a)
            for a in (annotations if isinstance(annotations, list) else [])
            if isinstance(a, Mapping)
        ],
    )


def zipkin_trace_to_trace(spans: Sequence[Mapping[str, Any]]) -> Trace | None:
    """Convert one Zipkin trace into a SPEC :class:`Trace`; ``None`` when empty."""
    if not spans:
        return None
    return Trace(
        normalize_trace_id(str(spans[0]["traceId"])), [zipkin_span_to_span(s) for s in spans]
    )


def summarize(trace: Trace) -> TraceSummary:
    """Summary of a SPEC trace; the root falls back to the earliest span."""
    if not trace.spans:
        return TraceSummary(trace.trace_id, 0, 0, 0)
    start = min(s.start_micros for s in trace.spans)
    end = max(s.end_micros for s in trace.spans)
    roots = trace.roots()
    root = roots[0] if roots else trace.spans[0]
    return TraceSummary(
        trace_id=trace.trace_id,
        start_micros=start,
        duration_micros=max(0, end - start),
        span_count=len(trace.spans),
        root_service_name=root.service_name,
        root_name=root.name,
    )
