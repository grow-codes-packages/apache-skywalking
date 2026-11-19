"""SkyWalkingTraceQueryClient over the Zipkin and native stores, plus the span conversions."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any

import httpx
import pytest

import grow_apache_skywalking as sdk
from _oap import NOW_MS, Oap, body, data, gql, reply

OTLP_TRACE = "055ffba8ecc69a64950538fda698a308"
NATIVE_TRACE = "1fddf66a1c64db2c1345419627769f1f.1.1795036241775"
SEG_A = "1fddf66a0691f3d5361838903180a743.1.1795036241775"
NOW = datetime(1970, 1, 1, tzinfo=timezone.utc) + timedelta(milliseconds=NOW_MS)


def zipkin_params(oap: Oap, path: str) -> list[dict[str, str]]:
    return [dict(r.url.params) for r in oap.requests if r.url.path == f"/zipkin/api/v2/{path}"]


# --------------------------------------------------------------------------- conversions


def test_zipkin_span_conversion() -> None:
    trace = sdk.zipkin_trace_to_trace(body("getTrace.zipkin"))
    assert trace is not None and trace.trace_id == OTLP_TRACE
    root, child = trace.spans
    assert root.name == "get /checkout" and root.kind == "server"
    assert root.parent_span_id is None and root.service_name == "sw-otlp-1fddf66a"
    assert root.status == sdk.SpanStatus("ok")
    assert root.attributes["retries"] == "2"
    assert "otel.status_code" not in root.attributes and "w3c.tracestate" not in root.attributes
    assert root.events[0] == sdk.SpanEvent("cache.miss", 1795036241735000, {"k": "v"})
    assert (root.start_micros, root.end_micros, root.duration_micros) == (
        1795036241725000,
        1795036241775000,
        50000,
    )
    assert child.parent_span_id == root.span_id and child.kind == "client"
    assert child.status == sdk.SpanStatus("error", "boom")
    assert "error" not in child.attributes
    assert sdk.zipkin_trace_to_trace([]) is None


def test_zipkin_helpers() -> None:
    assert sdk.is_hex_trace_id(OTLP_TRACE) and sdk.is_hex_trace_id("ABCDEF0123456789")
    assert not sdk.is_hex_trace_id(NATIVE_TRACE)
    assert not sdk.is_hex_trace_id(5)  # type: ignore[arg-type]
    assert sdk.zipkin_trace_id(" ABCDEF0123456789 ", "op") == "abcdef0123456789"
    for bad in ("0" * 32, "xyz", NATIVE_TRACE, None):
        with pytest.raises(sdk.ValidationError):
            sdk.zipkin_trace_id(bad, "op")  # type: ignore[arg-type]
    assert sdk.zipkin_spans_from_body({"x": 1}) == []
    assert sdk.zipkin_spans_from_body([{"traceId": "a"}, 1]) == []
    assert sdk.zipkin_traces_from_body("x") == []
    assert sdk.zipkin_traces_from_body([[], [{"traceId": "a", "id": "b"}]]) == [
        [{"traceId": "a", "id": "b"}]
    ]
    assert sdk.strings_from_body(["a", 1]) == ["a"] and sdk.strings_from_body(None) == []
    assert sdk.is_bookkeeping_tag("otel.x") and sdk.is_bookkeeping_tag("error")
    assert not sdk.is_bookkeeping_tag("http.method")
    assert sdk.annotation_to_event({"value": "plain", "timestamp": 5}) == sdk.SpanEvent(
        "plain", 5, {}
    )
    assert sdk.annotation_to_event({"value": "n||0", "timestamp": True}) == sdk.SpanEvent(
        "n", 0, {}
    )
    assert sdk.annotation_to_event({"value": "n|{bad|0"}).name == "n|{bad|0"
    assert sdk.annotation_to_event({"value": "n|[1]|0"}).name == "n|[1]|0"


def test_zipkin_status_variants() -> None:
    base: dict[str, Any] = {"traceId": "a" * 16, "id": "b" * 16}
    plain = sdk.zipkin_span_to_span(base)
    assert plain.kind == "internal" and plain.status.code == "unset" and plain.service_name == ""
    assert plain.trace_id == "0" * 16 + "a" * 16
    assert sdk.zipkin_span_to_span({**base, "tags": {"error": "x"}}).status.code == "error"
    assert sdk.zipkin_span_to_span({**base, "tags": {"otel.status_code": "ERROR"}}).status == (
        sdk.SpanStatus("error")
    )
    assert sdk.zipkin_span_to_span({**base, "kind": "WEIRD"}).kind == "unspecified"
    assert sdk.zipkin_span_to_span({**base, "timestamp": "x", "tags": []}).start_micros == 0


def test_native_span_conversion() -> None:
    native = sdk.native_trace_from_spans(body("getTrace.native")["data"]["queryTrace"]["spans"])
    trace = sdk.native_trace_to_trace(native)
    assert isinstance(trace, sdk.NativeIdTrace) and isinstance(trace, sdk.Trace)
    assert trace.trace_id == NATIVE_TRACE
    entry = next(s for s in trace.spans if s.span_id == f"{SEG_A}-0")
    exit_ = next(s for s in trace.spans if s.span_id == f"{SEG_A}-1")
    b = next(s for s in trace.spans if s.service_name == "sw-native-b-1fddf66a")
    assert isinstance(entry, sdk.NativeIdSpan) and entry.kind == "server"
    assert entry.parent_span_id is None
    assert exit_.parent_span_id == f"{SEG_A}-0" and exit_.kind == "client"
    assert exit_.attributes["sw.peer"] == "sw-native-b-1fddf66a:8080"
    assert entry.attributes["sw.component"] == "Tomcat" and entry.attributes["sw.layer"] == "Http"
    assert b.parent_span_id == f"{SEG_A}-1" and b.status.code == "error"
    assert b.events[0].name == "error" and b.events[0].attributes["message"] == "downstream failed"
    assert entry.start_micros == 1795036241675000 and entry.duration_micros == 100000
    assert [n.span.name for n in trace.tree()] == ["/native/entry"]
    assert sdk.native_trace_to_trace(None) is None
    assert sdk.native_trace_to_trace(sdk.NativeTrace("x")) is None
    summary = sdk.summarize(trace)
    assert summary.root_name == "/native/entry" and summary.span_count == 3


def test_native_links_kind_and_logs() -> None:
    ref1 = sdk.NativeSpanRef("t", "segP", 2, "CROSS_THREAD")
    ref2 = sdk.NativeSpanRef("t2", "segQ", 3, "CROSS_PROCESS")
    root = sdk.native_span_to_span(
        sdk.NativeSpan(
            "t",
            "seg",
            0,
            -1,
            refs=(ref1, ref2),
            type="Local",
            start_time=10,
            end_time=5,
            logs=(sdk.NativeSpanLog(7, (sdk.KeyValue("k", "v"),)),),
        )
    )
    assert root.parent_span_id == "segP-2" and root.kind == "internal"
    assert [(k.trace_id, k.span_id) for k in root.links] == [("t2", "segQ-3")]
    assert root.duration_micros == 0 and root.events[0].name == "log"
    child = sdk.native_span_to_span(sdk.NativeSpan("t", "seg", 1, 0, refs=(ref1,), type="?"))
    assert child.parent_span_id == "seg-0" and child.kind == "unspecified"
    assert child.links[0].attributes == {"sw.ref_type": "CROSS_THREAD"}
    orphan = sdk.native_span_to_span(sdk.NativeSpan("t", "seg", 0))
    assert orphan.parent_span_id is None
    with pytest.raises(sdk.ValidationError):
        sdk.NativeIdSpan("t", "s", "n", 0, 1, kind="bogus")  # type: ignore[arg-type]


def test_topology_to_dependencies_and_summary_fallback() -> None:
    topo = sdk.decode_topology(body("topology.global")["data"]["getGlobalTopology"])
    deps = sdk.topology_to_dependencies(topo)
    assert sdk.DependencyLink("User", "sw-native-a-1fddf66a", 0) in deps
    assert sdk.DependencyLink("sw-native-a-1fddf66a", "sw-native-b-1fddf66a", 0) in deps
    assert deps == sorted(deps, key=lambda d: (d.parent, d.child))
    loose = sdk.Topology((), (sdk.TopologyCall("1", "a", "b"),))
    assert sdk.topology_to_dependencies(loose) == [sdk.DependencyLink("a", "b", 0)]
    assert sdk.summarize(sdk.Trace(OTLP_TRACE)) == sdk.TraceSummary(OTLP_TRACE, 0, 0, 0)
    loop = sdk.NativeIdTrace(
        "t",
        [
            sdk.NativeIdSpan("t", "a", "A", 1, 2, parent_span_id="b"),
            sdk.NativeIdSpan("t", "b", "B", 2, 3, parent_span_id="a"),
        ],
    )
    assert sdk.summarize(loop).root_name == "A"


# --------------------------------------------------------------------------- get_trace


def test_get_trace_auto_backend() -> None:
    oap = Oap()
    with oap.client() as c:
        assert c.traces.backend == "auto"
        trace = c.traces.get_trace(OTLP_TRACE.upper())
        assert trace is not None and len(trace.spans) == 2
        native = c.traces.get_trace(NATIVE_TRACE)
        assert native is not None and native.trace_id == NATIVE_TRACE
        assert c.traces.get_trace("0000000000000000000000000000beef") is None
        with pytest.raises(sdk.ValidationError):
            c.traces.get_trace(" ")
    assert oap.graphql_calls("queryTrace(")[-1]["variables"]["id"] == (
        "0000000000000000000000000000beef"
    )


def test_get_trace_backends() -> None:
    zipkin = Oap()
    with zipkin.client(trace_backend="zipkin") as c:
        assert c.traces.get_trace("0000000000000000000000000000beef") is None
        with pytest.raises(sdk.ValidationError):
            c.traces.get_trace(NATIVE_TRACE)
    assert not zipkin.graphql_calls("queryTrace(")
    native = Oap()
    with native.client(trace_backend="native") as c:
        assert c.traces.get_trace(OTLP_TRACE) is None
    assert not [r for r in native.requests if "zipkin" in r.url.path]


def test_zipkin_errors_are_mapped() -> None:
    def route(r: httpx.Request) -> httpx.Response | None:
        if r.url.path.startswith("/zipkin/api/v2/trace/"):
            return reply("getTrace.zipkin-400")
        if r.url.path == "/zipkin/api/v2/services":
            return httpx.Response(500, text="oops")
        return None

    with Oap(route).client() as c:
        with pytest.raises(sdk.ValidationError, match=r"lower-hex encoded.*HTTP 400"):
            c.traces.get_trace(OTLP_TRACE)
        with pytest.raises(sdk.UnavailableError, match="oops"):
            c.traces.list_services()


def test_wait_for_trace() -> None:
    oap = Oap()
    with oap.client() as c:
        trace = c.traces.wait_for_trace(OTLP_TRACE, timeout_s=2, interval_s=0.01, min_spans=2)
        assert len(trace.spans) == 2
        native = c.traces.wait_for_trace(NATIVE_TRACE, timeout_s=2, interval_s=0.01, min_spans=3)
        assert native.trace_id == NATIVE_TRACE
        with pytest.raises(sdk.TimeoutError):
            c.traces.wait_for_trace(NATIVE_TRACE, timeout_s=0.05, interval_s=0.01, min_spans=9)


def test_wait_for_native_trace_ignores_not_found() -> None:
    calls: list[int] = []

    def route(r: httpx.Request) -> httpx.Response | None:
        if r.url.path == "/graphql" and "queryTrace(" in gql(r)["query"]:
            calls.append(1)
            if len(calls) == 1:
                return httpx.Response(404, text="missing")
        return None

    with Oap(route).client() as c:
        trace = c.traces.wait_for_trace(NATIVE_TRACE, timeout_s=2, interval_s=0.01)
    assert trace.trace_id == NATIVE_TRACE and len(calls) == 2


# --------------------------------------------------------------------------- search


def test_search_traces_zipkin() -> None:
    oap = Oap()
    start = NOW - timedelta(minutes=10)
    with oap.client() as c:
        hits = c.traces.search_traces(
            sdk.TraceSearchQuery(
                service="sw-otlp-1fddf66a",
                operation="SELECT orders",
                tags={"run_id": "1fddf66a", "http.method": "GET"},
                min_duration_micros=40000,
                max_duration_micros=90000,
                start=start,
                limit=10,
            )
        )
        hit = next(h for h in hits if h.trace_id == OTLP_TRACE)
        assert hit.span_count == 2 and hit.root_name == "get /checkout"
        c.traces.search_traces()
    first, second = zipkin_params(oap, "traces")
    assert first == {
        "serviceName": "sw-otlp-1fddf66a",
        "spanName": "select orders",
        "annotationQuery": "run_id=1fddf66a and http.method=GET",
        "minDuration": "40000",
        "maxDuration": "90000",
        "endTs": str(NOW_MS),
        "lookback": "600000",
        "limit": "10",
    }
    assert second == {"endTs": str(NOW_MS), "limit": "20"}


@pytest.mark.parametrize(
    ("query", "message"),
    [
        (sdk.TraceSearchQuery(limit=0), "limit"),
        (sdk.TraceSearchQuery(min_duration_micros=-1), "non-negative"),
        (sdk.TraceSearchQuery(start=NOW, end=NOW - timedelta(seconds=1)), "start must not"),
        (sdk.TraceSearchQuery(end=datetime(1960, 1, 1, tzinfo=timezone.utc)), "valid times"),
        (sdk.TraceSearchQuery(tags={"a": "x and y"}), " and "),
    ],
)
def test_search_traces_validation(query: sdk.TraceSearchQuery, message: str) -> None:
    with Oap().client() as c, pytest.raises(sdk.ValidationError, match=message):
        c.traces.search_traces(query)


def test_search_traces_native() -> None:
    oap = Oap()
    with oap.client(trace_backend="native") as c:
        hits = c.traces.search_traces(
            sdk.TraceSearchQuery(
                service="sw-native-b-1fddf66a",
                operation="/native/downstream",
                tags={"run_id": "1fddf66a"},
                min_duration_micros=1500,
                max_duration_micros=2500,
                limit=5,
            )
        )
        assert hits and all(isinstance(h, sdk.TraceSummary) for h in hits)
        c.traces.search_traces(sdk.TraceSearchQuery(start=NOW - timedelta(minutes=5)))
        with pytest.raises(sdk.ValidationError, match="needs service"):
            c.traces.search_traces(sdk.TraceSearchQuery(operation="/x"))
    first, second = oap.graphql_calls("queryTraces")
    cond = first["variables"]["c"]
    assert (cond["minTraceDuration"], cond["maxTraceDuration"]) == (1, 3)
    assert cond["endpointId"] == sdk.endpoint_id("sw-native-b-1fddf66a", "/native/downstream")
    assert cond["paging"] == {"pageNum": 1, "pageSize": 5}
    assert cond["queryDuration"]["step"] == "MINUTE"
    assert second["variables"]["c"]["queryDuration"]["start"] == "2026-11-18 2106"


# --------------------------------------------------------------------------- services, ops, deps


def test_list_services_and_operations() -> None:
    oap = Oap()
    with oap.client() as c:
        services = c.traces.list_services()
        assert "sw-otlp-traceonly-1fddf66a" in services and "sw-native-a-1fddf66a" in services
        assert services == sorted(set(services))
        ops = c.traces.list_operations("sw-otlp-1fddf66a")
        assert "get /checkout" in ops and ops == sorted(set(ops))
        with pytest.raises(sdk.ValidationError):
            c.traces.list_operations("")
    with Oap().client(trace_backend="zipkin") as c:
        assert c.traces.list_services() == ["sw-otlp-1fddf66a", "sw-otlp-traceonly-1fddf66a"]
        assert c.traces.list_operations("x") == ["get /checkout", "gzip-span", "select orders"]
    native = Oap()
    with native.client(trace_backend="native") as c:
        assert "sw-otlp-traceonly-1fddf66a" not in c.traces.list_services()
        assert c.traces.list_operations("sw-native-a-1fddf66a")
    assert not [r for r in native.requests if "zipkin" in r.url.path]


def test_virtual_services_are_skipped() -> None:
    oap = Oap(
        lambda r: (
            data(
                {
                    "listServices": [
                        {"name": "real", "normal": True},
                        {"name": "virtual", "normal": False},
                    ]
                }
            )
            if r.url.path == "/graphql" and "listServices" in gql(r)["query"]
            else None
        )
    )
    with oap.client(trace_backend="native") as c:
        assert c.traces.list_services() == ["real"]


def test_get_dependencies() -> None:
    oap = Oap()
    with oap.client() as c:
        deps = c.traces.get_dependencies(start=NOW - timedelta(minutes=15))
        assert sdk.DependencyLink("sw-native-a-1fddf66a", "sw-native-b-1fddf66a", 0) in deps
    with Oap().client(trace_backend="zipkin") as c, pytest.raises(sdk.UnsupportedError):
        c.traces.get_dependencies()


# --------------------------------------------------------------------------- async


async def test_async_trace_query_client() -> None:
    oap = Oap()
    async with oap.aclient() as c:
        t = c.traces
        assert t.backend == "auto"
        assert (await t.get_trace(OTLP_TRACE)) is not None
        assert OTLP_TRACE in [h.trace_id for h in await t.search_traces()]
        assert await t.list_services()
        assert await t.list_operations("sw-otlp-1fddf66a")
        assert await t.get_dependencies()
        hex_trace = await t.wait_for_trace(OTLP_TRACE, timeout_s=2, interval_s=0.01)
        assert hex_trace.trace_id == OTLP_TRACE
        native = await t.wait_for_trace(NATIVE_TRACE, timeout_s=2, interval_s=0.01)
        assert native.trace_id == NATIVE_TRACE
        with pytest.raises(sdk.TimeoutError):
            await t.wait_for_trace(NATIVE_TRACE, timeout_s=0.05, interval_s=0.01, min_spans=9)


async def test_async_wait_ignores_not_found() -> None:
    calls: list[int] = []

    def route(r: httpx.Request) -> httpx.Response | None:
        if r.url.path == "/graphql" and "queryTrace(" in gql(r)["query"]:
            calls.append(1)
            if len(calls) == 1:
                return httpx.Response(404, text="missing")
        return None

    async with Oap(route).aclient() as c:
        trace = await c.traces.wait_for_trace(NATIVE_TRACE, timeout_s=2, interval_s=0.01)
    assert trace.trace_id == NATIVE_TRACE
