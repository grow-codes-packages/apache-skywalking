"""Apache SkyWalking clients (sync and async).

Service-owned: health/ping/version, the GraphQL query API, the trace and log query clients, the
native log client and tracing setup for Apache SkyWalking OAP.
"""

from __future__ import annotations

import contextlib
import dataclasses
import inspect
import threading
import time
from collections.abc import Callable, Mapping
from types import TracebackType
from typing import Any

import httpx

from ._api import Call, GraphQLResponse, Op, State, field, graphql, run_async, run_sync, time_info
from ._core import (
    AsyncHttpTransport,
    CancelledError,
    CancelToken,
    ConfigError,
    HealthStatus,
    HttpTransport,
    Request,
    ResourceRegistry,
    Response,
    ServiceError,
    TimeoutError,
    UnavailableError,
    UnsupportedError,
    error_from_status,
)
from ._logging import AsyncBatchingLogClient, AttrValue, BatchingLogClient, BatchOptions
from ._logging import RedactionProcessor as _RedactionProcessor
from ._tracing import TracingConfig, TracingHandle
from .config import SkyWalkingConfig
from .logs import (
    DEFAULT_LOG_SERVICE_NAME,
    DEFAULT_MAX_SCAN_PAGES,
    AsyncSkyWalkingLogExporter,
    AsyncSkyWalkingLogQueryClient,
    SkyWalkingLogExporter,
    SkyWalkingLogQueryClient,
)
from .query import AsyncSkyWalkingQueryClient, SkyWalkingQueryClient
from .setup import init_tracing
from .traces import AsyncSkyWalkingTraceQueryClient, SkyWalkingTraceQueryClient

__all__ = [
    "AsyncSkyWalkingClient",
    "SkyWalkingClient",
    "create_async_log_client",
    "create_log_client",
]

_DEFAULT_REDACTION = _RedactionProcessor()
_HEALTH = Request("GET", "/healthcheck", operation="health", raise_for_status=False, max_retries=0)


def _health_from_error(err: ServiceError) -> HealthStatus:
    if isinstance(err, (CancelledError, ConfigError)):
        raise err
    word = "unreachable" if isinstance(err, (UnavailableError, TimeoutError)) else "unhealthy"
    return HealthStatus(healthy=False, status=word, details={"error": err.message})


def op_health(state: State) -> Op[HealthStatus]:
    """``GET /healthcheck`` (200 → ok, 503 → unhealthy); 404 → GraphQL ``checkHealth``."""
    try:
        resp: Response = yield Call("oap", _HEALTH)
        if resp.ok:
            return HealthStatus(True, "ok", {})
        if resp.status == 503:
            return HealthStatus(False, "unhealthy", {"httpStatus": "503"})
        if resp.status != 404:
            raise error_from_status(
                resp.status, "health", content=resp.content, headers=resp.headers
            )
        check = yield from field(state, "{checkHealth{score details}}", {}, "checkHealth", "health")
    except ServiceError as err:
        return _health_from_error(err)
    check = check if isinstance(check, Mapping) else {}
    score = check.get("score")
    score = score if isinstance(score, int) and not isinstance(score, bool) else -1
    if score == 0:
        return HealthStatus(True, "ok", {})
    details = {"score": str(score)}
    text = check.get("details")
    if isinstance(text, str) and text:
        details["details"] = text
    return HealthStatus(False, "unhealthy", details)


def op_server_version(state: State) -> Op[str]:
    """GraphQL ``{version}``."""
    version = yield from field(state, "{version}", {}, "version", "server_version")
    if not isinstance(version, str) or not version:
        raise UnsupportedError("server_version: the service does not report a version")
    return version


def _resource(
    resource: Mapping[str, AttrValue] | None,
    service_name: str | None,
    service_instance: str | None,
) -> dict[str, AttrValue]:
    out = dict(resource or {})
    if service_name is not None:
        out["service.name"] = service_name
    if service_instance is not None:
        out["service.instance.id"] = service_instance
    return out


def _zipkin_config(config: SkyWalkingConfig) -> SkyWalkingConfig:
    return dataclasses.replace(config, url=config.zipkin_url)


