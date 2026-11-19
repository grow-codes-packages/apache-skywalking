"""SPEC §5 ``TraceQueryClient`` over OAP's two trace stores (service-owned).

OTLP spans (what :func:`init_tracing` sends) are stored in OAP's Zipkin model and read via the
Zipkin query API (``:9412/zipkin``, needs ``SW_QUERY_ZIPKIN``); SkyWalking agent segments are
read via GraphQL. The backend comes from ``SkyWalkingConfig.trace_backend``:

* ``get_trace``: ``zipkin`` → Zipkin; ``native`` → GraphQL; ``auto`` (default) → hex ids via
  Zipkin first, then GraphQL; other ids → GraphQL.
* ``search_traces``: ``native`` → GraphQL ``queryTraces``; ``zipkin`` and ``auto`` → Zipkin.
* ``list_services``, ``list_operations``: ``zipkin`` → Zipkin; ``native`` → GraphQL; ``auto``
  → the union of both.
* ``get_dependencies``: ``zipkin`` → :class:`UnsupportedError`; ``native`` and ``auto`` → the
  native topology.

Native traces keep their opaque ids (``<hex>.<n>.<ms>``); native span ids are
``"<segmentId>-<spanId>"``.
"""

from __future__ import annotations

import math
from collections.abc import Iterable, Mapping
from datetime import datetime, timedelta, timezone
from typing import Any

from . import query as _q
from ._api import Op, State, run_async, run_sync, zipkin_get
from ._core import (
    AsyncHttpTransport,
    CancelToken,
    HttpTransport,
    NotFoundError,
    UnsupportedError,
    ValidationError,
    apoll_until,
    poll_until,
    to_seconds,
)
from ._tracing import (
    AsyncBaseTraceQueryClient,
    BaseTraceQueryClient,
    DependencyLink,
    Trace,
    TraceSearchQuery,
    TraceSummary,
    async_wait_for_trace,
    wait_for_trace,
)
from .config import TraceBackend
from .duration import TimeRange, epoch_ms
from .native import native_trace_to_trace, topology_to_dependencies
from .zipkin import (
    is_hex_trace_id,
    strings_from_body,
    summarize,
    zipkin_spans_from_body,
    zipkin_trace_id,
    zipkin_trace_to_trace,
    zipkin_traces_from_body,
)

__all__ = [
    "DEFAULT_TRACE_SEARCH_LIMIT",
    "AsyncSkyWalkingTraceQueryClient",
    "SkyWalkingTraceQueryClient",
]

#: Default ``limit`` of ``search_traces`` (SPEC §5).
DEFAULT_TRACE_SEARCH_LIMIT = 20


def _unique_sorted(values: Iterable[str]) -> list[str]:
    return sorted({v for v in values if v})


def _op_zipkin_trace(trace_id: str) -> Op[Trace | None]:
    operation = "traces.get_trace"
    tid = zipkin_trace_id(trace_id, operation)
    resp = yield from zipkin_get(f"/api/v2/trace/{tid}", operation, allow_404=True)
    if resp.status == 404:
        return None
    return zipkin_trace_to_trace(zipkin_spans_from_body(resp.json()))


def _op_native_trace(state: State, trace_id: str) -> Op[Trace | None]:
    native = yield from _q.op_get_trace(state, trace_id)
    return native_trace_to_trace(native)


def op_get_trace(state: State, backend: TraceBackend, trace_id: str) -> Op[Trace | None]:
    if not isinstance(trace_id, str) or not trace_id.strip():
        raise ValidationError("traces.get_trace: trace_id is required")
    if backend == "zipkin":
        return (yield from _op_zipkin_trace(trace_id))
    if backend == "native" or not is_hex_trace_id(trace_id):
        return (yield from _op_native_trace(state, trace_id.strip()))
    found = yield from _op_zipkin_trace(trace_id)
    if found is not None:
        return found
    return (yield from _op_native_trace(state, trace_id.strip().lower()))


