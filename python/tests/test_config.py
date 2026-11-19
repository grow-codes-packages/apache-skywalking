"""SkyWalkingConfig: defaults, derived endpoints, environment precedence, validation, redaction."""

from __future__ import annotations

from typing import Any

import pytest

import grow_apache_skywalking as sdk

P = sdk.ENV_PREFIX


def test_defaults_and_derived_endpoints() -> None:
    cfg = sdk.SkyWalkingConfig().validate()
    assert cfg.url == "http://127.0.0.1:12800"
    assert cfg.graphql_path == "/graphql"
    assert cfg.graphql_url == "http://127.0.0.1:12800/graphql"
    assert cfg.otlp_endpoint == "http://127.0.0.1:11800"
    assert cfg.otlp_protocol == "grpc"
    assert cfg.zipkin_url == "http://127.0.0.1:9412/zipkin"
    assert cfg.trace_backend == "auto"
    assert cfg.authentication is None
    assert dict(cfg.otlp_headers) == {}
    custom = sdk.SkyWalkingConfig(url="https://oap.example:443/graphql/")
    assert custom.url == "https://oap.example:443"
    assert custom.otlp_endpoint == "https://oap.example:11800"
    assert custom.zipkin_url == "https://oap.example:9412/zipkin"
    v6 = sdk.SkyWalkingConfig(url="http://[::1]:12800")
    assert v6.otlp_endpoint == "http://[::1]:11800"
    explicit = sdk.SkyWalkingConfig(
        otlp_endpoint="http://collector:4317/", zipkin_url="http://z:9412/zipkin/"
    )
    assert explicit.otlp_endpoint == "http://collector:4317"
    assert explicit.zipkin_url == "http://z:9412/zipkin"


def test_helpers() -> None:
    assert sdk.base_from_graphql_url("http://x:1/GraphQL/") == "http://x:1"
    assert sdk.base_from_graphql_url("http://x:1/") == "http://x:1"
    assert sdk.collector_to_url("oap:11800,oap2:11800") == "http://oap:11800"
    assert sdk.collector_to_url("https://oap:11800") == "https://oap:11800"
    assert sdk.zipkin_query_url("http://oap:9412") == "http://oap:9412/zipkin"
    assert sdk.zipkin_query_url("http://oap:9412/zipkin/") == "http://oap:9412/zipkin"
    assert sdk.parse_otlp_headers("A=1, b = x%20y ,,") == {"a": "1", "b": "x y"}
    with pytest.raises(sdk.ConfigError, match="key=value"):
        sdk.parse_otlp_headers("novalue")
    with pytest.raises(sdk.ConfigError, match="key=value"):
        sdk.parse_otlp_headers("=v")
    with pytest.raises(sdk.ConfigError, match="percent") as info:
        sdk.parse_otlp_headers("a=secret%zz", "X")
    assert "secret" not in str(info.value)


