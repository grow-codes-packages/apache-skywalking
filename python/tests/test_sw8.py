"""sw8 / sw8-correlation codecs and the OpenTelemetry propagator (vectors from the real OAP)."""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from opentelemetry import baggage
from opentelemetry.context import Context

import grow_apache_skywalking as sdk

VECTORS = json.loads((Path(__file__).parent / "fixtures" / "sw8.vectors.json").read_text())
D = VECTORS["sw8"]["decoded"]
CTX = sdk.Sw8Context(
    sample=D["sample"],
    trace_id=D["traceId"],
    parent_trace_segment_id=D["parentTraceSegmentId"],
    parent_span_id=D["parentSpanId"],
    parent_service=D["parentService"],
    parent_service_instance=D["parentServiceInstance"],
    parent_endpoint=D["parentEndpoint"],
    network_address_used_at_peer=D["networkAddressUsedAtPeer"],
)


def test_sw8_vectors() -> None:
    assert sdk.encode_sw8(CTX) == VECTORS["sw8"]["header"]
    assert sdk.decode_sw8(VECTORS["sw8"]["header"]) == CTX
    assert sdk.decode_sw8(f"  {VECTORS['sw8']['header']}  ") == CTX


@pytest.mark.parametrize(
    "header",
    [
        *VECTORS["sw8"]["invalid"],
        None,
        "1--YQ==-1-YQ==-YQ==-YQ==-YQ==",
        "1-YQ==-YQ==-1-/w==-YQ==-YQ==-YQ==",
    ],
)
def test_sw8_invalid(header: str | None) -> None:
    assert sdk.decode_sw8(header) is None


def test_sw8_unsampled_round_trip() -> None:
    ctx = sdk.Sw8Context(False, "t", "s", 0, "", "", "", "")
    assert sdk.decode_sw8(sdk.encode_sw8(ctx)) == ctx


def test_correlation() -> None:
    header = VECTORS["sw8Correlation"]["header"]
    expected = dict(VECTORS["sw8Correlation"]["decoded"])
    assert sdk.decode_sw8_correlation(header) == expected
    assert sdk.encode_sw8_correlation(expected) == header
    assert sdk.decode_sw8_correlation("a:b:c,!!:YQ==,:YQ==,YQ==:%%,dGVuYW50:") == {"tenant": ""}
    assert sdk.decode_sw8_correlation(None) == {}


def test_propagator_extract_and_inject() -> None:
    p = sdk.Sw8Propagator()
    assert p.fields == {"sw8", "sw8-correlation"}
    carrier = {
        "sw8": VECTORS["sw8"]["header"],
        "sw8-correlation": VECTORS["sw8Correlation"]["header"],
    }
    ctx = p.extract(carrier)
    assert sdk.get_sw8_context(ctx) == CTX
    assert baggage.get_baggage("tenant", ctx) == "acme"
    assert sdk.get_sw8_context(p.extract({"sw8": ["bad"]}, Context())) is None
    assert sdk.get_sw8_context(p.extract({"sw8": []})) is None
    assert sdk.get_sw8_context() is None
    attrs = sdk.sw8_attributes(CTX)
    assert attrs["sw8.parent.segment_id"] == CTX.parent_trace_segment_id
    assert attrs["sw8.peer"] == CTX.network_address_used_at_peer

    out: dict[str, str] = {}
    p.inject(out, Context())
    assert out == {}
    inject = sdk.Sw8InjectContext(**{**CTX.__dict__, "correlation": {"run": "1fddf66a"}})
    p.inject(out, sdk.with_sw8_context(inject))
    assert out["sw8"] == VECTORS["sw8"]["header"]
    assert sdk.decode_sw8_correlation(out["sw8-correlation"]) == {"run": "1fddf66a"}
    plain: dict[str, str] = {}
    p.inject(plain, sdk.with_sw8_context(CTX, Context()))
    assert set(plain) == {"sw8"}