def op_search_traces(
    state: State, backend: TraceBackend, query: TraceSearchQuery | None
) -> Op[list[TraceSummary]]:
    operation = "traces.search_traces"
    q = query or TraceSearchQuery()
    limit = q.limit
    if isinstance(limit, bool) or not isinstance(limit, int) or limit <= 0:
        raise ValidationError(f"{operation}: limit must be a positive integer")
    for value in (q.min_duration_micros, q.max_duration_micros):
        if value is not None and not value >= 0:
            raise ValidationError(f"{operation}: durations must be non-negative")
    end_ms = epoch_ms(q.end) if q.end is not None else state.now()
    start_ms = epoch_ms(q.start) if q.start is not None else None
    if end_ms <= 0 or (start_ms is not None and start_ms <= 0):
        raise ValidationError(f"{operation}: start and end must be valid times")
    if start_ms is not None and start_ms > end_ms:
        raise ValidationError(f"{operation}: start must not be after end")
    if backend == "native":
        if q.operation is not None and q.service is None:
            raise ValidationError(f"{operation}: operation needs service on the native backend")
        res = yield from _q.op_query_traces(
            state,
            service=q.service,
            endpoint=q.operation,
            tags=q.tags or None,
            min_duration_ms=None
            if q.min_duration_micros is None
            else math.floor(q.min_duration_micros / 1000),
            max_duration_ms=None
            if q.max_duration_micros is None
            else math.ceil(q.max_duration_micros / 1000),
            range=TimeRange(
                start=_dt(start_ms if start_ms is not None else end_ms - 3_600_000),
                end=_dt(end_ms),
            ),
            paging=_q.Paging(1, limit),
        )
        out = [native_trace_to_trace(t) for t in res.traces]
        return [summarize(t) for t in out if t is not None]
    terms = [f"{k}={v}" for k, v in q.tags.items()]
    if any(" and " in t for t in terms):
        raise ValidationError(f'{operation}: tags must not contain " and "')
    params: dict[str, Any] = {
        "serviceName": q.service,
        "spanName": q.operation.lower() if q.operation is not None else None,
        "annotationQuery": " and ".join(terms) if terms else None,
        "minDuration": None if q.min_duration_micros is None else round(q.min_duration_micros),
        "maxDuration": None if q.max_duration_micros is None else round(q.max_duration_micros),
        "endTs": end_ms,
        "lookback": None if start_ms is None else max(1, end_ms - start_ms),
        "limit": limit,
    }
    resp = yield from zipkin_get("/api/v2/traces", operation, params)
    traces = [zipkin_trace_to_trace(t) for t in zipkin_traces_from_body(resp.json())]
    return [summarize(t) for t in traces if t is not None]


def _dt(ms: int) -> datetime:
    return datetime(1970, 1, 1, tzinfo=timezone.utc) + timedelta(milliseconds=ms)


def op_list_services(state: State, backend: TraceBackend) -> Op[list[str]]:
    names: list[str] = []
    if backend != "native":
        resp = yield from zipkin_get("/api/v2/services", "traces.list_services")
        names.extend(strings_from_body(resp.json()))
    if backend != "zipkin":
        services = yield from _q.op_list_services(state, None)
        names.extend(s.name for s in services if s.normal)
    return _unique_sorted(names)


def op_list_operations(state: State, backend: TraceBackend, service: str) -> Op[list[str]]:
    operation = "traces.list_operations"
    if not isinstance(service, str) or not service.strip():
        raise ValidationError(f"{operation}: service is required")
    names: list[str] = []
    if backend != "native":
        resp = yield from zipkin_get("/api/v2/spans", operation, {"serviceName": service})
        names.extend(strings_from_body(resp.json()))
    if backend != "zipkin":
        endpoints = yield from _q.op_list_endpoints(state, service, None, 100)
        names.extend(e.name for e in endpoints)
    return _unique_sorted(names)


def op_get_dependencies(
    state: State, backend: TraceBackend, start: datetime | None, end: datetime | None
) -> Op[list[DependencyLink]]:
    if backend == "zipkin":
        raise UnsupportedError(
            "traces.get_dependencies: OAP does not implement the Zipkin dependencies API"
        )
    topology = yield from _q.op_topology(state, TimeRange(start=start, end=end), None, None)
    return topology_to_dependencies(topology)


def _enough(trace: Trace | None, min_spans: int) -> Trace | None:
    return trace if trace is not None and len(trace.spans) >= min_spans else None


