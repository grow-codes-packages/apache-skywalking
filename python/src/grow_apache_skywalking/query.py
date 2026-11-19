"""The OAP GraphQL query API: metadata, native traces, logs, topology and raw GraphQL.

Obtain :class:`SkyWalkingQueryClient` from ``SkyWalkingClient.query`` (or
:class:`AsyncSkyWalkingQueryClient` from ``AsyncSkyWalkingClient.query``). Services, instances
and endpoints are named by their names; the SDK derives the ids (``base64(name).1``).
"""

from __future__ import annotations

import dataclasses
from collections.abc import Mapping, Sequence
from typing import Any, Literal

from ._api import (
    GraphQLResponse,
    Op,
    SkyWalkingCapabilities,
    State,
    TimeInfo,
    capabilities,
    duration,
    field,
    graphql,
    run_async,
    run_sync,
    time_info,
)
from ._core import AsyncHttpTransport, CancelToken, HttpTransport, ValidationError
from .duration import TimeRange, endpoint_id, instance_id, service_id
from .native import (
    NATIVE_SPAN_FIELDS,
    KeyValue,
    NativeTrace,
    Topology,
    decode_topology,
    key_values,
    native_trace_from_spans,
)

__all__ = [
    "AsyncSkyWalkingQueryClient",
    "InstanceAttribute",
    "LogOrder",
    "NativeLog",
    "Paging",
    "QueryLogsResult",
    "QueryTracesResult",
    "SkyWalkingEndpoint",
    "SkyWalkingInstance",
    "SkyWalkingQueryClient",
    "SkyWalkingService",
    "TraceOrder",
    "TraceState",
    "decode_native_log",
]

#: Trace state filter of ``query_traces``.
TraceState = Literal["ALL", "SUCCESS", "ERROR"]
#: Order of ``query_traces``.
TraceOrder = Literal["BY_START_TIME", "BY_DURATION"]
#: Order of ``query_logs`` (``DES`` = newest first).
LogOrder = Literal["DES", "ASC"]

_SERVICE_FIELDS = "id name group shortName layers normal"
_LOG_FIELDS = (
    "serviceName serviceId serviceInstanceName endpointName traceId timestamp contentType "
    "content tags{key value}"
)
_TOPOLOGY_FIELDS = (
    "nodes{id name type isReal layers} "
    "calls{id source target detectPoints sourceComponents targetComponents}"
)


@dataclasses.dataclass(frozen=True)
class SkyWalkingService:
    """A service known to OAP (native agents, native logs, OTLP logs)."""

    #: ``base64(name) + ".1"`` for real services.
    id: str
    name: str
    group: str = ""
    short_name: str = ""
    layers: tuple[str, ...] = ()
    #: ``False`` for virtual (conjectured) services.
    normal: bool = True


@dataclasses.dataclass(frozen=True)
class SkyWalkingEndpoint:
    """An endpoint (operation) of a service."""

    id: str
    name: str


@dataclasses.dataclass(frozen=True)
class InstanceAttribute:
    """A ``{name, value}`` attribute of a service instance."""

    name: str
    value: str


@dataclasses.dataclass(frozen=True)
class SkyWalkingInstance:
    """A service instance."""

    id: str
    name: str
    language: str = ""
    instance_uuid: str = ""
    attributes: tuple[InstanceAttribute, ...] = ()


@dataclasses.dataclass(frozen=True)
class Paging:
    """1-based page selection (``page_size`` 1..10000)."""

    page_num: int = 1
    page_size: int = 20


@dataclasses.dataclass(frozen=True)
class QueryTracesResult:
    """Result of ``query_traces``: whole traces (every segment) that match."""

    traces: tuple[NativeTrace, ...] = ()
    #: Window the server searched as ``(start_ms, end_ms)``, when reported.
    retrieved_time_range: tuple[int, int] | None = None


@dataclasses.dataclass(frozen=True)
class NativeLog:
    """A log entry as stored by OAP (``timestamp`` in epoch milliseconds)."""

    service_name: str = ""
    service_id: str = ""
    service_instance_name: str = ""
    endpoint_name: str = ""
    #: Native or hex trace id; ``""`` when the log has no trace context.
    trace_id: str = ""
    timestamp: int = 0
    #: ``TEXT``, ``JSON`` or ``YAML``.
    content_type: str = ""
    content: str = ""
    tags: tuple[KeyValue, ...] = ()

    def tag(self, key: str) -> str | None:
        """Value of the first tag named ``key``."""
        return next((t.value for t in self.tags if t.key == key), None)


