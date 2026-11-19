"""Logging for SkyWalking: the native log exporter (``POST /v3/logs``) and the log query client
over GraphQL ``queryLogs`` with client-side text filtering (service-owned)."""

from __future__ import annotations

import dataclasses
import json
import re
from collections.abc import Mapping, Sequence
from datetime import datetime, timedelta, timezone
from typing import Any

from . import query as _q
from ._api import Op, State, run_async, run_sync
from ._core import AsyncHttpTransport, CancelToken, HttpTransport, Request, ValidationError
from ._logging import (
    AsyncBaseLogQueryClient,
    AttrValue,
    BaseLogQueryClient,
    ExportResult,
    LogRecord,
    LogSearchResult,
    SearchOrder,
    Severity,
)
from .duration import TimeRange, epoch_ms
from .query import NativeLog

__all__ = [
    "DEFAULT_LOG_SERVICE_NAME",
    "DEFAULT_MAX_SCAN_PAGES",
    "LOG_SCAN_PAGE_SIZE",
    "AsyncSkyWalkingLogExporter",
    "AsyncSkyWalkingLogQueryClient",
    "ParsedLogQuery",
    "SkyWalkingLogExporter",
    "SkyWalkingLogQueryClient",
    "log_to_record",
    "matches_text",
    "parse_log_query",
    "record_to_log_data",
    "tag_text",
]

#: Service name used when a record has no ``service.name`` resource attribute.
DEFAULT_LOG_SERVICE_NAME = "unknown_service"
#: Raw page size used while filtering free text client side.
LOG_SCAN_PAGE_SIZE = 1000
#: Default number of raw pages one ``search`` call may scan.
DEFAULT_MAX_SCAN_PAGES = 10

_HEX32 = re.compile(r"^[0-9a-f]{32}$")
_HEX16 = re.compile(r"^[0-9a-f]{16}$")
_TOKEN = re.compile(r'([A-Za-z_][\w.-]*)=(?:"([^"]*)"|(\S+))|"([^"]*)"|(\S+)')
_CURSOR = re.compile(r"^(\d+):(\d+):(\d+)$")
_EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)


def tag_text(value: AttrValue) -> str:
    """An attribute value in SkyWalking's string tag form (JSON for lists and mappings)."""
    if isinstance(value, str):
        return value
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False, default=str)


def record_to_log_data(
    record: LogRecord, default_service: str = DEFAULT_LOG_SERVICE_NAME
) -> dict[str, Any]:
    """Map a SPEC :class:`LogRecord` to native ``LogData`` (protobuf JSON mapping).

    ``resource["service.name"]`` → ``service``, ``service.instance.id`` → ``serviceInstance``,
    body → ``body.text.text``, ``severity_text`` → tag ``level``, attributes (stringified,
    nested values as JSON) → tags, ``trace_id`` → ``traceContext.traceId`` (enables
    ``relatedTrace`` queries), ``span_id`` → tag ``span_id``.
    """
    tags = [{"key": "level", "value": record.severity_text or Severity(record.severity).name}]
    tags.extend(
        {"key": str(k), "value": tag_text(v)} for k, v in record.attributes.items() if k != "level"
    )
    if record.span_id:
        tags.append({"key": "span_id", "value": record.span_id})
    service = record.resource.get("service.name")
    out: dict[str, Any] = {
        "timestamp": epoch_ms(record.timestamp),
        "service": tag_text(service) if service is not None else default_service,
        "body": {"text": {"text": record.body}},
        "tags": {"data": tags},
        "layer": "GENERAL",
    }
    instance = record.resource.get("service.instance.id")
    if instance is not None:
        out["serviceInstance"] = tag_text(instance)
    if record.trace_id:
        out["traceContext"] = {"traceId": record.trace_id}
    return out


