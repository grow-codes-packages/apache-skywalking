"""The native SkyWalking query model (GraphQL) and its conversion into the SPEC §5 trace model.

Native trace ids are opaque strings (``<hex>.<n>.<ms>``) and native span identity is
``(segmentId, spanId)``. The SDK keeps them verbatim (coordinator decision): the converted spans
are :class:`NativeIdSpan` / :class:`NativeIdTrace`, subclasses of the template's
:class:`~grow_apache_skywalking.Span` / :class:`~grow_apache_skywalking.Trace` that skip the hex
normalization; span ids are ``"<segmentId>-<spanId>"``.
"""

from __future__ import annotations

import dataclasses
import json
from collections.abc import Mapping
from typing import Any, ClassVar

from ._tracing import (
    DependencyLink,
    Span,
    SpanEvent,
    SpanKindName,
    SpanLink,
    SpanStatus,
    StatusCodeName,
    Trace,
)

__all__ = [
    "NATIVE_SPAN_FIELDS",
    "KeyValue",
    "NativeIdSpan",
    "NativeIdSpanLink",
    "NativeIdTrace",
    "NativeSpan",
    "NativeSpanLog",
    "NativeSpanRef",
    "NativeTrace",
    "Topology",
    "TopologyCall",
    "TopologyNode",
    "decode_native_span",
    "decode_topology",
    "key_values",
    "native_span_key",
    "native_span_to_span",
    "native_trace_from_spans",
    "native_trace_to_trace",
    "topology_to_dependencies",
]

#: GraphQL selection used for native spans.
NATIVE_SPAN_FIELDS = (
    "traceId segmentId spanId parentSpanId refs{traceId parentSegmentId parentSpanId type} "
    "serviceCode serviceInstanceName startTime endTime endpointName type peer component isError "
    "layer tags{key value} logs{time data{key value}}"
)

_KINDS: dict[str, SpanKindName] = {"Entry": "server", "Exit": "client", "Local": "internal"}


@dataclasses.dataclass(frozen=True)
class KeyValue:
    """A ``{key, value}`` pair (span tags, log tags, log data)."""

    key: str
    value: str


@dataclasses.dataclass(frozen=True)
class NativeSpanRef:
    """Cross-segment reference of a native span."""

    trace_id: str
    parent_segment_id: str
    parent_span_id: int
    #: ``CROSS_PROCESS`` or ``CROSS_THREAD``.
    type: str


@dataclasses.dataclass(frozen=True)
class NativeSpanLog:
    """A log entry recorded on a native span (``time`` in epoch milliseconds)."""

    time: int
    data: tuple[KeyValue, ...] = ()


@dataclasses.dataclass(frozen=True)
class NativeSpan:
    """A span as stored by SkyWalking (agents, ``/v3/segments``); identity = ``(segment_id,
    span_id)``. Times are epoch milliseconds; ``parent_span_id`` ``-1`` = segment root."""

    trace_id: str
    segment_id: str
    span_id: int
    parent_span_id: int = -1
    refs: tuple[NativeSpanRef, ...] = ()
    service_code: str = ""
    service_instance_name: str = ""
    start_time: int = 0
    end_time: int = 0
    endpoint_name: str = ""
    #: ``Entry``, ``Exit`` or ``Local``.
    type: str = ""
    peer: str = ""
    component: str = ""
    is_error: bool = False
    layer: str = ""
    tags: tuple[KeyValue, ...] = ()
    logs: tuple[NativeSpanLog, ...] = ()


@dataclasses.dataclass(frozen=True)
class NativeTrace:
    """A native trace: the spans of every segment."""

    trace_id: str
    spans: tuple[NativeSpan, ...] = ()


@dataclasses.dataclass(frozen=True)
class TopologyNode:
    """A node of the service topology."""

    id: str
    name: str
    type: str = ""
    is_real: bool = False
    layers: tuple[str, ...] = ()


@dataclasses.dataclass(frozen=True)
class TopologyCall:
    """An edge of the service topology (``source``/``target`` are node ids)."""

    id: str
    source: str
    target: str
    detect_points: tuple[str, ...] = ()
    source_components: tuple[str, ...] = ()
    target_components: tuple[str, ...] = ()