def create_log_client(
    config: SkyWalkingConfig | None = None,
    *,
    service_name: str | None = None,
    service_instance: str | None = None,
    options: BatchOptions | None = None,
    resource: Mapping[str, AttrValue] | None = None,
    redaction: _RedactionProcessor | None = _DEFAULT_REDACTION,
    correlate: bool = True,
    http_transport: httpx.BaseTransport | None = None,
) -> BatchingLogClient:
    """Standalone :class:`BatchingLogClient` that sends native logs to ``POST /v3/logs``.

    It owns its connections; ``close()`` flushes and releases them. ``service_name`` /
    ``service_instance`` become the ``service.name`` / ``service.instance.id`` resource
    attributes (records without ``service.name`` go to ``unknown_service``). ``config``
    defaults to :meth:`SkyWalkingConfig.from_env`.
    """
    cfg = (config or SkyWalkingConfig.from_env()).validate()
    transport = HttpTransport(cfg, known_secrets=cfg.known_secrets, http_transport=http_transport)
    exporter = SkyWalkingLogExporter(
        transport, service_name=service_name or DEFAULT_LOG_SERVICE_NAME, owns_transport=True
    )
    return BatchingLogClient(
        exporter,
        options=options,
        resource=_resource(resource, service_name, service_instance),
        redaction=redaction,
        correlate=correlate,
    )


def create_async_log_client(
    config: SkyWalkingConfig | None = None,
    *,
    service_name: str | None = None,
    service_instance: str | None = None,
    options: BatchOptions | None = None,
    resource: Mapping[str, AttrValue] | None = None,
    redaction: _RedactionProcessor | None = _DEFAULT_REDACTION,
    correlate: bool = True,
    http_transport: httpx.AsyncBaseTransport | None = None,
) -> AsyncBatchingLogClient:
    """Asynchronous :func:`create_log_client` (an :class:`AsyncBatchingLogClient`)."""
    cfg = (config or SkyWalkingConfig.from_env()).validate()
    transport = AsyncHttpTransport(
        cfg, known_secrets=cfg.known_secrets, http_transport=http_transport
    )
    exporter = AsyncSkyWalkingLogExporter(
        transport, service_name=service_name or DEFAULT_LOG_SERVICE_NAME, owns_transport=True
    )
    return AsyncBatchingLogClient(
        exporter,
        options=options,
        resource=_resource(resource, service_name, service_instance),
        redaction=redaction,
        correlate=correlate,
    )


