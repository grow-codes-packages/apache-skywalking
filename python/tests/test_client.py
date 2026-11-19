"""SkyWalkingClient / AsyncSkyWalkingClient: health, ping, version, raw GraphQL, owned resources."""

from __future__ import annotations

import asyncio
import json
from typing import Any

import httpx
import pytest

import grow_apache_skywalking as sdk
from _oap import NOW_MS, URL, Oap, data, gql, reply
from _support import spawn_capturing


def route_path(path: str, response: httpx.Response) -> Any:
    return lambda r: response if r.url.path == path else None


def route_query(needle: str, response: httpx.Response) -> Any:
    def route(r: httpx.Request) -> httpx.Response | None:
        if r.url.path == "/graphql" and needle in gql(r)["query"]:
            return response
        return None

    return route


# --------------------------------------------------------------------------- health


def test_health_ok() -> None:
    oap = Oap()
    with oap.client() as c:
        assert c.health() == sdk.HealthStatus(True, "ok", {})
    assert oap.paths() == ["/healthcheck"]


def test_health_503_is_unhealthy_without_retry() -> None:
    oap = Oap(route_path("/healthcheck", httpx.Response(503, text="down")))
    with oap.client() as c:
        assert c.health() == sdk.HealthStatus(False, "unhealthy", {"httpStatus": "503"})
    assert oap.paths() == ["/healthcheck"]


def test_health_falls_back_to_graphql_check() -> None:
    oap = Oap(route_path("/healthcheck", httpx.Response(404, text="nope")))
    with oap.client() as c:
        assert c.health().healthy
    assert oap.paths() == ["/healthcheck", "/graphql"]
    unhealthy = Oap(
        route_path("/healthcheck", httpx.Response(404)),
        route_query("checkHealth", data({"checkHealth": {"score": 2, "details": "storage"}})),
    )
    with unhealthy.client() as c:
        assert c.health() == sdk.HealthStatus(
            False, "unhealthy", {"score": "2", "details": "storage"}
        )
    odd = Oap(
        route_path("/healthcheck", httpx.Response(404)),
        route_query("checkHealth", data({"checkHealth": None})),
    )
    with odd.client() as c:
        assert c.health() == sdk.HealthStatus(False, "unhealthy", {"score": "-1"})