@dataclasses.dataclass(frozen=True)
class Topology:
    """``getGlobalTopology`` / ``getServiceTopology`` result."""

    nodes: tuple[TopologyNode, ...] = ()
    calls: tuple[TopologyCall, ...] = ()


# --------------------------------------------------------------------------- SPEC model


@dataclasses.dataclass(frozen=True)
class NativeIdSpanLink(SpanLink):
    """A :class:`SpanLink` whose ids are native SkyWalking ids (kept verbatim)."""

    normalize_ids: ClassVar[bool] = False


@dataclasses.dataclass(frozen=True)
class NativeIdSpan(Span):
    """A :class:`Span` whose ids are native SkyWalking ids (kept verbatim, not hex)."""

    normalize_ids: ClassVar[bool] = False
    link_class: ClassVar[type[SpanLink]] = NativeIdSpanLink


@dataclasses.dataclass(frozen=True)
class NativeIdTrace(Trace):
    """A :class:`Trace` whose id is a native SkyWalking trace id (kept verbatim)."""

    normalize_ids: ClassVar[bool] = False
    span_class: ClassVar[type[Span]] = NativeIdSpan


# --------------------------------------------------------------------------- decoding


def _str(value: Any) -> str:
    return value if isinstance(value, str) else ""


def _int(value: Any, fallback: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return fallback
    return int(value)


def _strings(value: Any) -> tuple[str, ...]:
    return tuple(v for v in value if isinstance(v, str)) if isinstance(value, list) else ()


def _objects(value: Any) -> list[Mapping[str, Any]]:
    return [v for v in value if isinstance(v, Mapping)] if isinstance(value, list) else []


def key_values(value: Any) -> tuple[KeyValue, ...]:
    """Decode a ``[{key, value}]`` list (non-string values as JSON, ``null`` → ``""``)."""
    out = []
    for kv in _objects(value):
        v = kv.get("value")
        text = v if isinstance(v, str) else "" if v is None else json.dumps(v)
        out.append(KeyValue(_str(kv.get("key")), text))
    return tuple(out)


def decode_native_span(raw: Any) -> NativeSpan:
    """Decode one native span from a GraphQL response (missing fields get neutral defaults)."""
    s: Mapping[str, Any] = raw if isinstance(raw, Mapping) else {}
    return NativeSpan(
        trace_id=_str(s.get("traceId")),
        segment_id=_str(s.get("segmentId")),
        span_id=_int(s.get("spanId")),
        parent_span_id=_int(s.get("parentSpanId"), -1),
        refs=tuple(
            NativeSpanRef(
                _str(r.get("traceId")),
                _str(r.get("parentSegmentId")),
                _int(r.get("parentSpanId")),
                _str(r.get("type")),
            )
            for r in _objects(s.get("refs"))
        ),
        service_code=_str(s.get("serviceCode")),
        service_instance_name=_str(s.get("serviceInstanceName")),
        start_time=_int(s.get("startTime")),
        end_time=_int(s.get("endTime")),
        endpoint_name=_str(s.get("endpointName")),
        type=_str(s.get("type")),
        peer=_str(s.get("peer")),
        component=_str(s.get("component")),
        is_error=s.get("isError") is True,
        layer=_str(s.get("layer")),
        tags=key_values(s.get("tags")),
        logs=tuple(
            NativeSpanLog(_int(lg.get("time")), key_values(lg.get("data")))
            for lg in _objects(s.get("logs"))
        ),
    )


def native_trace_from_spans(raw: Any) -> NativeTrace | None:
    """Decode a list of native spans into a :class:`NativeTrace`; ``None`` when empty."""
    spans = tuple(decode_native_span(s) for s in raw) if isinstance(raw, list) else ()
    return NativeTrace(spans[0].trace_id, spans) if spans else None


def decode_topology(raw: Any) -> Topology:
    """Decode a topology result."""
    t: Mapping[str, Any] = raw if isinstance(raw, Mapping) else {}
    return Topology(
        nodes=tuple(
            TopologyNode(
                _str(n.get("id")),
                _str(n.get("name")),
                _str(n.get("type")),
                n.get("isReal") is True,
                _strings(n.get("layers")),
            )
            for n in _objects(t.get("nodes"))
        ),
        calls=tuple(
            TopologyCall(
                _str(c.get("id")),
                _str(c.get("source")),
                _str(c.get("target")),
                _strings(c.get("detectPoints")),
                _strings(c.get("sourceComponents")),
                _strings(c.get("targetComponents")),
            )
            for c in _objects(t.get("calls"))
        ),
    )


# --------------------------------------------------------------------------- conversion


def native_span_key(segment_id: str, span_id: int) -> str:
    """Composite SPEC span id of a native span: ``<segmentId>-<spanId>``."""
    return f"{segment_id}-{span_id}"


def native_span_to_span(span: NativeSpan) -> NativeIdSpan:
    """Convert a native span into the SPEC §5 model.

    * Ids are kept verbatim: ``span_id = "<segmentId>-<spanId>"``.
    * Parent: the same segment's ``parent_span_id`` when >= 0, else ``refs[0]``
      (``"<parentSegmentId>-<parentSpanId>"``); further refs become links.
    * ``kind``: Entry -> server, Exit -> client, Local -> internal.
    * Times: milliseconds -> microseconds. Status ``error`` when ``is_error``.
    * Attributes: tags plus ``sw.component``, ``sw.layer``, ``sw.peer`` and
      ``sw.service_instance`` (when set).
    * Span logs become events (name = the ``event`` data value, else ``log``).
    """
    attributes: dict[str, Any] = {kv.key: kv.value for kv in span.tags}
    for key, value in (
        ("sw.component", span.component),
        ("sw.layer", span.layer),
        ("sw.peer", span.peer),
        ("sw.service_instance", span.service_instance_name),
    ):
        if value:
            attributes[key] = value
    start = span.start_time * 1000
    end = max(span.end_time, span.start_time) * 1000
    events = []
    for log in span.logs:
        data = {kv.key: kv.value for kv in log.data}
        events.append(SpanEvent(data.get("event", "log"), log.time * 1000, data))
    extra = span.refs if span.parent_span_id >= 0 else span.refs[1:]
    links = [
        NativeIdSpanLink(
            r.trace_id,
            native_span_key(r.parent_segment_id, r.parent_span_id),
            {"sw.ref_type": r.type},
        )
        for r in extra
    ]
    parent: str | None = None
    if span.parent_span_id >= 0:
        parent = native_span_key(span.segment_id, span.parent_span_id)
    elif span.refs:
        parent = native_span_key(span.refs[0].parent_segment_id, span.refs[0].parent_span_id)
    status: StatusCodeName = "error" if span.is_error else "unset"
    return NativeIdSpan(
        trace_id=span.trace_id,
        span_id=native_span_key(span.segment_id, span.span_id),
        name=span.endpoint_name,
        start_micros=start,
        end_micros=end,
        parent_span_id=parent,
        kind=_KINDS.get(span.type, "unspecified"),
        service_name=span.service_code,
        duration_micros=end - start,
        status=SpanStatus(status),
        attributes=attributes,
        events=events,
        links=links,
    )


def native_trace_to_trace(trace: NativeTrace | None) -> NativeIdTrace | None:
    """Convert a native trace into a SPEC trace (spans sorted); ``None`` when empty."""
    if trace is None or not trace.spans:
        return None
    return NativeIdTrace(trace.trace_id, [native_span_to_span(s) for s in trace.spans])


def topology_to_dependencies(topology: Topology) -> list[DependencyLink]:
    """Topology → dependency links (``parent`` = source node name, ``child`` = target node
    name, ``call_count`` = 0: the topology type carries no call counts). Sorted."""
    names = {n.id: n.name for n in topology.nodes}
    links = [
        DependencyLink(names.get(c.source, c.source), names.get(c.target, c.target), 0)
        for c in topology.calls
    ]
    return sorted(links, key=lambda link: (link.parent, link.child))
