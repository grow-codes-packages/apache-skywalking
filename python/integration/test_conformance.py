"""Logging and tracing conformance scenarios against the real OAP.

OAP lists a new service only ~30-35 s after its first span, and the runner calls
``list_services`` once, so the tracing service is warmed up first (span sent, then
``list_services`` polled until it shows up).
"""

from __future__ import annotations

import time
from collections.abc import Iterator
from typing import Any

import pytest
from factories import (
    LOGGING_CAPABILITIES,
    TRACING_CAPABILITIES,
    core_client,
    logging_target,
    tracing_handle,
    tracing_target,
    unique,
)

import grow_apache_skywalking as sdk
from grow_apache_skywalking.testing import ConformanceRunner, assert_scenario, pytest_params


@pytest.fixture(scope="module")
def shared(env: dict[str, str]) -> Iterator[sdk.SkyWalkingClient]:
    with core_client(env) as c:
        yield c


@pytest.fixture(scope="module")
def warm_service(env: dict[str, str], shared: sdk.SkyWalkingClient) -> str:
    name = unique("conformance-traces")
    handle = tracing_handle(env, name)
    handle.tracer("warm-up").start_span("warm-up").end()
    handle.force_flush(30)
    handle.shutdown()
    deadline = time.monotonic() + 150
    while name not in shared.traces.list_services():
        assert time.monotonic() < deadline, f"warm-up: {name} never listed"
        time.sleep(2)
    return name


@pytest.mark.parametrize("scenario", pytest_params(categories=["logging"]))
def test_logging_conformance(
    env: dict[str, str], shared: sdk.SkyWalkingClient, scenario: dict[str, Any]
) -> None:
    runner = ConformanceRunner(
        {"logging": lambda ctx: logging_target(env, ctx.scenario, shared.logs)},
        capabilities=LOGGING_CAPABILITIES,
    )
    assert_scenario(runner, scenario)


@pytest.mark.parametrize("scenario", pytest_params(categories=["tracing"]))
def test_tracing_conformance(
    env: dict[str, str],
    shared: sdk.SkyWalkingClient,
    warm_service: str,
    scenario: dict[str, Any],
) -> None:
    runner = ConformanceRunner(
        {"tracing": lambda ctx: tracing_target(env, warm_service, shared.traces)},
        capabilities=TRACING_CAPABILITIES,
        service_name=warm_service,
    )
    assert_scenario(runner, scenario)