@dataclasses.dataclass(frozen=True)
class QueryLogsResult:
    """Result of ``query_logs``."""

    logs: tuple[NativeLog, ...] = ()
    #: Server-side explanation when the query could not run as asked.
    error_reason: str | None = None


def _str(value: Any) -> str:
    return value if isinstance(value, str) else ""


def _objects(value: Any) -> list[Mapping[str, Any]]:
    return [v for v in value if isinstance(v, Mapping)] if isinstance(value, list) else []


def _decode_service(s: Mapping[str, Any]) -> SkyWalkingService:
    layers = s.get("layers")
    return SkyWalkingService(
        id=_str(s.get("id")),
        name=_str(s.get("name")),
        group=_str(s.get("group")),
        short_name=_str(s.get("shortName")),
        layers=tuple(x for x in layers if isinstance(x, str)) if isinstance(layers, list) else (),
        normal=s.get("normal") is not False,
    )


def decode_native_log(raw: Any) -> NativeLog:
    """Decode one native log from a GraphQL response."""
    lg: Mapping[str, Any] = raw if isinstance(raw, Mapping) else {}
    ts = lg.get("timestamp")
    return NativeLog(
        service_name=_str(lg.get("serviceName")),
        service_id=_str(lg.get("serviceId")),
        service_instance_name=_str(lg.get("serviceInstanceName")),
        endpoint_name=_str(lg.get("endpointName")),
        trace_id=_str(lg.get("traceId")),
        timestamp=int(ts) if isinstance(ts, (int, float)) and not isinstance(ts, bool) else 0,
        content_type=_str(lg.get("contentType")),
        content=_str(lg.get("content")),
        tags=key_values(lg.get("tags")),
    )