def test_health_errors_never_raise() -> None:
    teapot = Oap(route_path("/healthcheck", httpx.Response(418, text="teapot")))
    with teapot.client() as c:
        status = c.health()
    assert status.status == "unhealthy" and not status.healthy
    broken = Oap(route_path("/healthcheck", httpx.Response(500, text="boom")))
    with broken.client() as c:
        assert c.health().status == "unreachable"

    def fail(r: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused")

    with Oap(fail).client() as c:
        status = c.health()
    assert status.status == "unreachable" and "error" in status.details
    gql_fail = Oap(
        route_path("/healthcheck", httpx.Response(404)),
        route_query("checkHealth", reply("graphql.validation-error")),
    )
    with gql_fail.client() as c:
        assert c.health().status == "unhealthy"


def test_health_cancelled_raises() -> None:
    token = sdk.CancelToken()
    token.cancel()
    with Oap().client() as c, pytest.raises(sdk.CancelledError):
        c.health(cancel=token)


# --------------------------------------------------------------------------- ping, version, graphql


def test_ping_version_graphql() -> None:
    oap = Oap()
    with oap.client() as c:
        assert c.ping() >= 0
        assert c.server_version() == "10.4.0-b272da3"
        res = c.graphql("{version}")
        assert res.data == {"version": "10.4.0-b272da3"} and res.errors == ()
        assert c.query.graphql("query{version}", {}).data == {"version": "10.4.0-b272da3"}
    sent = json.loads(oap.requests[1].content)
    assert sent == {"query": "{version}", "variables": {}}
    assert oap.requests[1].headers["content-type"] == "application/json"
    assert oap.requests[1].headers["user-agent"].startswith("grow-codes-apache-skywalking/")


def test_server_version_missing_is_unsupported() -> None:
    oap = Oap(route_query("{version}", data({"version": ""})))
    with oap.client() as c, pytest.raises(sdk.UnsupportedError):
        c.server_version()


def test_graphql_errors() -> None:
    oap = Oap(
        route_query("nope", reply("graphql.validation-error")),
        route_query("bad", reply("graphql.bad-json")),
        route_query("fetch", reply("graphql.fetch-error")),
        route_query("basic", reply("queryBasicTraces.unsupported")),
        route_query("text", httpx.Response(200, text="not json")),
        route_query("list", httpx.Response(200, json=[1, 2])),
    )
    with oap.client() as c:
        with pytest.raises(sdk.ValidationError) as info:
            c.graphql("{nope}")
        assert info.value.http_status == 400
        assert "FieldUndefined" in str(info.value)
        with pytest.raises(sdk.ValidationError):
            c.graphql("{bad}")
        with pytest.raises(sdk.ValidationError):
            c.graphql("   ")
        res = c.graphql("{fetch}")
        assert res.data == {"getGlobalTopology": None}
        assert res.errors[0].path == ("getGlobalTopology",)
        assert res.errors[0].classification == "DataFetchingException"
        err = sdk.graphql_error("op", res.errors)
        assert isinstance(err, sdk.ValidationError) and str(err).startswith("op: Exception")
        basic = c.graphql("{basic}")
        assert isinstance(sdk.graphql_error("op", basic.errors), sdk.UnsupportedError)
        with pytest.raises(sdk.ValidationError):
            c.graphql("{text}")
        assert c.graphql("{list}") == sdk.GraphQLResponse(None, ())


def test_graphql_error_helpers() -> None:
    assert sdk.graphql_errors(None) == ()
    assert sdk.graphql_errors({"errors": [1, {"message": 2, "extensions": []}]}) == (
        sdk.GraphQLErrorInfo(""),
    )
    long = sdk.GraphQLErrorInfo("x secret-value-123 " + "y" * 400)
    err = sdk.graphql_error("op", [sdk.GraphQLErrorInfo(""), long], ["secret-value-123"])
    assert "secret-value-123" not in str(err) and len(err.message) <= 310
    assert str(sdk.graphql_error("op", [])) == "op: GraphQL error"


def test_mutations_are_not_retried() -> None:
    calls: list[int] = []

    def flaky(r: httpx.Request) -> httpx.Response | None:
        if r.url.path == "/graphql":
            calls.append(1)
            return httpx.Response(503, text="busy")
        return None

    with Oap(flaky).client() as c:
        with pytest.raises(sdk.UnavailableError):
            c.graphql("mutation { x }")
        assert len(calls) == 1
        with pytest.raises(sdk.UnavailableError):
            c.graphql("{ x }")
        assert len(calls) == 3


def test_request_and_resources_and_repr() -> None:
    oap = Oap()
    c = oap.client(authentication="tok-secret-1")
    assert c.request(sdk.Request("GET", "/healthcheck")).text == "200 OK"
    assert isinstance(c.resources, sdk.ResourceRegistry)
    assert "tok-secret-1" not in repr(c) and "SkyWalkingClient(" in repr(c)
    assert isinstance(c.query, sdk.SkyWalkingQueryClient)
    assert isinstance(c.traces, sdk.TraceQueryClient)
    assert isinstance(c.logs, sdk.LogQueryClient)
    c.close()
    c.close()
    with pytest.raises(sdk.CancelledError):
        c.ping()


def test_from_env(monkeypatch: pytest.MonkeyPatch) -> None:
    c = sdk.SkyWalkingClient.from_env({"SW_GRAPHQL_URL": "http://oap:12800/graphql"})
    assert c.config.url == URL
    assert c.config.zipkin_url == "http://oap:9412/zipkin"
    c.close()
    monkeypatch.setenv("GROW_APACHE_SKYWALKING_URL", "http://env-oap:12800")
    with sdk.SkyWalkingClient() as d:
        assert d.config.url == "http://env-oap:12800"
    with pytest.raises(sdk.ConfigError):
        sdk.SkyWalkingClient(sdk.SkyWalkingConfig(url="nope"))


def test_close_flushes_owned_log_clients_and_handles() -> None:
    oap = Oap()
    c = oap.client()
    logs = c.log_client(service_name="svc", options=sdk.BatchOptions(flush_interval_s=60))
    handle = c.init_tracing(service_name="svc", set_global=False)
    logs.send(sdk.LogInput("hello"))
    c.close()
    assert handle.is_shut_down
    assert logs.stats().sent == 1
    assert "/v3/logs" in oap.paths()


def test_calls_after_close_are_cancelled() -> None:
    oap = Oap()
    c = oap.client()
    c.close()
    with pytest.raises(sdk.CancelledError, match="closed"):
        c.ping()
    with pytest.raises(sdk.CancelledError):
        c.query.list_services()
    with pytest.raises(sdk.CancelledError):
        c.traces.list_services()
    assert oap.paths() == []


async def test_async_calls_after_close_are_cancelled() -> None:
    oap = Oap()
    c = oap.aclient()
    await c.aclose()
    with pytest.raises(sdk.CancelledError, match="closed"):
        await c.ping()
    with pytest.raises(sdk.CancelledError):
        await c.traces.list_services()
    assert oap.paths() == []


def test_close_survives_failing_closer() -> None:
    c = Oap().client()

    def boom() -> None:
        raise RuntimeError("x")

    c._own(boom)
    c.close()


# --------------------------------------------------------------------------- async


async def test_async_client_core() -> None:
    oap = Oap()
    async with oap.aclient() as c:
        assert (await c.health()).healthy
        assert await c.ping() >= 0
        assert await c.server_version() == "10.4.0-b272da3"
        assert (await c.graphql("{version}")).data == {"version": "10.4.0-b272da3"}
        assert (await c.request(sdk.Request("GET", "/healthcheck"))).status == 200
        assert isinstance(c.resources, sdk.ResourceRegistry)
        assert isinstance(c.query, sdk.AsyncSkyWalkingQueryClient)
        assert isinstance(c.traces, sdk.AsyncTraceQueryClient)
        assert isinstance(c.logs, sdk.AsyncLogQueryClient)
        assert "AsyncSkyWalkingClient(" in repr(c)
    await c.aclose()
    c2 = sdk.AsyncSkyWalkingClient.from_env({"GROW_APACHE_SKYWALKING_URL": URL})
    await c2.close()


async def test_async_health_variants() -> None:
    oap = Oap(route_path("/healthcheck", httpx.Response(503)))
    async with oap.aclient() as c:
        assert (await c.health()).status == "unhealthy"


async def test_async_owned_resources() -> None:
    oap = Oap()
    c = oap.aclient()
    logs = c.log_client(service_name="svc", options=sdk.BatchOptions(flush_interval_s=60))
    handle = c.init_tracing(service_name="svc", set_global=False)
    logs.send(sdk.LogInput("hello async"))

    def boom() -> None:
        raise RuntimeError("x")

    c._owned.append(boom)
    await c.aclose()
    assert handle.is_shut_down
    assert logs.stats().sent == 1
    assert "/v3/logs" in oap.paths()


async def test_async_cancellation() -> None:
    gate = asyncio.Event()

    async def slow_handler(request: httpx.Request) -> httpx.Response:
        await gate.wait()
        return httpx.Response(200, json={"data": {}})

    c = sdk.AsyncSkyWalkingClient(
        sdk.SkyWalkingConfig(url=URL), http_transport=httpx.MockTransport(slow_handler)
    )
    seen: list[BaseException] = []
    task = spawn_capturing(c.ping(), seen)
    await asyncio.sleep(0.05)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert isinstance(seen[0], sdk.CancelledError)
    await c.aclose()


def test_default_clock_is_used() -> None:
    oap = Oap()
    c = sdk.SkyWalkingClient(sdk.SkyWalkingConfig(url=URL), http_transport=oap.transport)
    assert abs(c._state.now() - NOW_MS) < 10 * 365 * 86_400_000
    c.close()
