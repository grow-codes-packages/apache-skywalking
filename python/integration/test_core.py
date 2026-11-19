"""Core round trips against the real OAP plus the core conformance scenarios."""

from __future__ import annotations

import asyncio
from typing import Any

import pytest
from factories import CORE_CAPABILITIES, async_core_client, core_client

import grow_apache_skywalking as sdk
from grow_apache_skywalking.testing import ConformanceRunner, assert_scenario, pytest_params


def test_health(client: sdk.SkyWalkingClient) -> None:
    assert client.health() == sdk.HealthStatus(True, "ok", {})


def test_ping(client: sdk.SkyWalkingClient) -> None:
    assert client.ping() >= 0


def test_server_version(client: sdk.SkyWalkingClient, env: dict[str, str]) -> None:
    version = client.server_version()
    assert version.startswith("10.4.")
    if env.get("SW_SERVER_VERSION"):
        assert version == env["SW_SERVER_VERSION"]


def test_close_is_idempotent(env: dict[str, str]) -> None:
    c = core_client(env)
    c.close()
    c.close()
    with pytest.raises(sdk.CancelledError):
        c.ping()


def test_async_client(env: dict[str, str]) -> None:
    async def run() -> None:
        async with async_core_client(env) as c:
            assert (await c.health()).healthy
            assert await c.ping() >= 0
            assert (await c.server_version()).startswith("10.4.")

    asyncio.run(run())


def test_unreachable_service_is_reported(env: dict[str, str]) -> None:
    with core_client({**env, "GROW_APACHE_SKYWALKING_URL": "http://127.0.0.1:1"}) as c:
        assert c.health().status == "unreachable"
        with pytest.raises(sdk.UnavailableError):
            c.ping()


@pytest.mark.parametrize("scenario", pytest_params(categories=["core"]))
def test_core_conformance(env: dict[str, str], scenario: dict[str, Any]) -> None:
    runner = ConformanceRunner(
        {"core": lambda ctx: core_client(env)}, capabilities=CORE_CAPABILITIES
    )
    assert_scenario(runner, scenario)
