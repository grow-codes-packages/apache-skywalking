"""Native log exporter (/v3/logs), record mapping, log query syntax, search paging, log clients."""

from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from typing import Any

import httpx
import pytest

import grow_apache_skywalking as sdk
from _oap import NOW_MS, URL, Oap, data, gql

NOW = datetime(1970, 1, 1, tzinfo=timezone.utc) + timedelta(milliseconds=NOW_MS)
OTLP_TRACE = "055ffba8ecc69a64950538fda698a308"


def native_log(i: int, content: str, ts: int = NOW_MS - 1000, **kw: Any) -> dict[str, Any]:
    return {
        "serviceName": "svc",
        "serviceInstanceName": "",
        "endpointName": "",
        "traceId": "",
        "timestamp": ts - i,
        "contentType": "TEXT",
        "content": content,
        "tags": kw.get("tags", [{"key": "level", "value": "INFO"}]),
    }


def paged_logs(pages: list[list[dict[str, Any]]]) -> Any:
    """Answers queryLogs with ``pages[pageNum - 1]`` (empty beyond)."""

    def route(r: httpx.Request) -> httpx.Response | None:
        if r.url.path != "/graphql" or "queryLogs" not in gql(r)["query"]:
            return None
        page = gql(r)["variables"]["c"]["paging"]["pageNum"]
        logs = pages[page - 1] if page <= len(pages) else []
        return data({"queryLogs": {"errorReason": None, "logs": logs}})

    return route


# --------------------------------------------------------------------------- mapping


def test_record_to_log_data() -> None:
    record = sdk.LogRecord(
        body="payment failed",
        severity=sdk.Severity.ERROR,
        timestamp=NOW,
        attributes={"run": "r1", "n": 2, "ok": True, "obj": {"a": [1]}, "level": "x"},
        resource={"service.name": "checkout", "service.instance.id": 7},
        trace_id=OTLP_TRACE,
        span_id="00f067aa0ba902b7",
    )
    assert sdk.record_to_log_data(record) == {
        "timestamp": NOW_MS,
        "service": "checkout",
        "serviceInstance": "7",
        "body": {"text": {"text": "payment failed"}},
        "traceContext": {"traceId": OTLP_TRACE},
        "tags": {
            "data": [
                {"key": "level", "value": "ERROR"},
                {"key": "run", "value": "r1"},
                {"key": "n", "value": "2"},
                {"key": "ok", "value": "true"},
                {"key": "obj", "value": '{"a":[1]}'},
                {"key": "span_id", "value": "00f067aa0ba902b7"},
            ]
        },
        "layer": "GENERAL",
    }
    minimal = sdk.record_to_log_data(sdk.LogRecord(body="x", timestamp=NOW), "fallback")
    assert minimal["service"] == "fallback"
    assert "traceContext" not in minimal and "serviceInstance" not in minimal
    assert sdk.tag_text(1.5) == "1.5" and sdk.tag_text(False) == "false"


def test_log_to_record() -> None:
    log = sdk.NativeLog(
        service_name="svc",
        service_instance_name="inst",
        endpoint_name="/x",
        trace_id=OTLP_TRACE.upper(),
        timestamp=NOW_MS,
        content="hello",
        tags=(
            sdk.KeyValue("level", "WARN"),
            sdk.KeyValue("span_id", "00f067aa0ba902b7"),
            sdk.KeyValue("run", "r"),
        ),
    )
    rec = sdk.log_to_record(log)
    assert rec.severity == sdk.Severity.WARN and rec.severity_text == "WARN"
    assert rec.trace_id == OTLP_TRACE and rec.span_id == "00f067aa0ba902b7"
    assert rec.attributes == {"run": "r"}
    assert rec.resource == {
        "service.name": "svc",
        "service.instance.id": "inst",
        "sw.endpoint": "/x",
    }
    assert rec.timestamp == NOW
    native = sdk.log_to_record(
        sdk.NativeLog(
            service_name="svc",
            trace_id="abc.1.2",
            tags=(sdk.KeyValue("level", "chatty"), sdk.KeyValue("span_id", "nothex")),
        )
    )
    assert native.severity == sdk.Severity.INFO and native.severity_text == "chatty"
    assert native.trace_id is None and native.attributes == {
        "span_id": "nothex",
        "sw.trace_id": "abc.1.2",
    }
    plain = sdk.log_to_record(sdk.NativeLog(service_name="svc"))
    assert plain.severity_text == "INFO" and plain.resource == {"service.name": "svc"}