class SkyWalkingTraceQueryClient(BaseTraceQueryClient):
    """:class:`TraceQueryClient` for SkyWalking (see the module description for the backends).

    Obtain it from ``SkyWalkingClient.traces``. Every method accepts ``cancel=``.
    """

    def __init__(
        self, state: State, transports: Mapping[str, HttpTransport], backend: TraceBackend
    ) -> None:
        self._state = state
        self._transports = transports
        #: The configured backend (``auto``, ``zipkin`` or ``native``).
        self.backend: TraceBackend = backend

    def _run(self, op: Op[Any], cancel: CancelToken | None) -> Any:
        return run_sync(op, self._transports, cancel)

    def get_trace(self, trace_id: str, *, cancel: CancelToken | None = None) -> Trace | None:
        """One trace in the SPEC model, ``None`` when it does not exist. Zipkin rejects non-hex
        ids with :class:`ValidationError`."""
        result: Trace | None = self._run(op_get_trace(self._state, self.backend, trace_id), cancel)
        return result

    def search_traces(
        self, query: TraceSearchQuery | None = None, *, cancel: CancelToken | None = None
    ) -> list[TraceSummary]:
        """SPEC search.

        Zipkin: ``service`` → ``serviceName``, ``operation`` → ``spanName`` (lower-cased like
        OAP stores names), ``tags`` → ``annotationQuery`` (``k=v and …``; keys must be in
        ``SW_ZIPKIN_SEARCHABLE_TAG_KEYS``), durations in µs, ``endTs`` = ``end`` (default now),
        ``lookback`` = ``end - start``. Native: ``queryTraces`` with ``operation`` as endpoint
        name and durations in ms (default window: last hour).
        """
        result: list[TraceSummary] = self._run(
            op_search_traces(self._state, self.backend, query), cancel
        )
        return result

    def list_services(self, *, cancel: CancelToken | None = None) -> list[str]:
        """Service names (sorted, unique). New services appear about 30-35 s after their first
        data (OAP flushes its name index periodically)."""
        result: list[str] = self._run(op_list_services(self._state, self.backend), cancel)
        return result

    def list_operations(self, service: str, *, cancel: CancelToken | None = None) -> list[str]:
        """Operation names of a service (Zipkin span names and/or native endpoint names)."""
        result: list[str] = self._run(
            op_list_operations(self._state, self.backend, service), cancel
        )
        return result

    def get_dependencies(
        self,
        *,
        start: datetime | None = None,
        end: datetime | None = None,
        cancel: CancelToken | None = None,
    ) -> list[DependencyLink]:
        """Service dependencies from the native topology (``call_count`` is 0: the topology
        carries no counts; default window last hour). OAP 10.4 does not implement Zipkin
        ``/dependencies``, so the ``zipkin`` backend raises :class:`UnsupportedError`; OTLP
        spans create no topology edges."""
        result: list[DependencyLink] = self._run(
            op_get_dependencies(self._state, self.backend, start, end), cancel
        )
        return result

    def wait_for_trace(
        self,
        trace_id: str,
        *,
        timeout_s: float | timedelta = 30.0,
        interval_s: float | timedelta = 1.0,
        min_spans: int = 1,
    ) -> Trace:
        """Poll :meth:`get_trace` until the trace has ``min_spans`` spans (``TimeoutError``
        otherwise). Native ids are used verbatim; hex ids are normalized."""
        if is_hex_trace_id(trace_id):
            return wait_for_trace(
                self, trace_id, timeout_s=timeout_s, interval_s=interval_s, min_spans=min_spans
            )

        def attempt() -> Trace | None:
            try:
                return _enough(self.get_trace(trace_id), min_spans)
            except NotFoundError:
                return None

        return poll_until(
            attempt,
            timeout_s=to_seconds(timeout_s),
            interval_s=to_seconds(interval_s),
            operation=f"traces.wait_for_trace {trace_id}",
        )


class AsyncSkyWalkingTraceQueryClient(AsyncBaseTraceQueryClient):
    """Asynchronous :class:`SkyWalkingTraceQueryClient` (same methods, awaited). Obtain it from
    ``AsyncSkyWalkingClient.traces``."""

    def __init__(
        self, state: State, transports: Mapping[str, AsyncHttpTransport], backend: TraceBackend
    ) -> None:
        self._state = state
        self._transports = transports
        #: The configured backend (``auto``, ``zipkin`` or ``native``).
        self.backend: TraceBackend = backend

    async def _run(self, op: Op[Any]) -> Any:
        return await run_async(op, self._transports)

    async def get_trace(self, trace_id: str) -> Trace | None:
        """See :meth:`SkyWalkingTraceQueryClient.get_trace`."""
        result: Trace | None = await self._run(op_get_trace(self._state, self.backend, trace_id))
        return result

    async def search_traces(self, query: TraceSearchQuery | None = None) -> list[TraceSummary]:
        """See :meth:`SkyWalkingTraceQueryClient.search_traces`."""
        result: list[TraceSummary] = await self._run(
            op_search_traces(self._state, self.backend, query)
        )
        return result

    async def list_services(self) -> list[str]:
        """See :meth:`SkyWalkingTraceQueryClient.list_services`."""
        result: list[str] = await self._run(op_list_services(self._state, self.backend))
        return result

    async def list_operations(self, service: str) -> list[str]:
        """See :meth:`SkyWalkingTraceQueryClient.list_operations`."""
        result: list[str] = await self._run(op_list_operations(self._state, self.backend, service))
        return result

    async def get_dependencies(
        self, *, start: datetime | None = None, end: datetime | None = None
    ) -> list[DependencyLink]:
        """See :meth:`SkyWalkingTraceQueryClient.get_dependencies`."""
        result: list[DependencyLink] = await self._run(
            op_get_dependencies(self._state, self.backend, start, end)
        )
        return result

    async def wait_for_trace(
        self,
        trace_id: str,
        *,
        timeout_s: float | timedelta = 30.0,
        interval_s: float | timedelta = 1.0,
        min_spans: int = 1,
    ) -> Trace:
        """See :meth:`SkyWalkingTraceQueryClient.wait_for_trace`."""
        if is_hex_trace_id(trace_id):
            return await async_wait_for_trace(
                self, trace_id, timeout_s=timeout_s, interval_s=interval_s, min_spans=min_spans
            )

        async def attempt() -> Trace | None:
            try:
                return _enough(await self.get_trace(trace_id), min_spans)
            except NotFoundError:
                return None

        return await apoll_until(
            attempt,
            timeout_s=to_seconds(timeout_s),
            interval_s=to_seconds(interval_s),
            operation=f"traces.wait_for_trace {trace_id}",
        )