class SkyWalkingClient:
    """Synchronous Apache SkyWalking OAP client. Thread-safe; use as a context manager or call
    :meth:`close`.

    Modules: :attr:`query` (GraphQL query API), :attr:`traces` (SPEC ``TraceQueryClient``),
    :attr:`logs` (SPEC ``LogQueryClient``); :meth:`log_client` and :meth:`init_tracing` create
    log clients and tracing handles that :meth:`close` flushes and shuts down. Every I/O method
    accepts ``cancel=`` (a :class:`CancelToken`) to abort from another thread.

    ``http_transport`` (an ``httpx`` transport, e.g. ``httpx.MockTransport``) and ``now``
    (epoch-ms clock) are test seams; ``max_scan_pages`` bounds the raw pages one log search may
    scan (default 10).
    """

    def __init__(
        self,
        config: SkyWalkingConfig | None = None,
        *,
        http_transport: httpx.BaseTransport | None = None,
        now: Callable[[], int] | None = None,
        max_scan_pages: int = DEFAULT_MAX_SCAN_PAGES,
    ) -> None:
        if config is None:
            config = SkyWalkingConfig.from_env()
        self.config = config.validate()
        self._resources = ResourceRegistry()
        self._transport = HttpTransport(
            self.config, known_secrets=self.config.known_secrets, http_transport=http_transport
        )
        self._zipkin = HttpTransport(
            _zipkin_config(self.config),
            known_secrets=self.config.known_secrets,
            http_transport=http_transport,
        )
        self._transports = {"oap": self._transport, "zipkin": self._zipkin}
        self._state = State(self.config, now) if now is not None else State(self.config)
        self._query = SkyWalkingQueryClient(self._state, self._transports)
        self._traces = SkyWalkingTraceQueryClient(
            self._state, self._transports, self.config.trace_backend
        )
        self._logs = SkyWalkingLogQueryClient(
            self._state, self._transports, max_scan_pages=max_scan_pages
        )
        self._owned: list[Callable[[], Any]] = []
        self._lock = threading.Lock()
        self._closed = False

    @classmethod
    def from_env(
        cls,
        env: Mapping[str, str] | None = None,
        **overrides: Any,
    ) -> SkyWalkingClient:
        """Build a client from the ``GROW_APACHE_SKYWALKING_*`` and vendor environment
        variables (see :class:`SkyWalkingConfig`)."""
        return cls(SkyWalkingConfig.from_env(env, **overrides))

    @property
    def resources(self) -> ResourceRegistry:
        """Background resources stopped by :meth:`close`."""
        return self._resources

    @property
    def query(self) -> SkyWalkingQueryClient:
        """The GraphQL query API: services, endpoints, instances, native traces, logs,
        topology."""
        return self._query

    @property
    def traces(self) -> SkyWalkingTraceQueryClient:
        """SPEC ``TraceQueryClient`` (Zipkin store for OTLP spans, native store for agents)."""
        return self._traces

    @property
    def logs(self) -> SkyWalkingLogQueryClient:
        """SPEC ``LogQueryClient`` over GraphQL ``queryLogs``."""
        return self._logs

    def request(self, req: Request, *, cancel: CancelToken | None = None) -> Response:
        """Send a raw request to the OAP REST port (retries, hooks, redaction)."""
        return self._transport.request(req, cancel=cancel)

    def health(self, *, cancel: CancelToken | None = None) -> HealthStatus:
        """Liveness: ``GET /healthcheck`` (OAP health checker, ``SW_HEALTH_CHECKER=default``):
        200 → ok, 503 → unhealthy. Without the health checker (404) falls back to GraphQL
        ``checkHealth`` (score 0 = ok). Never raises for an unhealthy or unreachable service
        (only :class:`CancelledError` and :class:`ConfigError`)."""
        return run_sync(op_health(self._state), self._transports, cancel)

    def ping(self, *, cancel: CancelToken | None = None) -> float:
        """Timed GraphQL round trip (``getTimeInfo``; also caches the OAP timezone); returns
        milliseconds. OAP has no query authentication: :class:`AuthenticationError` only comes
        from a gateway in front of it."""
        started = time.monotonic()
        run_sync(time_info(self._state), self._transports, cancel)
        return (time.monotonic() - started) * 1000.0

    def server_version(self, *, cancel: CancelToken | None = None) -> str:
        """OAP version from GraphQL ``{version}`` (e.g. ``10.4.0-b272da3``)."""
        return run_sync(op_server_version(self._state), self._transports, cancel)

    def graphql(
        self,
        query: str,
        variables: Mapping[str, Any] | None = None,
        *,
        cancel: CancelToken | None = None,
    ) -> GraphQLResponse:
        """Raw GraphQL (same as ``client.query.graphql``)."""
        return run_sync(graphql(self._state, query, variables, "graphql"), self._transports, cancel)

    def log_client(
        self,
        *,
        service_name: str | None = None,
        service_instance: str | None = None,
        options: BatchOptions | None = None,
        resource: Mapping[str, AttrValue] | None = None,
        redaction: _RedactionProcessor | None = _DEFAULT_REDACTION,
        correlate: bool = True,
    ) -> BatchingLogClient:
        """A :class:`BatchingLogClient` sending native logs (``POST /v3/logs``) over this
        client's connections. :meth:`close` flushes and closes it."""
        exporter = SkyWalkingLogExporter(
            self._transport, service_name=service_name or DEFAULT_LOG_SERVICE_NAME
        )
        client = BatchingLogClient(
            exporter,
            options=options,
            resource=_resource(resource, service_name, service_instance),
            redaction=redaction,
            correlate=correlate,
        )
        self._own(client.close)
        return client

    def init_tracing(self, config: TracingConfig | None = None, **overrides: Any) -> TracingHandle:
        """:func:`init_tracing` with this client's config (OTLP endpoint, protocol, token).
        :meth:`close` shuts the handle down."""
        handle = init_tracing(config, skywalking=self.config, **overrides)
        self._own(handle.shutdown)
        return handle

    def _own(self, closer: Callable[[], Any]) -> None:
        with self._lock:
            self._owned.append(closer)

    def close(self) -> None:
        """Flush and close log clients and tracing handles created here, stop background
        resources and release connections. Idempotent."""
        with self._lock:
            if self._closed:
                return
            self._closed = True
            owned, self._owned = self._owned, []
        for closer in owned:
            with contextlib.suppress(Exception):  # closing must not fail half way
                closer()
        self._resources.stop_all()
        self._transport.close()
        self._zipkin.close()

    def __enter__(self) -> SkyWalkingClient:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"SkyWalkingClient(config={self.config!r})"