def test_from_env_precedence_and_conversions() -> None:
    env = {
        "SW_OAP_ADDRESS": "http://ui-var:12800",
        "SW_GRAPHQL_URL": "http://gql:12800/graphql",
        "SW_AGENT_COLLECTOR_BACKEND_SERVICES": "agent:11800",
        "SW_ZIPKIN_ADDRESS": "http://zip:9412",
        "SW_AGENT_AUTHENTICATION": "agent-token-1",
        "OTEL_EXPORTER_OTLP_PROTOCOL": "http/protobuf",
        "OTEL_EXPORTER_OTLP_HEADERS": "Authorization=Bearer%20abc123,x-tenant=t1",
    }
    cfg = sdk.SkyWalkingConfig.from_env(env)
    assert cfg.url == "http://gql:12800"
    assert cfg.otlp_endpoint == "http://agent:11800"
    assert cfg.zipkin_url == "http://zip:9412/zipkin"
    assert cfg.authentication is not None and cfg.authentication.reveal() == "agent-token-1"
    assert cfg.otlp_protocol == "http/protobuf"
    assert dict(cfg.otlp_headers) == {"authorization": "Bearer abc123", "x-tenant": "t1"}
    assert set(cfg.known_secrets()) == {"agent-token-1", "Bearer abc123"}
    env.update(
        {
            "GROW_SKYWALKING_URL": "http://short:12800",
            "OTEL_EXPORTER_OTLP_ENDPOINT": "http://otel:4317",
            "SW_AUTHENTICATION": "sw-token-2",
        }
    )
    cfg = sdk.SkyWalkingConfig.from_env(env)
    assert cfg.url == "http://short:12800"
    assert cfg.otlp_endpoint == "http://otel:4317"
    assert cfg.authentication is not None and cfg.authentication.reveal() == "sw-token-2"
    env.update(
        {
            P + "URL": "http://grow:12800",
            P + "OTLP_ENDPOINT": "http://grow:11800",
            P + "OTLP_PROTOCOL": "grpc",
            P + "OTLP_HEADERS": "k=v",
            P + "AUTHENTICATION": "grow-token-3",
            P + "ZIPKIN_URL": "http://gz:9412/zipkin",
            P + "TRACE_BACKEND": "native",
            P + "GRAPHQL_PATH": "/api/graphql",
        }
    )
    cfg = sdk.SkyWalkingConfig.from_env(env)
    assert cfg.url == "http://grow:12800"
    assert cfg.graphql_url == "http://grow:12800/api/graphql"
    assert cfg.otlp_endpoint == "http://grow:11800"
    assert cfg.otlp_protocol == "grpc"
    assert dict(cfg.otlp_headers) == {"k": "v"}
    assert cfg.authentication is not None and cfg.authentication.reveal() == "grow-token-3"
    assert cfg.zipkin_url == "http://gz:9412/zipkin"
    assert cfg.trace_backend == "native"
    explicit = sdk.SkyWalkingConfig.from_env(env, trace_backend="zipkin", url="http://e:1")
    assert explicit.trace_backend == "zipkin" and explicit.url == "http://e:1"


@pytest.mark.parametrize(
    ("name", "value"),
    [
        ("OTEL_EXPORTER_OTLP_PROTOCOL", "http/json"),
        (P + "TRACE_BACKEND", "jaeger"),
        ("OTEL_EXPORTER_OTLP_TRACES_HEADERS", "broken"),
    ],
)
def test_from_env_invalid_values(name: str, value: str) -> None:
    with pytest.raises(sdk.ConfigError) as info:
        sdk.SkyWalkingConfig.from_env({name: value})
    assert name in str(info.value)
    assert value not in str(info.value)


@pytest.mark.parametrize(
    ("kwargs", "word"),
    [
        ({"graphql_path": "graphql"}, "graphql_path"),
        ({"otlp_endpoint": "grpc://x:1"}, "otlp_endpoint"),
        ({"zipkin_url": "nope"}, "zipkin_url"),
        ({"otlp_protocol": "thrift"}, "otlp_protocol"),
        ({"trace_backend": "elastic"}, "trace_backend"),
        ({"authentication": "  "}, "authentication"),
        ({"url": "ftp://x"}, "url"),
    ],
)
def test_validate(kwargs: dict[str, Any], word: str) -> None:
    with pytest.raises(sdk.ConfigError, match=word):
        sdk.SkyWalkingConfig(**kwargs).validate()


def test_invalid_url_does_not_crash_derivation() -> None:
    cfg = sdk.SkyWalkingConfig(url="http://[bad")
    assert cfg.otlp_endpoint == ""
    with pytest.raises(sdk.ConfigError):
        cfg.validate()


def test_repr_redacts_credentials() -> None:
    cfg = sdk.SkyWalkingConfig(
        url="http://user:pw@oap:12800",
        authentication="auth-token-xyz",
        otlp_headers={"X-Tenant": "tenant-secret", "Authorization": "Bearer zzz123"},
        otlp_endpoint="http://u:p@collector:4317",
    )
    for text in (repr(cfg), str(cfg)):
        assert "auth-token-xyz" not in text
        assert "tenant-secret" not in text
        assert "zzz123" not in text
        assert ":pw@" not in text and "u:p@" not in text
        assert "otlp_headers={'x-tenant': '[REDACTED]'" in text
        assert "trace_backend='auto'" in text
    assert isinstance(cfg.authentication, sdk.Secret)
    assert cfg.known_secrets() == ["auth-token-xyz", "Bearer zzz123"]