def _required(value: Any, what: str, operation: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValidationError(f"{operation}: {what} is required")
    return value


def _paging(p: Paging | None, operation: str) -> dict[str, int]:
    p = p or Paging()
    if isinstance(p.page_num, bool) or not isinstance(p.page_num, int) or p.page_num < 1:
        raise ValidationError(f"{operation}: page_num must be a positive integer")
    if (
        isinstance(p.page_size, bool)
        or not isinstance(p.page_size, int)
        or not 1 <= p.page_size <= 10_000
    ):
        raise ValidationError(f"{operation}: page_size must be 1..10000")
    return {"pageNum": p.page_num, "pageSize": p.page_size}


def _tag_list(tags: Mapping[str, str] | None) -> list[dict[str, str]] | None:
    if not tags:
        return None
    return [{"key": str(k), "value": str(v)} for k, v in tags.items()]


def _scoped_ids(
    service: str | None, instance: str | None, endpoint: str | None, operation: str
) -> dict[str, str]:
    out: dict[str, str] = {}
    if (instance is not None or endpoint is not None) and service is None:
        raise ValidationError(f"{operation}: instance and endpoint need service")
    if service is not None:
        out["serviceId"] = service_id(_required(service, "service", operation))
        if instance is not None:
            out["serviceInstanceId"] = instance_id(service, instance)
        if endpoint is not None:
            out["endpointId"] = endpoint_id(service, endpoint)
    return out


def _contains_all(text: str, words: Sequence[str]) -> bool:
    lower = text.lower()
    return all(w.lower() in lower for w in words)


def _contains_none(text: str, words: Sequence[str]) -> bool:
    lower = text.lower()
    return not any(w.lower() in lower for w in words)


# --------------------------------------------------------------------------- operations


def op_list_services(state: State, layer: str | None) -> Op[list[SkyWalkingService]]:
    rows = yield from field(
        state,
        f"query($l:String){{listServices(layer:$l){{{_SERVICE_FIELDS}}}}}",
        {"l": layer},
        "listServices",
        "query.list_services",
    )
    return [_decode_service(s) for s in _objects(rows)]


def op_find_service(state: State, name: str) -> Op[SkyWalkingService | None]:
    operation = "query.find_service"
    row = yield from field(
        state,
        f"query($n:String!){{findService(serviceName:$n){{{_SERVICE_FIELDS}}}}}",
        {"n": _required(name, "name", operation)},
        "findService",
        operation,
    )
    return _decode_service(row) if isinstance(row, Mapping) else None


def op_list_endpoints(
    state: State, service: str, keyword: str | None, limit: int
) -> Op[list[SkyWalkingEndpoint]]:
    operation = "query.list_endpoints"
    if isinstance(limit, bool) or not isinstance(limit, int) or limit < 1:
        raise ValidationError(f"{operation}: limit must be a positive integer")
    sid = service_id(_required(service, "service", operation))
    rows = yield from field(
        state,
        "query($k:String,$s:ID!,$l:Int!){findEndpoint(keyword:$k,serviceId:$s,limit:$l){id name}}",
        {"k": keyword or None, "s": sid, "l": limit},
        "findEndpoint",
        operation,
    )
    return [SkyWalkingEndpoint(_str(e.get("id")), _str(e.get("name"))) for e in _objects(rows)]


def op_list_instances(
    state: State,
    service: str,
    range: TimeRange | None,  # noqa: A002
) -> Op[list[SkyWalkingInstance]]:
    operation = "query.list_instances"
    sid = service_id(_required(service, "service", operation))
    d = yield from duration(state, range, operation)
    rows = yield from field(
        state,
        "query($s:ID!,$d:Duration!){listInstances(serviceId:$s,duration:$d)"
        "{id name language instanceUUID attributes{name value}}}",
        {"s": sid, "d": d.to_json()},
        "listInstances",
        operation,
    )
    return [
        SkyWalkingInstance(
            id=_str(i.get("id")),
            name=_str(i.get("name")),
            language=_str(i.get("language")),
            instance_uuid=_str(i.get("instanceUUID")),
            attributes=tuple(
                InstanceAttribute(_str(a.get("name")), _str(a.get("value")))
                for a in _objects(i.get("attributes"))
            ),
        )
        for i in _objects(rows)
    ]


def op_query_traces(
    state: State,
    *,
    service: str | None = None,
    instance: str | None = None,
    endpoint: str | None = None,
    trace_id: str | None = None,
    trace_state: TraceState = "ALL",
    min_duration_ms: float | None = None,
    max_duration_ms: float | None = None,
    tags: Mapping[str, str] | None = None,
    range: TimeRange | None = None,  # noqa: A002
    paging: Paging | None = None,
    order: TraceOrder = "BY_START_TIME",
) -> Op[QueryTracesResult]:
    operation = "query.query_traces"
    condition: dict[str, Any] = {
        **_scoped_ids(service, instance, endpoint, operation),
        "traceState": trace_state,
        "queryOrder": order,
        "paging": _paging(paging, operation),
    }
    for key, value in (
        ("minTraceDuration", min_duration_ms),
        ("maxTraceDuration", max_duration_ms),
    ):
        if value is None:
            continue
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not value >= 0:
            raise ValidationError(f"{operation}: durations must be non-negative")
        condition[key] = round(value)
    if trace_id is not None:
        condition["traceId"] = _required(trace_id, "trace_id", operation)
    tag_list = _tag_list(tags)
    if tag_list is not None:
        condition["tags"] = tag_list
    if trace_id is None or range is not None:
        d = yield from duration(state, range, operation)
        condition["queryDuration"] = d.to_json()
    res = yield from field(
        state,
        f"query($c:TraceQueryCondition){{queryTraces(condition:$c){{traces{{spans{{"
        f"{NATIVE_SPAN_FIELDS}}}}} retrievedTimeRange{{startTime endTime}}}}}}",
        {"c": condition},
        "queryTraces",
        operation,
    )
    body: Mapping[str, Any] = res if isinstance(res, Mapping) else {}
    traces = tuple(
        t
        for t in (native_trace_from_spans(x.get("spans")) for x in _objects(body.get("traces")))
        if t is not None
    )
    window = body.get("retrievedTimeRange")
    retrieved = None
    if isinstance(window, Mapping):
        start, end = window.get("startTime"), window.get("endTime")
        if isinstance(start, int) and isinstance(end, int):
            retrieved = (start, end)
    return QueryTracesResult(traces, retrieved)


def op_get_trace(state: State, trace_id: str) -> Op[NativeTrace | None]:
    operation = "query.get_trace"
    res = yield from field(
        state,
        f"query($id:ID!){{queryTrace(traceId:$id){{spans{{{NATIVE_SPAN_FIELDS}}}}}}}",
        {"id": _required(trace_id, "trace_id", operation)},
        "queryTrace",
        operation,
    )
    return native_trace_from_spans(res.get("spans") if isinstance(res, Mapping) else None)


def op_query_logs(
    state: State,
    *,
    service: str | None = None,
    instance: str | None = None,
    endpoint: str | None = None,
    trace_id: str | None = None,
    segment_id: str | None = None,
    span_id: int | None = None,
    keywords: Sequence[str] = (),
    excluding_keywords: Sequence[str] = (),
    tags: Mapping[str, str] | None = None,
    range: TimeRange | None = None,  # noqa: A002
    paging: Paging | None = None,
    order: LogOrder = "DES",
) -> Op[QueryLogsResult]:
    operation = "query.query_logs"
    condition: dict[str, Any] = {
        **_scoped_ids(service, instance, endpoint, operation),
        "paging": _paging(paging, operation),
        "queryOrder": order,
    }
    if trace_id is not None:
        related: dict[str, Any] = {"traceId": _required(trace_id, "trace_id", operation)}
        if segment_id is not None:
            related["segmentId"] = segment_id
        if span_id is not None:
            related["spanId"] = span_id
        condition["relatedTrace"] = related
    tag_list = _tag_list(tags)
    if tag_list is not None:
        condition["tags"] = tag_list
    keywords = list(keywords or ())
    excluding = list(excluding_keywords or ())
    server_keywords = False
    if keywords or excluding:
        caps = yield from capabilities(state)
        server_keywords = caps.logs_by_keywords
        if server_keywords:
            if keywords:
                condition["keywordsOfContent"] = keywords
            if excluding:
                condition["excludingKeywordsOfContent"] = excluding
    if trace_id is None or range is not None:
        d = yield from duration(state, range, operation)
        condition["queryDuration"] = d.to_json()
    res = yield from field(
        state,
        f"query($c:LogQueryCondition){{queryLogs(condition:$c)"
        f"{{errorReason logs{{{_LOG_FIELDS}}}}}}}",
        {"c": condition},
        "queryLogs",
        operation,
    )
    body: Mapping[str, Any] = res if isinstance(res, Mapping) else {}
    logs = [decode_native_log(x) for x in _objects(body.get("logs"))]
    if not server_keywords:
        logs = [
            lg
            for lg in logs
            if _contains_all(lg.content, keywords) and _contains_none(lg.content, excluding)
        ]
    reason = body.get("errorReason")
    return QueryLogsResult(tuple(logs), reason if isinstance(reason, str) and reason else None)


def op_list_log_tag_keys(state: State, range: TimeRange | None) -> Op[list[str]]:  # noqa: A002
    operation = "query.list_log_tag_keys"
    d = yield from duration(state, range, operation)
    keys = yield from field(
        state,
        "query($d:Duration!){queryLogTagAutocompleteKeys(duration:$d)}",
        {"d": d.to_json()},
        "queryLogTagAutocompleteKeys",
        operation,
    )
    return sorted(k for k in keys if isinstance(k, str)) if isinstance(keys, list) else []


def op_topology(
    state: State,
    range: TimeRange | None,  # noqa: A002
    service: str | None,
    layer: str | None,
) -> Op[Topology]:
    operation = "query.topology"
    d = yield from duration(state, range, operation)
    if service is not None:
        res = yield from field(
            state,
            f"query($s:ID!,$d:Duration!){{getServiceTopology(serviceId:$s,duration:$d)"
            f"{{{_TOPOLOGY_FIELDS}}}}}",
            {"s": service_id(_required(service, "service", operation)), "d": d.to_json()},
            "getServiceTopology",
            operation,
        )
        return decode_topology(res)
    res = yield from field(
        state,
        f"query($d:Duration!,$l:String){{getGlobalTopology(duration:$d,layer:$l)"
        f"{{{_TOPOLOGY_FIELDS}}}}}",
        {"d": d.to_json(), "l": layer},
        "getGlobalTopology",
        operation,
    )
    return decode_topology(res)


# --------------------------------------------------------------------------- clients


class SkyWalkingQueryClient:
    """The SkyWalking GraphQL query API (``POST /graphql`` on port 12800), synchronous.

    Obtain it from ``SkyWalkingClient.query``. Every method accepts ``cancel=`` (a
    :class:`CancelToken`).
    """

    def __init__(self, state: State, transports: Mapping[str, HttpTransport]) -> None:
        self._state = state
        self._transports = transports

    def _run(self, op: Op[Any], cancel: CancelToken | None) -> Any:
        return run_sync(op, self._transports, cancel)

    def graphql(
        self,
        query: str,
        variables: Mapping[str, Any] | None = None,
        *,
        cancel: CancelToken | None = None,
    ) -> GraphQLResponse:
        """Raw GraphQL: returns ``data`` and ``errors`` (partial data possible). HTTP 400
        (syntax/validation errors) raises :class:`ValidationError`."""
        result: GraphQLResponse = self._run(
            graphql(self._state, query, variables, "graphql"), cancel
        )
        return result

    def time_info(self, *, cancel: CancelToken | None = None) -> TimeInfo:
        """OAP clock and timezone: ``getTimeInfo``."""
        result: TimeInfo = self._run(time_info(self._state), cancel)
        return result

    def capabilities(self, *, cancel: CancelToken | None = None) -> SkyWalkingCapabilities:
        """Server features (``hasQueryTracesV2Support``, ``supportQueryLogsByKeywords``), cached."""
        result: SkyWalkingCapabilities = self._run(capabilities(self._state), cancel)
        return result

    def list_services(
        self,
        layer: str | None = None,
        range: TimeRange | None = None,  # noqa: A002
        *,
        cancel: CancelToken | None = None,
    ) -> list[SkyWalkingService]:
        """Services: ``listServices(layer)``. ``layer`` ``None`` = all layers; an unknown layer
        → ``[]``. OAP has no time filter for services: ``range`` is accepted for API symmetry
        and ignored. Services that only sent OTLP *traces* are not listed (use
        ``client.traces.list_services()``)."""
        result: list[SkyWalkingService] = self._run(op_list_services(self._state, layer), cancel)
        return result

    def find_service(
        self, name: str, *, cancel: CancelToken | None = None
    ) -> SkyWalkingService | None:
        """One service by name: ``findService``; ``None`` when unknown."""
        result: SkyWalkingService | None = self._run(op_find_service(self._state, name), cancel)
        return result

    def list_endpoints(
        self,
        service: str,
        keyword: str | None = None,
        *,
        limit: int = 100,
        cancel: CancelToken | None = None,
    ) -> list[SkyWalkingEndpoint]:
        """Endpoints of a service: ``findEndpoint(keyword, serviceId, limit)``. ``keyword`` is a
        tokenised match (``downstream`` finds ``/native/downstream``, ``down`` does not). No
        duration is sent (OAP 10.4 + BanyanDB returns nothing with one)."""
        result: list[SkyWalkingEndpoint] = self._run(
            op_list_endpoints(self._state, service, keyword, limit), cancel
        )
        return result

    def list_instances(
        self,
        service: str,
        range: TimeRange | None = None,  # noqa: A002
        *,
        cancel: CancelToken | None = None,
    ) -> list[SkyWalkingInstance]:
        """Instances of a service active in ``range`` (default last hour): ``listInstances``."""
        result: list[SkyWalkingInstance] = self._run(
            op_list_instances(self._state, service, range), cancel
        )
        return result

    def query_traces(
        self,
        *,
        service: str | None = None,
        instance: str | None = None,
        endpoint: str | None = None,
        trace_id: str | None = None,
        trace_state: TraceState = "ALL",
        min_duration_ms: float | None = None,
        max_duration_ms: float | None = None,
        tags: Mapping[str, str] | None = None,
        range: TimeRange | None = None,  # noqa: A002
        paging: Paging | None = None,
        order: TraceOrder = "BY_START_TIME",
        cancel: CancelToken | None = None,
    ) -> QueryTracesResult:
        """Native traces: ``queryTraces(condition)`` (whole traces). ``instance``/``endpoint``
        need ``service``; durations in ms; ``tags`` must be keys in the server's
        ``SW_SEARCHABLE_TAG_KEYS``. The window (default last hour) is omitted when only
        ``trace_id`` is given. The legacy ``queryBasicTraces`` is not used (unsupported on
        BanyanDB)."""
        result: QueryTracesResult = self._run(
            op_query_traces(
                self._state,
                service=service,
                instance=instance,
                endpoint=endpoint,
                trace_id=trace_id,
                trace_state=trace_state,
                min_duration_ms=min_duration_ms,
                max_duration_ms=max_duration_ms,
                tags=tags,
                range=range,
                paging=paging,
                order=order,
            ),
            cancel,
        )
        return result

    def get_trace(self, trace_id: str, *, cancel: CancelToken | None = None) -> NativeTrace | None:
        """One native trace: ``queryTrace(traceId)``; ``None`` when unknown (OTLP trace ids are
        never native)."""
        result: NativeTrace | None = self._run(op_get_trace(self._state, trace_id), cancel)
        return result

    def query_logs(
        self,
        *,
        service: str | None = None,
        instance: str | None = None,
        endpoint: str | None = None,
        trace_id: str | None = None,
        segment_id: str | None = None,
        span_id: int | None = None,
        keywords: Sequence[str] = (),
        excluding_keywords: Sequence[str] = (),
        tags: Mapping[str, str] | None = None,
        range: TimeRange | None = None,  # noqa: A002
        paging: Paging | None = None,
        order: LogOrder = "DES",
        cancel: CancelToken | None = None,
    ) -> QueryLogsResult:
        """Logs: ``queryLogs(condition)``. Either a window (default last hour) or ``trace_id``
        (``relatedTrace``) is sent. ``keywords``/``excluding_keywords`` go to the server when it
        supports keyword search (Elasticsearch storage); on BanyanDB the returned page is
        filtered client side (case-insensitive substring). ``tags`` must be keys in
        ``SW_SEARCHABLE_LOGS_TAG_KEYS``."""
        result: QueryLogsResult = self._run(
            op_query_logs(
                self._state,
                service=service,
                instance=instance,
                endpoint=endpoint,
                trace_id=trace_id,
                segment_id=segment_id,
                span_id=span_id,
                keywords=keywords,
                excluding_keywords=excluding_keywords,
                tags=tags,
                range=range,
                paging=paging,
                order=order,
            ),
            cancel,
        )
        return result

    def list_log_tag_keys(
        self,
        range: TimeRange | None = None,  # noqa: A002
        *,
        cancel: CancelToken | None = None,
    ) -> list[str]:
        """Log tag keys seen in ``range`` (default last hour): ``queryLogTagAutocompleteKeys``."""
        result: list[str] = self._run(op_list_log_tag_keys(self._state, range), cancel)
        return result

    def topology(
        self,
        range: TimeRange | None = None,  # noqa: A002
        *,
        service: str | None = None,
        layer: str | None = None,
        cancel: CancelToken | None = None,
    ) -> Topology:
        """Service topology in ``range`` (default last hour): ``getGlobalTopology(duration,
        layer)``, or ``getServiceTopology`` when ``service`` is set. Native traffic only (OTLP
        spans create no edges); includes the virtual ``User`` node."""
        result: Topology = self._run(op_topology(self._state, range, service, layer), cancel)
        return result


class AsyncSkyWalkingQueryClient:
    """Asynchronous :class:`SkyWalkingQueryClient` (same methods, awaited). Obtain it from
    ``AsyncSkyWalkingClient.query``; cancel a call by cancelling its task."""

    def __init__(self, state: State, transports: Mapping[str, AsyncHttpTransport]) -> None:
        self._state = state
        self._transports = transports

    async def _run(self, op: Op[Any]) -> Any:
        return await run_async(op, self._transports)

    async def graphql(
        self, query: str, variables: Mapping[str, Any] | None = None
    ) -> GraphQLResponse:
        """See :meth:`SkyWalkingQueryClient.graphql`."""
        result: GraphQLResponse = await self._run(graphql(self._state, query, variables, "graphql"))
        return result

    async def time_info(self) -> TimeInfo:
        """See :meth:`SkyWalkingQueryClient.time_info`."""
        result: TimeInfo = await self._run(time_info(self._state))
        return result

    async def capabilities(self) -> SkyWalkingCapabilities:
        """See :meth:`SkyWalkingQueryClient.capabilities`."""
        result: SkyWalkingCapabilities = await self._run(capabilities(self._state))
        return result

    async def list_services(
        self,
        layer: str | None = None,
        range: TimeRange | None = None,  # noqa: A002
    ) -> list[SkyWalkingService]:
        """See :meth:`SkyWalkingQueryClient.list_services`."""
        result: list[SkyWalkingService] = await self._run(op_list_services(self._state, layer))
        return result

    async def find_service(self, name: str) -> SkyWalkingService | None:
        """See :meth:`SkyWalkingQueryClient.find_service`."""
        result: SkyWalkingService | None = await self._run(op_find_service(self._state, name))
        return result

    async def list_endpoints(
        self, service: str, keyword: str | None = None, *, limit: int = 100
    ) -> list[SkyWalkingEndpoint]:
        """See :meth:`SkyWalkingQueryClient.list_endpoints`."""
        result: list[SkyWalkingEndpoint] = await self._run(
            op_list_endpoints(self._state, service, keyword, limit)
        )
        return result

    async def list_instances(
        self,
        service: str,
        range: TimeRange | None = None,  # noqa: A002
    ) -> list[SkyWalkingInstance]:
        """See :meth:`SkyWalkingQueryClient.list_instances`."""
        result: list[SkyWalkingInstance] = await self._run(
            op_list_instances(self._state, service, range)
        )
        return result

    async def query_traces(
        self,
        *,
        service: str | None = None,
        instance: str | None = None,
        endpoint: str | None = None,
        trace_id: str | None = None,
        trace_state: TraceState = "ALL",
        min_duration_ms: float | None = None,
        max_duration_ms: float | None = None,
        tags: Mapping[str, str] | None = None,
        range: TimeRange | None = None,  # noqa: A002
        paging: Paging | None = None,
        order: TraceOrder = "BY_START_TIME",
    ) -> QueryTracesResult:
        """See :meth:`SkyWalkingQueryClient.query_traces`."""
        result: QueryTracesResult = await self._run(
            op_query_traces(
                self._state,
                service=service,
                instance=instance,
                endpoint=endpoint,
                trace_id=trace_id,
                trace_state=trace_state,
                min_duration_ms=min_duration_ms,
                max_duration_ms=max_duration_ms,
                tags=tags,
                range=range,
                paging=paging,
                order=order,
            )
        )
        return result

    async def get_trace(self, trace_id: str) -> NativeTrace | None:
        """See :meth:`SkyWalkingQueryClient.get_trace`."""
        result: NativeTrace | None = await self._run(op_get_trace(self._state, trace_id))
        return result

    async def query_logs(
        self,
        *,
        service: str | None = None,
        instance: str | None = None,
        endpoint: str | None = None,
        trace_id: str | None = None,
        segment_id: str | None = None,
        span_id: int | None = None,
        keywords: Sequence[str] = (),
        excluding_keywords: Sequence[str] = (),
        tags: Mapping[str, str] | None = None,
        range: TimeRange | None = None,  # noqa: A002
        paging: Paging | None = None,
        order: LogOrder = "DES",
    ) -> QueryLogsResult:
        """See :meth:`SkyWalkingQueryClient.query_logs`."""
        result: QueryLogsResult = await self._run(
            op_query_logs(
                self._state,
                service=service,
                instance=instance,
                endpoint=endpoint,
                trace_id=trace_id,
                segment_id=segment_id,
                span_id=span_id,
                keywords=keywords,
                excluding_keywords=excluding_keywords,
                tags=tags,
                range=range,
                paging=paging,
                order=order,
            )
        )
        return result

    async def list_log_tag_keys(self, range: TimeRange | None = None) -> list[str]:  # noqa: A002
        """See :meth:`SkyWalkingQueryClient.list_log_tag_keys`."""
        result: list[str] = await self._run(op_list_log_tag_keys(self._state, range))
        return result

    async def topology(
        self,
        range: TimeRange | None = None,  # noqa: A002
        *,
        service: str | None = None,
        layer: str | None = None,
    ) -> Topology:
        """See :meth:`SkyWalkingQueryClient.topology`."""
        result: Topology = await self._run(op_topology(self._state, range, service, layer))
        return result