class AsyncSkyWalkingClient:
    """Asynchronous Apache SkyWalking OAP client; use ``async with`` or call :meth:`aclose`.

    Same modules and methods as :class:`SkyWalkingClient`, awaited. Cancel a call by
    cancelling its task: the SDK raises :class:`CancelledError`, which is also an
    :class:`asyncio.CancelledError`.
    """

    def __init__(
        self,
        config: SkyWalkingConfig | None = None,
        *,
        http_transport: httpx.AsyncBaseTransport | None = None,
        now: Callable[[], int] | None = None,
        max_scan_pages: int = DEFAULT_MAX_SCAN_PAGES,
    ) -> None:
        if config is None:
            config = SkyWalkingConfig.from_env()
        self.config = config.validate()
        self._resources = ResourceRegistry()
        self._transport = AsyncHttpTransport(
            self.config, known_secrets=self.config.known_secrets, http_transport=http_transport
        )
        self._zipkin = AsyncHttpTransport(
            _zipkin_config(self.config),
            known_secrets=self.config.known_secrets,
            http_transport=http_transport,
        )
        self._transports = {"oap": self._transport, "zipkin": self._zipkin}
        self._state = State(self.config, now) if now is not None else State(self.config)
        self._query = AsyncSkyWalkingQueryClient(self._state, self._transports)
        self._traces = AsyncSkyWalkingTraceQueryClient(
            self._state, self._transports, self.config.trace_backend
        )
        self._logs = AsyncSkyWalkingLogQueryClient(
            self._state, self._transports, max_scan_pages=max_scan_pages
        )
        self._owned: list[Callable[[], Any]] = []
        self._closed = False

    @classmethod
    def from_env(
        cls,
        env: Mapping[str, str] | None = None,
        **overrides: Any,
    ) -> AsyncSkyWalkingClient:
        """Build a client from the environment (see :class:`SkyWalkingConfig`)."""
        return cls(SkyWalkingConfig.from_env(env, **overrides))

    @property
    def resources(self) -> ResourceRegistry:
        """Background resources stopped by :meth:`aclose`."""
        return self._resources

    @property
    def query(self) -> AsyncSkyWalkingQueryClient:
        """The GraphQL query API (async)."""
        return self._query

    @property
    def traces(self) -> AsyncSkyWalkingTraceQueryClient:
        """SPEC ``AsyncTraceQueryClient``."""
        return self._traces

    @property
    def logs(self) -> AsyncSkyWalkingLogQueryClient:
        """SPEC ``AsyncLogQueryClient``."""
        return self._logs

    async def request(self, req: Request) -> Response:
        """Send a raw request to the OAP REST port."""
        return await self._transport.request(req)

    async def health(self) -> HealthStatus:
        """See :meth:`SkyWalkingClient.health`."""
        return await run_async(op_health(self._state), self._transports)

    async def ping(self) -> float:
        """See :meth:`SkyWalkingClient.ping`."""
        started = time.monotonic()
        await run_async(time_info(self._state), self._transports)
        return (time.monotonic() - started) * 1000.0

    async def server_version(self) -> str:
        """See :meth:`SkyWalkingClient.server_version`."""
        return await run_async(op_server_version(self._state), self._transports)

    async def graphql(
        self, query: str, variables: Mapping[str, Any] | None = None
    ) -> GraphQLResponse:
        """Raw GraphQL (same as ``client.query.graphql``)."""
        return await run_async(graphql(self._state, query, variables, "graphql"), self._transports)

    def log_client(
        self,
        *,
        service_name: str | None = None,
        service_instance: str | None = None,
        options: BatchOptions | None = None,
        resource: Mapping[str, AttrValue] | None = None,
        redaction: _RedactionProcessor | None = _DEFAULT_REDACTION,
        correlate: bool = True,
    ) -> AsyncBatchingLogClient:
        """An :class:`AsyncBatchingLogClient` sending native logs over this client's
        connections. :meth:`aclose` flushes and closes it."""
        exporter = AsyncSkyWalkingLogExporter(
            self._transport, service_name=service_name or DEFAULT_LOG_SERVICE_NAME
        )
        client = AsyncBatchingLogClient(
            exporter,
            options=options,
            resource=_resource(resource, service_name, service_instance),
            redaction=redaction,
            correlate=correlate,
        )
        self._owned.append(client.close)
        return client

    def init_tracing(self, config: TracingConfig | None = None, **overrides: Any) -> TracingHandle:
        """:func:`init_tracing` with this client's config. :meth:`aclose` shuts it down."""
        handle = init_tracing(config, skywalking=self.config, **overrides)
        self._owned.append(handle.shutdown)
        return handle

    async def aclose(self) -> None:
        """Flush and close owned log clients and tracing handles, then release connections.
        Idempotent."""
        if self._closed:
            return
        self._closed = True
        owned, self._owned = self._owned, []
        for closer in owned:
            try:
                result = closer()
                if inspect.isawaitable(result):
                    await result
            except Exception:  # noqa: BLE001, S110 - closing must not fail half way
                pass
        self._resources.stop_all()
        await self._transport.aclose()
        await self._zipkin.aclose()

    close = aclose

    async def __aenter__(self) -> AsyncSkyWalkingClient:
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        await self.aclose()

    def __repr__(self) -> str:
        return f"AsyncSkyWalkingClient(config={self.config!r})"