def log_to_record(log: NativeLog) -> LogRecord:
    """Map a stored log back to a SPEC :class:`LogRecord`.

    Tag ``level`` → severity/severity_text (default INFO), ``span_id`` → ``span_id`` (16 hex), a
    32-hex ``trace_id`` → ``trace_id`` (native trace ids go to attribute ``sw.trace_id``), other
    tags → attributes (strings), service/instance → resource ``service.name`` /
    ``service.instance.id``, endpoint → resource ``sw.endpoint``.
    """
    attributes: dict[str, AttrValue] = {}
    level: str | None = None
    span_id: str | None = None
    for kv in log.tags:
        if kv.key == "level":
            level = kv.value
        elif kv.key == "span_id" and _HEX16.match(kv.value):
            span_id = kv.value
        else:
            attributes[kv.key] = kv.value
    resource: dict[str, AttrValue] = {"service.name": log.service_name}
    if log.service_instance_name:
        resource["service.instance.id"] = log.service_instance_name
    if log.endpoint_name:
        resource["sw.endpoint"] = log.endpoint_name
    try:
        severity = Severity.parse(level) if level else Severity.INFO
    except ValidationError:
        severity = Severity.INFO
    trace_id: str | None = None
    if _HEX32.match(log.trace_id.lower()):
        trace_id = log.trace_id.lower()
    elif log.trace_id:
        attributes["sw.trace_id"] = log.trace_id
    return LogRecord(
        body=log.content,
        severity=severity,
        timestamp=_EPOCH + timedelta(milliseconds=log.timestamp),
        severity_text=level or "INFO",
        attributes=attributes,
        resource=resource,
        trace_id=trace_id,
        span_id=span_id,
    )


def _export_request(records: Sequence[LogRecord], service: str, timeout_s: float) -> Request:
    return Request(
        "POST",
        "/v3/logs",
        operation="logging.export",
        json=[record_to_log_data(r, service) for r in records],
        timeout=timeout_s if timeout_s > 0 else None,
    )


class SkyWalkingLogExporter:
    """:class:`LogExporter` posting batches to OAP's native log API (``POST /v3/logs``, JSON
    array, port 12800).

    Native logs carry the trace context, register the service and are queryable a few seconds
    later. The body is sent uncompressed (``gzip`` is ignored: compression is not verified on
    OAP's HTTP receiver). Transport errors are raised (the batch processor retries retryable
    ones).
    """

    def __init__(
        self,
        transport: HttpTransport,
        *,
        service_name: str = DEFAULT_LOG_SERVICE_NAME,
        owns_transport: bool = False,
    ) -> None:
        self._transport = transport
        self._service = service_name
        self._owns = owns_transport
        self._shut_down = False

    def export(
        self, records: Sequence[LogRecord], *, gzip: bool = False, timeout_s: float = 30.0
    ) -> ExportResult:
        """Post one batch; returns how many records OAP accepted."""
        if not records:
            return ExportResult(accepted=0)
        resp = self._transport.request(_export_request(records, self._service, timeout_s))
        return ExportResult(accepted=len(records), retries=resp.attempts - 1)

    def shutdown(self) -> None:
        """Idempotent; closes the transport when the exporter owns it."""
        if self._shut_down:
            return
        self._shut_down = True
        if self._owns:
            self._transport.close()


class AsyncSkyWalkingLogExporter:
    """Asynchronous :class:`SkyWalkingLogExporter` (:class:`AsyncLogExporter`)."""

    def __init__(
        self,
        transport: AsyncHttpTransport,
        *,
        service_name: str = DEFAULT_LOG_SERVICE_NAME,
        owns_transport: bool = False,
    ) -> None:
        self._transport = transport
        self._service = service_name
        self._owns = owns_transport
        self._shut_down = False

    async def export(
        self, records: Sequence[LogRecord], *, gzip: bool = False, timeout_s: float = 30.0
    ) -> ExportResult:
        """Post one batch; returns how many records OAP accepted."""
        if not records:
            return ExportResult(accepted=0)
        resp = await self._transport.request(_export_request(records, self._service, timeout_s))
        return ExportResult(accepted=len(records), retries=resp.attempts - 1)

    async def shutdown(self) -> None:
        """Idempotent; closes the transport when the exporter owns it."""
        if self._shut_down:
            return
        self._shut_down = True
        if self._owns:
            await self._transport.aclose()


