"""Shared OAP access layer (internal).

Every service operation is written once as a generator that yields :class:`Call` objects and
receives :class:`Response` objects back; :func:`run_sync` and :func:`run_async` drive the same
generator over the sync or async transports. Errors raised by a transport are thrown into the
generator, so an operation may catch them (``health`` does).
"""

from __future__ import annotations

import dataclasses
import re
import time
from collections.abc import Callable, Generator, Mapping
from typing import Any, Literal, TypeVar

from ._core import (
    AsyncHttpTransport,
    CancelToken,
    HttpTransport,
    Request,
    Response,
    ServiceError,
    UnsupportedError,
    ValidationError,
    error_from_status,
    redact_text,
    truncate,
)
from .config import SkyWalkingConfig
from .duration import GraphQLDuration, TimeRange, parse_timezone, resolve_range, to_graphql_duration

__all__ = [
    "Call",
    "GraphQLErrorInfo",
    "GraphQLResponse",
    "Op",
    "SkyWalkingCapabilities",
    "TimeInfo",
    "graphql_error",
    "graphql_errors",
]

T = TypeVar("T")

Target = Literal["oap", "zipkin"]
_MUTATION = re.compile(r"^\s*mutation\b", re.IGNORECASE)
_SPACES = re.compile(r"\s+")


@dataclasses.dataclass(frozen=True)
class Call:
    """One HTTP request of an operation, sent to the OAP REST port or the Zipkin query API."""

    target: Target
    request: Request


Op = Generator[Call, Response, T]


@dataclasses.dataclass(frozen=True)
class GraphQLErrorInfo:
    """One entry of a GraphQL ``errors`` array."""

    message: str
    #: Field path the error belongs to (resolver errors).
    path: tuple[str | int, ...] | None = None
    #: graphql-java classification (``ValidationError``, ``DataFetchingException`` …).
    classification: str | None = None


@dataclasses.dataclass(frozen=True)
class GraphQLResponse:
    """Result of a raw GraphQL call: partial ``data`` is possible next to ``errors``."""

    data: dict[str, Any] | None
    errors: tuple[GraphQLErrorInfo, ...] = ()


@dataclasses.dataclass(frozen=True)
class SkyWalkingCapabilities:
    """Server features probed once per client."""

    #: ``queryTraces`` (V2 trace query) is available (``hasQueryTracesV2Support``).
    query_traces_v2: bool
    #: The log store honours ``keywordsOfContent`` (``supportQueryLogsByKeywords``; false on
    #: BanyanDB).
    logs_by_keywords: bool


@dataclasses.dataclass(frozen=True)
class TimeInfo:
    """OAP clock: ``getTimeInfo``."""

    #: OAP timezone, e.g. ``+0000``.
    timezone: str
    #: OAP time in epoch milliseconds.
    current_timestamp: int


def graphql_errors(body: Any) -> tuple[GraphQLErrorInfo, ...]:
    """Decode the ``errors`` array of a GraphQL response body."""
    if not isinstance(body, Mapping) or not isinstance(body.get("errors"), list):
        return ()
    out = []
    for e in body["errors"]:
        if not isinstance(e, Mapping):
            continue
        message = e.get("message")
        path = e.get("path")
        ext = e.get("extensions")
        classification = ext.get("classification") if isinstance(ext, Mapping) else None
        out.append(
            GraphQLErrorInfo(
                message if isinstance(message, str) else "",
                tuple(path) if isinstance(path, list) else None,
                classification if isinstance(classification, str) else None,
            )
        )
    return tuple(out)


def graphql_error(
    operation: str,
    errors: tuple[GraphQLErrorInfo, ...] | list[GraphQLErrorInfo],
    known_secrets: list[str] | None = None,
) -> ServiceError:
    """Map GraphQL errors to an SDK error.

    ``UnsupportedOperationException`` → :class:`UnsupportedError`; anything else →
    :class:`ValidationError` (bad input caught by the resolver). The message is redacted and
    truncated to 300 characters.
    """
    raw = next((e.message for e in errors if e.message), "GraphQL error")
    text = truncate(redact_text(_SPACES.sub(" ", raw).strip(), known_secrets or []), 300)
    if "UnsupportedOperationException" in raw:
        return UnsupportedError(f"{operation}: {text}")
    return ValidationError(f"{operation}: {text}")


def _now_ms() -> int:
    return int(time.time() * 1000)


class State:
    """Per-client state shared by the modules: config, clock and cached server facts."""

    def __init__(self, config: SkyWalkingConfig, now: Callable[[], int] = _now_ms) -> None:
        self.config = config
        self.now = now
        self.offset_minutes: int | None = None
        self.capabilities: SkyWalkingCapabilities | None = None


