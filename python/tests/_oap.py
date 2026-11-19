"""Mock SkyWalking OAP for unit tests: GraphQL, /healthcheck, /v3/logs and the Zipkin query API,
answering with the responses recorded from the real OAP 10.4.0 (tests/fixtures, copied from the
GRO-1383 prep run). Built on ``httpx.MockTransport`` so it serves the sync and async clients."""

from __future__ import annotations

import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

import httpx

import grow_apache_skywalking as sdk
from _support import fast_retry

FIXTURES = Path(__file__).parent / "fixtures"
URL = "http://oap:12800"
ZIPKIN = "http://oap:9412/zipkin"
NOW_MS = 1795036300000  # 2026-11-18 21:11:40 UTC, a minute after the fixtures were recorded

Route = Callable[[httpx.Request], "httpx.Response | None"]


def fixture(name: str) -> dict[str, Any]:
    """A recorded exchange ``{request, response}``."""
    data: dict[str, Any] = json.loads((FIXTURES / f"{name}.json").read_text(encoding="utf-8"))
    return data


def body(name: str) -> Any:
    return fixture(name)["response"]["body"]


def reply(name: str) -> httpx.Response:
    """The recorded response of a fixture."""
    res = fixture(name)["response"]
    content = res["body"]
    headers = dict(res.get("headers") or {})
    if isinstance(content, str):
        return httpx.Response(res["status"], headers=headers, content=content.encode())
    return httpx.Response(res["status"], headers=headers, json=content)


def data(value: dict[str, Any]) -> httpx.Response:
    """A GraphQL ``data`` answer."""
    return httpx.Response(200, json={"data": value})


def gql(request: httpx.Request) -> dict[str, Any]:
    parsed: dict[str, Any] = json.loads(request.content)
    return parsed


def default_route(request: httpx.Request) -> httpx.Response:
    path = request.url.path
    if path == "/healthcheck":
        return reply("health.http")
    if path == "/v3/logs":
        return reply("reportLogs.ok")
    if path.startswith("/zipkin/api/v2/trace/"):
        if path.endswith("055ffba8ecc69a64950538fda698a308"):
            return reply("getTrace.zipkin")
        return reply("getTrace.zipkin-404")
    if path == "/zipkin/api/v2/traces":
        return reply("searchTraces.by-service")
    if path == "/zipkin/api/v2/services":
        return reply("listServices.zipkin")
    if path == "/zipkin/api/v2/spans":
        return reply("listOperations.zipkin")
    if path == "/zipkin/api/v2/dependencies":
        return reply("getDependencies.zipkin-404")
    if path != "/graphql" or request.method != "POST":
        return httpx.Response(404, text="not found")
    doc = gql(request)
    query, variables = doc["query"], doc.get("variables") or {}
    if "getTimeInfo" in query:
        return reply("ping.ok")
    if "checkHealth" in query:
        return reply("health.graphql")
    if "hasQueryTracesV2Support" in query:
        return reply("capabilities.ok")
    if "listServices" in query:
        return reply("listServices.all")
    if "findService" in query:
        if str(variables.get("n", "")).startswith("no-such"):
            return reply("findService.missing")
        return reply("findService.ok")
    if "findEndpoint" in query:
        return reply("listEndpoints.ok")
    if "listInstances" in query:
        return reply("listInstances.ok")
    if "queryTraces" in query:
        return reply("queryTraces.by-service")
    if "queryTrace(" in query:
        if str(variables.get("id", "")).startswith("1fddf66a1c64"):
            return reply("getTrace.native")
        return reply("getTrace.native-otlp-id")
    if "queryLogTagAutocompleteKeys" in query:
        return reply("queryLogTagAutocompleteKeys.ok")
    if "queryLogs" in query:
        return reply("queryLogs.by-service")
    if "getGlobalTopology" in query:
        return reply("topology.global")
    if "getServiceTopology" in query:
        return reply("topology.service")
    if "{version}" in query:
        return reply("serverVersion.ok")
    return reply("graphql.validation-error")


class Oap:
    """A mock OAP; ``routes`` are tried before the defaults (return ``None`` to fall through)."""

    def __init__(self, *routes: Route) -> None:
        self.routes = list(routes)
        self.requests: list[httpx.Request] = []
        self.transport = httpx.MockTransport(self._handle)

    def _handle(self, request: httpx.Request) -> httpx.Response:
        request.read()
        self.requests.append(request)
        for route in self.routes:
            res = route(request)
            if res is not None:
                return res
        return default_route(request)

    def config(self, **kw: Any) -> sdk.SkyWalkingConfig:
        kw.setdefault("url", URL)
        kw.setdefault("retry", fast_retry(max_retries=1))
        return sdk.SkyWalkingConfig(**kw)

    def client(self, **kw: Any) -> sdk.SkyWalkingClient:
        pages = kw.pop("max_scan_pages", sdk.DEFAULT_MAX_SCAN_PAGES)
        return sdk.SkyWalkingClient(
            self.config(**kw),
            http_transport=self.transport,
            now=lambda: NOW_MS,
            max_scan_pages=pages,
        )

    def aclient(self, **kw: Any) -> sdk.AsyncSkyWalkingClient:
        pages = kw.pop("max_scan_pages", sdk.DEFAULT_MAX_SCAN_PAGES)
        return sdk.AsyncSkyWalkingClient(
            self.config(**kw),
            http_transport=self.transport,
            now=lambda: NOW_MS,
            max_scan_pages=pages,
        )

    def graphql_calls(self, needle: str) -> list[dict[str, Any]]:
        """GraphQL request bodies whose query contains ``needle``."""
        return [
            gql(r)
            for r in self.requests
            if r.url.path == "/graphql" and r.method == "POST" and needle in gql(r)["query"]
        ]

    def paths(self) -> list[str]:
        return [r.url.path for r in self.requests]