def test_parse_log_query_and_matches() -> None:
    q = sdk.parse_log_query(
        'service=svc instance=i endpoint=/e traceId=t1 run_id=r level="WARN X" hello "two words"'
    )
    assert q == sdk.ParsedLogQuery(
        service="svc",
        instance="i",
        endpoint="/e",
        trace_id="t1",
        tags={"run_id": "r", "level": "WARN X"},
        text=("hello", "two words"),
    )
    assert sdk.parse_log_query('trace_id=t2 ""').trace_id == "t2"
    assert sdk.parse_log_query("") == sdk.ParsedLogQuery()
    log = sdk.NativeLog(content="Hello World", tags=(sdk.KeyValue("run", "R-1"),))
    assert sdk.matches_text(log, [])
    assert sdk.matches_text(log, ["hello", "r-1"])
    assert not sdk.matches_text(log, ["hello", "nope"])


# --------------------------------------------------------------------------- exporter + clients


def test_log_client_posts_native_logs() -> None:
    oap = Oap()
    with oap.client() as c:
        logs = c.log_client(
            service_name="checkout",
            service_instance="inst-1",
            options=sdk.BatchOptions(flush_interval_s=60),
            resource={"team": "pay"},
        )
        logs.send(sdk.LogInput("order created", attributes={"password": "hunter2"}))
        logs.flush(5)
        assert logs.stats().sent == 1
    req = next(r for r in oap.requests if r.url.path == "/v3/logs")
    sent = json.loads(req.content)
    assert sent[0]["service"] == "checkout" and sent[0]["serviceInstance"] == "inst-1"
    assert "hunter2" not in req.content.decode()
    assert req.headers.get("content-encoding") is None


def test_exporter_direct() -> None:
    oap = Oap()
    transport = sdk.HttpTransport(oap.config(), http_transport=oap.transport)
    exporter = sdk.SkyWalkingLogExporter(transport, owns_transport=True)
    assert exporter.export([]) == sdk.ExportResult(accepted=0)
    result = exporter.export([sdk.LogRecord(body="x")], gzip=True, timeout_s=0)
    assert result == sdk.ExportResult(accepted=1, retries=0)
    exporter.shutdown()
    exporter.shutdown()
    assert transport.closed
    shared = sdk.HttpTransport(oap.config(), http_transport=oap.transport)
    sdk.SkyWalkingLogExporter(shared).shutdown()
    assert not shared.closed
    shared.close()


def test_exporter_raises_transport_errors() -> None:
    oap = Oap(lambda r: httpx.Response(500, text="boom") if r.url.path == "/v3/logs" else None)
    transport = sdk.HttpTransport(oap.config(), http_transport=oap.transport)
    with pytest.raises(sdk.UnavailableError):
        sdk.SkyWalkingLogExporter(transport).export([sdk.LogRecord(body="x")])
    transport.close()


def test_create_log_client() -> None:
    oap = Oap()
    client = sdk.create_log_client(
        oap.config(), service_name="standalone", http_transport=oap.transport
    )
    client.send(sdk.LogInput("standalone hello"))
    client.close()
    assert client.stats().sent == 1
    sent = json.loads(next(r for r in oap.requests if r.url.path == "/v3/logs").content)
    assert sent[0]["service"] == "standalone"


def test_create_log_client_from_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("GROW_APACHE_SKYWALKING_URL", URL)
    oap = Oap()
    client = sdk.create_log_client(http_transport=oap.transport)
    client.close()


async def test_async_log_clients() -> None:
    oap = Oap()
    async with oap.aclient() as c:
        logs = c.log_client(service_name="async-svc")
        logs.send(sdk.LogInput("async hello"))
        await logs.flush(5)
        assert logs.stats().sent == 1
    standalone = sdk.create_async_log_client(
        oap.config(), service_name="async-standalone", http_transport=oap.transport
    )
    standalone.send(sdk.LogInput("x"))
    await standalone.close()
    assert standalone.stats().sent == 1
    exporter = sdk.AsyncSkyWalkingLogExporter(
        sdk.AsyncHttpTransport(oap.config(), http_transport=oap.transport), owns_transport=True
    )
    assert await exporter.export([]) == sdk.ExportResult(accepted=0)
    await exporter.shutdown()
    await exporter.shutdown()


async def test_create_async_log_client_from_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("GROW_APACHE_SKYWALKING_URL", URL)
    client = sdk.create_async_log_client(http_transport=Oap().transport)
    await client.close()


# --------------------------------------------------------------------------- search


