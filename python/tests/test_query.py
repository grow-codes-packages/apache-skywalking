"""SkyWalkingQueryClient (GraphQL): metadata, native traces, logs, topology, capabilities."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any

import httpx
import pytest

import grow_apache_skywalking as sdk
from _oap import NOW_MS, Oap, data, gql, reply

NOW = datetime(1970, 1, 1, tzinfo=timezone.utc) + timedelta(milliseconds=NOW_MS)
NATIVE_TRACE = "1fddf66a1c64db2c1345419627769f1f.1.1795036241775"
SEG_A = "1fddf66a0691f3d5361838903180a743.1.1795036241775"


def answer(needle: str, response: httpx.Response) -> Any:
    def route(r: httpx.Request) -> httpx.Response | None:
        if r.url.path == "/graphql" and needle in gql(r)["query"]:
            return response
        return None

    return route


def test_time_info_and_capabilities_are_cached() -> None:
    oap = Oap()
    with oap.client() as c:
        info = c.query.time_info()
        assert info == sdk.TimeInfo("+0000", 1795036301381)
        caps = c.query.capabilities()
        assert caps == sdk.SkyWalkingCapabilities(query_traces_v2=True, logs_by_keywords=False)
        assert c.query.capabilities() is caps
        c.query.list_instances("svc")
    assert len(oap.graphql_calls("hasQueryTracesV2Support")) == 1
    assert len(oap.graphql_calls("getTimeInfo")) == 1


def test_time_info_fallbacks() -> None:
    oap = Oap(answer("getTimeInfo", data({"getTimeInfo": {"timezone": 8}})))
    with oap.client() as c:
        assert c.query.time_info() == sdk.TimeInfo("+0000", NOW_MS)


def test_list_and_find_services() -> None:
    oap = Oap()
    with oap.client() as c:
        services = c.query.list_services()
        assert services[0] == sdk.SkyWalkingService(
            id="c3ctb3RscC0xZmRkZjY2YQ==.1",
            name="sw-otlp-1fddf66a",
            group="",
            short_name="sw-otlp-1fddf66a",
            layers=("GENERAL",),
            normal=True,
        )
        c.query.list_services("GENERAL", sdk.TimeRange(start=NOW - timedelta(minutes=1)))
        found = c.query.find_service("sw-native-a-1fddf66a")
        assert found is not None and found.name == "sw-native-a-1fddf66a"
        assert found.short_name == "" and found.normal
        assert c.query.find_service("no-such-service") is None
        with pytest.raises(sdk.ValidationError, match="name is required"):
            c.query.find_service(" ")
    calls = oap.graphql_calls("listServices")
    assert calls[0]["variables"] == {"l": None}
    assert calls[1]["variables"] == {"l": "GENERAL"}


def test_list_endpoints() -> None:
    oap = Oap()
    with oap.client() as c:
        eps = c.query.list_endpoints("sw-native-a-1fddf66a")
        assert all(isinstance(e, sdk.SkyWalkingEndpoint) for e in eps) and eps
        c.query.list_endpoints("svc", "downstream", limit=5)
        with pytest.raises(sdk.ValidationError, match="limit"):
            c.query.list_endpoints("svc", limit=0)
        with pytest.raises(sdk.ValidationError, match="service is required"):
            c.query.list_endpoints("")
    calls = oap.graphql_calls("findEndpoint")
    assert calls[0]["variables"] == {
        "k": None,
        "s": "c3ctbmF0aXZlLWEtMWZkZGY2NmE=.1",
        "l": 100,
    }
    assert calls[1]["variables"]["k"] == "downstream" and calls[1]["variables"]["l"] == 5
    assert "duration" not in calls[0]["query"]


def test_list_instances_sends_duration_in_oap_timezone() -> None:
    oap = Oap(answer("getTimeInfo", data({"getTimeInfo": {"timezone": "+0100"}})))
    with oap.client() as c:
        inst = c.query.list_instances("sw-native-a-1fddf66a")
        assert inst[0].name == "inst-a-1fddf66a"
        assert inst[0].language == "GO"
        assert inst[0].attributes == (sdk.InstanceAttribute("ipv4s", ""),)
    call = oap.graphql_calls("listInstances")[0]
    assert call["variables"]["d"] == {
        "start": "2026-11-18 2111",
        "end": "2026-11-18 2211",
        "step": "MINUTE",
    }


def test_query_traces() -> None:
    oap = Oap()
    with oap.client() as c:
        res = c.query.query_traces(
            service="sw-native-a-1fddf66a",
            instance="inst-a",
            endpoint="/native/entry",
            tags={"run_id": "1fddf66a"},
            min_duration_ms=1.4,
            max_duration_ms=500,
            trace_state="ERROR",
            order="BY_DURATION",
            paging=sdk.Paging(2, 5),
        )
        assert res.traces and all(isinstance(t, sdk.NativeTrace) for t in res.traces)
        assert res.retrieved_time_range is not None
        c.query.query_traces(trace_id=NATIVE_TRACE)
    first, second = oap.graphql_calls("queryTraces")
    cond = first["variables"]["c"]
    assert cond["serviceId"] == sdk.service_id("sw-native-a-1fddf66a")
    assert cond["serviceInstanceId"] == sdk.instance_id("sw-native-a-1fddf66a", "inst-a")
    assert cond["endpointId"] == sdk.endpoint_id("sw-native-a-1fddf66a", "/native/entry")
    assert cond["tags"] == [{"key": "run_id", "value": "1fddf66a"}]
    assert (cond["minTraceDuration"], cond["maxTraceDuration"]) == (1, 500)
    assert (cond["traceState"], cond["queryOrder"]) == ("ERROR", "BY_DURATION")
    assert cond["paging"] == {"pageNum": 2, "pageSize": 5}
    assert "queryDuration" in cond
    assert second["variables"]["c"]["traceId"] == NATIVE_TRACE
    assert "queryDuration" not in second["variables"]["c"]


@pytest.mark.parametrize(
    ("kwargs", "message"),
    [
        ({"instance": "i"}, "need service"),
        ({"min_duration_ms": -1}, "non-negative"),
        ({"max_duration_ms": True}, "non-negative"),
        ({"trace_id": ""}, "trace_id is required"),
        ({"paging": sdk.Paging(0, 1)}, "page_num"),
        ({"paging": sdk.Paging(1, 10_001)}, "page_size"),
    ],
)
def test_query_traces_validation(kwargs: dict[str, Any], message: str) -> None:
    with Oap().client() as c, pytest.raises(sdk.ValidationError, match=message):
        c.query.query_traces(**kwargs)


def test_query_traces_odd_bodies() -> None:
    oap = Oap(answer("queryTraces", data({"queryTraces": {"traces": [{"spans": []}, 3]}})))
    with oap.client() as c:
        assert c.query.query_traces() == sdk.QueryTracesResult((), None)
    unsupported = Oap(answer("queryTraces", reply("queryBasicTraces.unsupported")))
    with unsupported.client() as c, pytest.raises(sdk.UnsupportedError):
        c.query.query_traces()


def test_get_trace_native() -> None:
    oap = Oap()
    with oap.client() as c:
        trace = c.query.get_trace(NATIVE_TRACE)
        assert trace is not None and trace.trace_id == NATIVE_TRACE
        assert len(trace.spans) == 3
        b = next(s for s in trace.spans if s.service_code == "sw-native-b-1fddf66a")
        assert b.refs[0] == sdk.NativeSpanRef(NATIVE_TRACE, SEG_A, 1, "CROSS_PROCESS")
        assert b.is_error and b.logs[0].data[0] == sdk.KeyValue("event", "error")
        assert c.query.get_trace("055ffba8ecc69a64950538fda698a308") is None
        with pytest.raises(sdk.ValidationError):
            c.query.get_trace("")
    assert oap.graphql_calls("queryTrace(")[0]["variables"] == {"id": NATIVE_TRACE}


def test_query_logs() -> None:
    oap = Oap()
    with oap.client() as c:
        res = c.query.query_logs(service="sw-otlp-1fddf66a", instance="inst-1fddf66a")
        assert len(res.logs) == 2 and res.error_reason is None
        log = res.logs[1]
        assert log.trace_id == "055ffba8ecc69a64950538fda698a308"
        assert log.tag("level") == "ERROR" and log.tag("nope") is None
        assert res.logs[0].endpoint_name == "" and res.logs[0].trace_id == ""
        c.query.query_logs(
            trace_id="t1", segment_id="s1", span_id=2, tags={"level": "ERROR"}, order="ASC"
        )
        c.query.query_logs(trace_id="t1", range=sdk.TimeRange(start=NOW - timedelta(hours=2)))
    calls = oap.graphql_calls("queryLogs")
    assert calls[0]["variables"]["c"]["serviceInstanceId"] == sdk.instance_id(
        "sw-otlp-1fddf66a", "inst-1fddf66a"
    )
    assert calls[0]["variables"]["c"]["queryOrder"] == "DES"
    second = calls[1]["variables"]["c"]
    assert second["relatedTrace"] == {"traceId": "t1", "segmentId": "s1", "spanId": 2}
    assert second["tags"] == [{"key": "level", "value": "ERROR"}]
    assert "queryDuration" not in second and second["queryOrder"] == "ASC"
    assert "queryDuration" in calls[2]["variables"]["c"]


def test_query_logs_keywords_client_side_on_banyandb() -> None:
    oap = Oap()
    with oap.client() as c:
        res = c.query.query_logs(service="svc", keywords=["PAYMENT"], excluding_keywords=["x"])
        assert [lg.content for lg in res.logs] == ["payment accepted otlp-log-1fddf66a"]
        res = c.query.query_logs(service="svc", excluding_keywords=["payment"])
        assert [lg.content for lg in res.logs] == [
            "native log with otel trace native-log-1fddf66a-otel"
        ]
    for call in oap.graphql_calls("queryLogs"):
        assert "keywordsOfContent" not in call["variables"]["c"]


def test_query_logs_keywords_server_side_when_supported() -> None:
    oap = Oap(
        answer(
            "hasQueryTracesV2Support",
            data({"hasQueryTracesV2Support": True, "supportQueryLogsByKeywords": True}),
        ),
        answer("queryLogs", data({"queryLogs": {"errorReason": "partial", "logs": [{}]}})),
    )
    with oap.client() as c:
        res = c.query.query_logs(keywords=["a"], excluding_keywords=["b"])
        assert res.error_reason == "partial"
        assert res.logs == (sdk.NativeLog(),)
        c.query.query_logs(keywords=["only"])
    calls = oap.graphql_calls("queryLogs")
    assert calls[0]["variables"]["c"]["keywordsOfContent"] == ["a"]
    assert calls[0]["variables"]["c"]["excludingKeywordsOfContent"] == ["b"]
    assert "excludingKeywordsOfContent" not in calls[1]["variables"]["c"]


def test_query_logs_errors() -> None:
    oap = Oap(answer("queryLogs", reply("queryLogs.no-duration-error")))
    with oap.client() as c:
        with pytest.raises(sdk.ValidationError):
            c.query.query_logs(trace_id="x")
        with pytest.raises(sdk.ValidationError, match="need service"):
            c.query.query_logs(endpoint="/x")


def test_list_log_tag_keys_and_topology() -> None:
    oap = Oap()
    with oap.client() as c:
        assert c.query.list_log_tag_keys() == ["level", "run_id", "span_id", "trace_id"]
        topo = c.query.topology()
        user = sdk.TopologyNode("VXNlcg==.0", "User", "USER", False, ("UNDEFINED",))
        assert topo.nodes[0] == user
        assert topo.calls[0].detect_points == ("CLIENT", "SERVER")
        svc = c.query.topology(service="sw-native-a-1fddf66a")
        assert any(n.name == "sw-native-b-1fddf66a" for n in svc.nodes)
        c.query.topology(layer="GENERAL")
        with pytest.raises(sdk.ValidationError):
            c.query.topology(service="")
    calls = oap.graphql_calls("getGlobalTopology")
    assert calls[0]["variables"]["l"] is None and calls[1]["variables"]["l"] == "GENERAL"
    assert oap.graphql_calls("getServiceTopology")[0]["variables"]["s"] == sdk.service_id(
        "sw-native-a-1fddf66a"
    )
    odd = Oap(answer("queryLogTagAutocompleteKeys", data({"queryLogTagAutocompleteKeys": None})))
    with odd.client() as c:
        assert c.query.list_log_tag_keys() == []


def test_decoders_tolerate_garbage() -> None:
    assert sdk.decode_native_log(None) == sdk.NativeLog()
    assert sdk.decode_native_log({"timestamp": True}).timestamp == 0
    assert sdk.decode_topology(None) == sdk.Topology()
    assert sdk.native_trace_from_spans(None) is None
    span = sdk.decode_native_span({"spanId": True, "isError": "yes", "refs": [1]})
    assert span.span_id == 0 and span.parent_span_id == -1 and not span.is_error
    assert sdk.key_values([{"key": "a", "value": 1}, {"key": "b", "value": None}, 3]) == (
        sdk.KeyValue("a", "1"),
        sdk.KeyValue("b", ""),
    )


async def test_async_query_client() -> None:
    oap = Oap()
    async with oap.aclient() as c:
        q = c.query
        assert (await q.graphql("{version}")).data == {"version": "10.4.0-b272da3"}
        assert (await q.time_info()).timezone == "+0000"
        assert (await q.capabilities()).query_traces_v2
        assert await q.list_services()
        assert (await q.find_service("sw-native-a-1fddf66a")) is not None
        assert await q.list_endpoints("svc", "x", limit=3)
        assert await q.list_instances("svc")
        assert (await q.query_traces(service="svc")).traces
        assert (await q.get_trace(NATIVE_TRACE)) is not None
        assert len((await q.query_logs(service="svc")).logs) == 2
        assert await q.list_log_tag_keys()
        assert (await q.topology(service="svc")).nodes
