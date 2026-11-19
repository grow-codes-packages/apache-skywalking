"""Factories the integration suite and the conformance runner use (owned by this repository).

OAP runs without query authentication in docker/compose.yml; /bootstrap/env carries every
endpoint (GraphQL/REST on 12800, OTLP gRPC on 11800, Zipkin query on 9412).
"""

from __future__ import annotations

import os
import random
import string
from collections.abc import Mapping
from typing import Any

import grow_apache_skywalking as sdk
from grow_apache_skywalking.testing import LoggingTarget, TracingTarget
from grow_apache_skywalking.testing.logging_conformance import batch_options_for

URL_VAR = "GROW_APACHE_SKYWALKING_URL"

#: Capabilities of the deployment under test for the core scenarios ("version" = serverVersion).
CORE_CAPABILITIES: set[str] = {"version"}

#: Logging: OAP has a log query API; free text is filtered client side (BanyanDB ignores
#: content keywords). No streaming tail, no label values API.
LOGGING_CAPABILITIES: set[str] = {"query"}

#: Tracing: OTLP spans are read back via the Zipkin query API; OAP lists services ~35 s after
#: the first span (warm-up in test_conformance.py). Zipkin /dependencies is not implemented.
TRACING_CAPABILITIES: set[str] = {"query", "services"}


def unique(prefix: str) -> str:
    """A lower-case name no other test or language suite uses (OAP lower-cases span names)."""
    run = "".join(c for c in os.environ.get("GROW_TEST_RUN_ID", "local").lower() if c.isalnum())
    rand = "".join(random.choices(string.ascii_lowercase + string.digits, k=6))
    return f"{prefix}-py-{run[:12]}-{rand}"


def core_client(env: Mapping[str, str]) -> sdk.SkyWalkingClient:
    """A client configured from the test environment (endpoints from /bootstrap/env)."""
    return sdk.SkyWalkingClient.from_env(env)


def async_core_client(env: Mapping[str, str]) -> sdk.AsyncSkyWalkingClient:
    return sdk.AsyncSkyWalkingClient.from_env(env)


def tracing_handle(env: Mapping[str, str], service_name: str, **kw: Any) -> sdk.TracingHandle:
    """Tracing to the real OAP (OTLP gRPC on 11800), fast batches, not global."""
    kw.setdefault("set_global", False)
    kw.setdefault("sampler", "always_on")
    kw.setdefault("batch", sdk.SpanBatchOptions(scheduled_delay_s=0.2))
    return sdk.init_tracing(
        service_name=service_name, skywalking=sdk.SkyWalkingConfig.from_env(env), **kw
    )


def logging_target(
    env: Mapping[str, str], scenario: Mapping[str, Any], query: Any
) -> LoggingTarget:
    """A ``LoggingTarget`` whose client sends native logs for a unique service."""
    client = sdk.create_log_client(
        sdk.SkyWalkingConfig.from_env(env),
        service_name=unique("conformance-logs"),
        options=batch_options_for(scenario),
    )
    return LoggingTarget(client, query)


def tracing_target(env: Mapping[str, str], service_name: str, query: Any) -> TracingTarget:
    """A ``TracingTarget`` exporting as ``service_name``."""
    return TracingTarget(tracing_handle(env, service_name), query)