def test_search_by_fields_uses_limit_as_page_size() -> None:
    oap = Oap()
    with oap.client() as c:
        res = c.logs.search(
            f"service=sw-otlp-1fddf66a trace_id={OTLP_TRACE} level=ERROR",
            start=NOW - timedelta(days=1),
            limit=5,
            order="asc",
        )
    assert next(r.body for r in res.records) == "payment accepted otlp-log-1fddf66a"
    assert res.next_cursor is None
    cond = oap.graphql_calls("queryLogs")[0]["variables"]["c"]
    assert cond["serviceId"] == sdk.service_id("sw-otlp-1fddf66a")
    assert cond["relatedTrace"] == {"traceId": OTLP_TRACE}
    assert cond["tags"] == [{"key": "level", "value": "ERROR"}]
    assert cond["paging"] == {"pageNum": 1, "pageSize": 5}
    assert cond["queryOrder"] == "ASC"
    assert cond["queryDuration"]["step"] == "MINUTE"


def test_search_filters_free_text_client_side_and_pages() -> None:
    page1 = [native_log(i, f"noise {i}") for i in range(999)] + [native_log(999, "Match A")]
    page2 = [native_log(1000, "match b"), native_log(1001, "match c"), native_log(1002, "z")]
    oap = Oap(paged_logs([page1, page2]))
    with oap.client() as c:
        first = c.logs.search("match", limit=2)
        assert [r.body for r in first.records] == ["Match A", "match b"]
        assert first.next_cursor == "2:1:1000"
        second = c.logs.search("match", limit=2, cursor=first.next_cursor)
        assert [r.body for r in second.records] == ["match c"]
        assert second.next_cursor is None
    sizes = [call["variables"]["c"]["paging"] for call in oap.graphql_calls("queryLogs")]
    assert sizes == [
        {"pageNum": 1, "pageSize": 1000},
        {"pageNum": 2, "pageSize": 1000},
        {"pageNum": 2, "pageSize": 1000},
    ]


def test_search_cursor_at_page_end_and_scan_limit() -> None:
    full = [native_log(i, f"hit {i}") for i in range(3)]
    oap = Oap(paged_logs([full, full]))
    with oap.client() as c:
        res = c.logs.search("", limit=3)
        assert len(res.records) == 3 and res.next_cursor == "2:0:3"
    noise = [native_log(i, "noise") for i in range(1000)]
    capped = Oap(paged_logs([noise, noise, noise]))
    with capped.client(max_scan_pages=2) as c:
        res = c.logs.search("needle")
        assert res.records == [] and res.next_cursor == "3:0:1000"


def test_search_drops_records_outside_window() -> None:
    logs = [native_log(0, "old", ts=NOW_MS - 7_200_000), native_log(0, "new")]
    with Oap(paged_logs([logs])).client() as c:
        assert [r.body for r in c.logs.search("").records] == ["new"]
        assert c.logs.search("", end=NOW - timedelta(hours=1, minutes=30)).records[0].body == "old"


@pytest.mark.parametrize(
    ("kwargs", "message"),
    [
        ({"limit": 0}, "limit"),
        ({"limit": True}, "limit"),
        ({"start": NOW, "end": NOW - timedelta(seconds=1)}, "time window"),
        ({"cursor": "nope"}, "cursor"),
        ({"cursor": "0:0:10"}, "cursor"),
        ({"cursor": "1:0:0"}, "cursor"),
    ],
)
def test_search_validation(kwargs: dict[str, Any], message: str) -> None:
    with Oap().client() as c, pytest.raises(sdk.ValidationError, match=message):
        c.logs.search("x", **kwargs)


def test_streams_labels_wait_and_tail() -> None:
    oap = Oap()
    with oap.client() as c:
        assert "sw-native-a-1fddf66a" in c.logs.list_streams()
        assert c.logs.list_labels() == ["level", "run_id", "span_id", "trace_id"]
        found = c.logs.wait_for_log("payment", timeout_s=2, interval_s=0.01)
        assert found.body == "payment accepted otlp-log-1fddf66a"
        assert found.attributes["run_id"] == "1fddf66a"
    with pytest.raises(sdk.ValidationError):
        sdk.SkyWalkingClient(oap.config(), http_transport=oap.transport, max_scan_pages=0)


async def test_async_log_query_client() -> None:
    oap = Oap()
    async with oap.aclient() as c:
        res = await c.logs.search("payment")
        assert len(res.records) == 1
        assert "sw-native-a-1fddf66a" in await c.logs.list_streams()
        assert "run_id" in await c.logs.list_labels()