@dataclasses.dataclass(frozen=True)
class ParsedLogQuery:
    """Parsed form of a log search query (see :func:`parse_log_query`)."""

    service: str | None = None
    instance: str | None = None
    endpoint: str | None = None
    trace_id: str | None = None
    #: Server-side tag filters (allow-listed keys only).
    tags: Mapping[str, str] = dataclasses.field(default_factory=dict)
    #: Free-text terms, matched client side.
    text: tuple[str, ...] = ()


def parse_log_query(query: str) -> ParsedLogQuery:
    """Parse the log query syntax.

    Space-separated ``key=value`` terms (``service=``, ``instance=``, ``endpoint=``,
    ``trace_id=``/``traceId=`` map to the GraphQL condition; any other key is a server tag
    filter and must be in ``SW_SEARCHABLE_LOGS_TAG_KEYS``) plus free-text terms (``"quoted
    phrases"`` allowed). Every free-text term must occur (case-insensitive) in the content or in
    a tag value.
    """
    fields: dict[str, str] = {}
    tags: dict[str, str] = {}
    text: list[str] = []
    for m in _TOKEN.finditer(query or ""):
        key = m.group(1)
        if key is not None:
            value = m.group(2) if m.group(2) is not None else (m.group(3) or "")
            if key in ("service", "instance", "endpoint"):
                fields[key] = value
            elif key in ("trace_id", "traceId"):
                fields["trace_id"] = value
            else:
                tags[key] = value
        else:
            term = m.group(4) if m.group(4) is not None else (m.group(5) or "")
            if term:
                text.append(term)
    return ParsedLogQuery(
        service=fields.get("service"),
        instance=fields.get("instance"),
        endpoint=fields.get("endpoint"),
        trace_id=fields.get("trace_id"),
        tags=tags,
        text=tuple(text),
    )


def matches_text(log: NativeLog, terms: Sequence[str]) -> bool:
    """True when every term occurs (case-insensitive) in the content or a tag value."""
    if not terms:
        return True
    haystack = "\n".join([log.content, *(t.value for t in log.tags)]).lower()
    return all(t.lower() in haystack for t in terms)


def _decode_cursor(cursor: str) -> tuple[int, int, int]:
    m = _CURSOR.match(cursor)
    if m is None:
        raise ValidationError("logging.search: invalid cursor")
    page, offset, size = (int(x) for x in m.groups())
    if page < 1 or not 1 <= size <= 10_000:
        raise ValidationError("logging.search: invalid cursor")
    return page, offset, size


def op_search(
    state: State,
    max_scan_pages: int,
    query: str,
    start: datetime | None,
    end: datetime | None,
    limit: int,
    order: SearchOrder,
    cursor: str | None,
) -> Op[LogSearchResult]:
    operation = "logging.search"
    if isinstance(limit, bool) or not isinstance(limit, int) or not 1 <= limit <= 10_000:
        raise ValidationError(f"{operation}: limit must be 1..10000")
    parsed = parse_log_query(query)
    end_ms = epoch_ms(end) if end is not None else state.now()
    start_ms = epoch_ms(start) if start is not None else end_ms - 3_600_000
    if start_ms > end_ms:
        raise ValidationError(f"{operation}: invalid time window")
    if cursor is None:
        page, offset, size = 1, 0, (LOG_SCAN_PAGE_SIZE if parsed.text else limit)
    else:
        page, offset, size = _decode_cursor(cursor)
    window = TimeRange(start=_dt(start_ms), end=_dt(end_ms), step="MINUTE")
    records: list[LogRecord] = []
    for _ in range(max_scan_pages):
        res = yield from _q.op_query_logs(
            state,
            service=parsed.service,
            instance=parsed.instance,
            endpoint=parsed.endpoint,
            trace_id=parsed.trace_id,
            tags=parsed.tags or None,
            range=window,
            paging=_q.Paging(page, size),
            order="ASC" if order == "asc" else "DES",
        )
        logs = res.logs
        for i in range(offset, len(logs)):
            log = logs[i]
            if log.timestamp < start_ms or log.timestamp > end_ms:
                continue
            if not matches_text(log, parsed.text):
                continue
            records.append(log_to_record(log))
            if len(records) >= limit:
                if i + 1 < len(logs):
                    return LogSearchResult(records, f"{page}:{i + 1}:{size}")
                if len(logs) >= size:
                    return LogSearchResult(records, f"{page + 1}:0:{size}")
                return LogSearchResult(records)
        if len(logs) < size:
            return LogSearchResult(records)
        page += 1
        offset = 0
    return LogSearchResult(records, f"{page}:0:{size}")