def is_object(value: Any) -> bool:
    return isinstance(value, Mapping)


def graphql(
    state: State, query: str, variables: Mapping[str, Any] | None, operation: str
) -> Op[GraphQLResponse]:
    """Raw GraphQL: ``POST <url>/graphql``. Queries are idempotent (retried), mutations not."""
    if not isinstance(query, str) or not query.strip():
        raise ValidationError(f"{operation}: query is required")
    resp = yield Call(
        "oap",
        Request(
            "POST",
            state.config.graphql_path,
            operation=operation,
            json={"query": query, "variables": dict(variables or {})},
            idempotent=not _MUTATION.match(query),
        ),
    )
    body = resp.json()
    data = body.get("data") if isinstance(body, Mapping) else None
    return GraphQLResponse(
        dict(data) if isinstance(data, Mapping) else None,
        graphql_errors(body),
    )


def field(
    state: State, query: str, variables: Mapping[str, Any], name: str, operation: str
) -> Op[Any]:
    """Run a query and return ``data[name]``; raise on any GraphQL error."""
    res = yield from graphql(state, query, variables, operation)
    if res.errors:
        raise graphql_error(operation, res.errors, state.config.known_secrets())
    return None if res.data is None else res.data.get(name)


def time_info(state: State) -> Op[TimeInfo]:
    """``getTimeInfo`` (also caches the OAP timezone offset)."""
    info = yield from field(
        state, "{getTimeInfo{timezone currentTimestamp}}", {}, "getTimeInfo", "time_info"
    )
    info = info if isinstance(info, Mapping) else {}
    tz = info.get("timezone")
    timezone = tz if isinstance(tz, str) else "+0000"
    if state.offset_minutes is None:
        state.offset_minutes = parse_timezone(timezone)
    ts = info.get("currentTimestamp")
    current = ts if isinstance(ts, int) and not isinstance(ts, bool) else state.now()
    return TimeInfo(timezone, current)


def offset_minutes(state: State) -> Op[int]:
    """OAP timezone offset in minutes (fetched once, then cached)."""
    if state.offset_minutes is None:
        yield from time_info(state)
    return state.offset_minutes or 0


def duration(state: State, range: TimeRange | None, operation: str) -> Op[GraphQLDuration]:  # noqa: A002
    """GraphQL ``Duration`` for a window, in the OAP timezone."""
    resolved = resolve_range(range, state.now(), operation)
    offset = yield from offset_minutes(state)
    return to_graphql_duration(resolved, offset)


def capabilities(state: State) -> Op[SkyWalkingCapabilities]:
    """Server feature probe (cached after the first success)."""
    if state.capabilities is None:
        res = yield from graphql(
            state, "{hasQueryTracesV2Support supportQueryLogsByKeywords}", {}, "capabilities"
        )
        data = res.data or {}
        state.capabilities = SkyWalkingCapabilities(
            data.get("hasQueryTracesV2Support") is True,
            data.get("supportQueryLogsByKeywords") is True,
        )
    return state.capabilities


def zipkin_get(
    path: str,
    operation: str,
    params: Mapping[str, Any] | None = None,
    *,
    allow_404: bool = False,
) -> Op[Response]:
    """GET on the Zipkin query API (``<zipkin_url><path>``)."""
    resp = yield Call(
        "zipkin",
        Request(
            "GET",
            path,
            operation=operation,
            params=params,
            raise_for_status=not allow_404,
        ),
    )
    if allow_404 and resp.status >= 400 and resp.status != 404:
        raise error_from_status(resp.status, operation, content=resp.content, headers=resp.headers)
    return resp


def run_sync(
    op: Op[T],
    transports: Mapping[str, HttpTransport],
    cancel: CancelToken | None = None,
) -> T:
    """Drive an operation over the synchronous transports."""
    try:
        call = next(op)
        while True:
            try:
                resp = transports[call.target].request(call.request, cancel=cancel)
            except BaseException as exc:  # noqa: BLE001 - handed to the operation
                call = op.throw(exc)
            else:
                call = op.send(resp)
    except StopIteration as stop:
        return stop.value  # type: ignore[no-any-return]


async def run_async(op: Op[T], transports: Mapping[str, AsyncHttpTransport]) -> T:
    """Drive an operation over the asynchronous transports."""
    try:
        call = next(op)
        while True:
            try:
                resp = await transports[call.target].request(call.request)
            except BaseException as exc:  # noqa: BLE001 - handed to the operation
                call = op.throw(exc)
            else:
                call = op.send(resp)
    except StopIteration as stop:
        return stop.value  # type: ignore[no-any-return]