def _dt(ms: int) -> datetime:
    return _EPOCH + timedelta(milliseconds=ms)


def op_list_streams(state: State) -> Op[list[str]]:
    services = yield from _q.op_list_services(state, None)
    return sorted({s.name for s in services if s.normal})


def _check_pages(max_scan_pages: int) -> int:
    if (
        isinstance(max_scan_pages, bool)
        or not isinstance(max_scan_pages, int)
        or max_scan_pages < 1
    ):
        raise ValidationError("logging: max_scan_pages must be a positive integer")
    return max_scan_pages


class SkyWalkingLogQueryClient(BaseLogQueryClient):
    """:class:`LogQueryClient` for SkyWalking (GraphQL ``queryLogs``).

    OAP on BanyanDB ignores content keywords, so free-text terms are filtered client side:
    ``search`` reads raw pages of 1000 logs (newest first by default) and keeps the matching
    ones until ``limit`` matches or ``max_scan_pages`` pages; ``next_cursor``
    (``"page:offset:size"``) continues where it stopped. Without free text the page size is
    ``limit`` and only the exact time window is filtered. Default window: the last hour. See
    :func:`parse_log_query` for the syntax. Obtain it from ``SkyWalkingClient.logs``.
    """

    def __init__(
        self,
        state: State,
        transports: Mapping[str, HttpTransport],
        *,
        max_scan_pages: int = DEFAULT_MAX_SCAN_PAGES,
    ) -> None:
        self._state = state
        self._transports = transports
        self._max_pages = _check_pages(max_scan_pages)

    def search(
        self,
        query: str,
        *,
        start: datetime | None = None,
        end: datetime | None = None,
        limit: int = 100,
        order: SearchOrder = "desc",
        cursor: str | None = None,
        cancel: CancelToken | None = None,
    ) -> LogSearchResult:
        """Search logs (see the class description for the query syntax and paging)."""
        op = op_search(self._state, self._max_pages, query, start, end, limit, order, cursor)
        return run_sync(op, self._transports, cancel)

    def list_streams(self) -> list[str]:
        """Service names known to OAP (SkyWalking has no streams; the service is the
        equivalent)."""
        return run_sync(op_list_streams(self._state), self._transports)

    def list_labels(self) -> list[str]:
        """Log tag keys seen in the last hour (``queryLogTagAutocompleteKeys``)."""
        return run_sync(_q.op_list_log_tag_keys(self._state, None), self._transports)


class AsyncSkyWalkingLogQueryClient(AsyncBaseLogQueryClient):
    """Asynchronous :class:`SkyWalkingLogQueryClient`. Obtain it from
    ``AsyncSkyWalkingClient.logs``."""

    def __init__(
        self,
        state: State,
        transports: Mapping[str, AsyncHttpTransport],
        *,
        max_scan_pages: int = DEFAULT_MAX_SCAN_PAGES,
    ) -> None:
        self._state = state
        self._transports = transports
        self._max_pages = _check_pages(max_scan_pages)

    async def search(
        self,
        query: str,
        *,
        start: datetime | None = None,
        end: datetime | None = None,
        limit: int = 100,
        order: SearchOrder = "desc",
        cursor: str | None = None,
    ) -> LogSearchResult:
        """See :meth:`SkyWalkingLogQueryClient.search`."""
        op = op_search(self._state, self._max_pages, query, start, end, limit, order, cursor)
        return await run_async(op, self._transports)

    async def list_streams(self) -> list[str]:
        """See :meth:`SkyWalkingLogQueryClient.list_streams`."""
        return await run_async(op_list_streams(self._state), self._transports)

    async def list_labels(self) -> list[str]:
        """See :meth:`SkyWalkingLogQueryClient.list_labels`."""
        return await run_async(_q.op_list_log_tag_keys(self._state, None), self._transports)
