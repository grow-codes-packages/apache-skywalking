"""Round trips against the real SkyWalking OAP 10.4.0 + BanyanDB (docker/compose.yml).

Every public function is called at least once with a write → read-back assertion. Native traces
are seeded over OAP's documented HTTP API (``/v3/segments``) because no SkyWalking agent runs in
the test container; OTLP spans come from the package's own ``init_tracing``.
"""

from __future__ import annotations

import asyncio
import dataclasses
import secrets
import time
from collections.abc import Callable, Iterator
from datetime import datetime, timedelta, timezone
from typing import Any, TypeVar

import pytest
from factories import core_client, tracing_handle, unique
from opentelemetry import trace as otel_trace
from opentelemetry.context import Context

import grow_apache_skywalking as sdk

T = TypeVar("T")


def poll(label: str, fn: Callable[[], T], ok: Callable[[T], bool], timeout_s: float = 120) -> T:
    deadline = time.monotonic() + timeout_s
    last: Any = None
    error: BaseException | None = None
    while time.monotonic() < deadline:
        try:
            last = fn()
            if ok(last):
                return last
        except sdk.ServiceError as exc:
            error = exc
        time.sleep(2)
    raise AssertionError(f"{label}: not satisfied in {timeout_s}s; last={last!r} error={error!r}")


def ago(minutes: float) -> datetime:
    return datetime.now(timezone.utc) - timedelta(minutes=minutes)


@dataclasses.dataclass
class World:
    client: sdk.SkyWalkingClient
    run: str
    svc_a: str
    svc_b: str
    svc_otlp: str
    svc_logs: str
    native_trace: str
    seg_a: str
    seg_b: str
    otlp_trace: str
    otlp_span: str


def seed_native(client: sdk.SkyWalkingClient, run: str, svc_a: str, svc_b: str) -> tuple[str, ...]:
    """Seed a native A → B trace; B's ref is built by decoding the sw8 header of A's exit span."""
    now = int(time.time() * 1000)
    trace_id = f"{secrets.token_hex(16)}.1.{now}"
    seg_a = f"{secrets.token_hex(16)}.1.{now}"
    seg_b = f"{secrets.token_hex(16)}.2.{now}"
    header = sdk.encode_sw8(
        sdk.Sw8Context(
            True, trace_id, seg_a, 1, svc_a, f"inst-a-{run}", "/it/entry", f"{svc_b}:8080"
        )
    )
    ref = sdk.decode_sw8(header)
    assert ref is not None
    segments = [
        {
            "traceId": trace_id,
            "traceSegmentId": seg_a,
            "service": svc_a,
            "serviceInstance": f"inst-a-{run}",
            "spans": [
                {
                    "spanId": 1,
                    "parentSpanId": 0,
                    "startTime": now - 80,
                    "endTime": now - 20,
                    "operationName": "/it/downstream",
                    "peer": f"{svc_b}:8080",
                    "spanType": "Exit",
                    "spanLayer": "Http",
                    "componentId": 2,
                    "isError": False,
                    "tags": [{"key": "http.method", "value": "GET"}],
                },
                {
                    "spanId": 0,
                    "parentSpanId": -1,
                    "startTime": now - 100,
                    "endTime": now,
                    "operationName": "/it/entry",
                    "peer": "",
                    "spanType": "Entry",
                    "spanLayer": "Http",
                    "componentId": 1,
                    "isError": False,
                    "tags": [
                        {"key": "http.method", "value": "GET"},
                        {"key": "run_id", "value": run},
                    ],
                },
            ],
        },
        {
            "traceId": ref.trace_id,
            "traceSegmentId": seg_b,
            "service": svc_b,
            "serviceInstance": f"inst-b-{run}",
            "spans": [
                {
                    "spanId": 0,
                    "parentSpanId": -1,
                    "startTime": now - 70,
                    "endTime": now - 30,
                    "operationName": "/it/downstream",
                    "peer": "",
                    "spanType": "Entry",
                    "spanLayer": "Http",
                    "componentId": 1,
                    "isError": True,
                    "refs": [
                        {
                            "refType": "CrossProcess",
                            "traceId": ref.trace_id,
                            "parentTraceSegmentId": ref.parent_trace_segment_id,
                            "parentSpanId": ref.parent_span_id,
                            "parentService": ref.parent_service,
                            "parentServiceInstance": ref.parent_service_instance,
                            "parentEndpoint": ref.parent_endpoint,
                            "networkAddressUsedAtPeer": ref.network_address_used_at_peer,
                        }
                    ],
                    "tags": [{"key": "run_id", "value": run}],
                    "logs": [
                        {
                            "time": now - 40,
                            "data": [
                                {"key": "event", "value": "error"},
                                {"key": "message", "value": "boom"},
                            ],
                        }
                    ],
                },
            ],
        },
    ]
    resp = client.request(sdk.Request("POST", "/v3/segments", json=segments))
    assert resp.status == 200, resp.text
    return trace_id, seg_a, seg_b


@pytest.fixture(scope="module")
def world(env: dict[str, str]) -> Iterator[World]:
    run = unique("it").removeprefix("it-py-")
    client = core_client(env)
    svc_a, svc_b = f"it-native-a-{run}", f"it-native-b-{run}"
    svc_otlp, svc_logs = f"it-otlp-{run}", f"it-logs-{run}"
    native_trace, seg_a, seg_b = seed_native(client, run, svc_a, svc_b)
    handle = client.init_tracing(
        service_name=svc_otlp,
        set_global=False,
        propagators=("tracecontext", "baggage", "sw8"),
        batch=sdk.SpanBatchOptions(scheduled_delay_s=0.2),
    )
    tracer = handle.tracer("it")
    with (
        tracer.start_as_current_span(
            "GET /checkout", attributes={"run_id": run, "retries": 2}
        ) as root,
        tracer.start_as_current_span("select orders") as child,
    ):
        child.add_event("cache.miss", {"k": "v"})
    otlp_trace = format(root.get_span_context().trace_id, "032x")
    otlp_span = format(root.get_span_context().span_id, "016x")
    handle.force_flush(30)
    logs = client.log_client(
        service_name=svc_logs,
        service_instance=f"inst-{run}",
        options=sdk.BatchOptions(flush_interval_s=0.2),
    )
    logs.send(
        sdk.LogInput(
            f"order created native-log-{run}",
            severity=sdk.Severity.WARN,
            attributes={"run_id": run, "n": 1},
        )
    )
    logs.send(
        sdk.LogInput(
            f"payment failed native-log-{run}",
            severity=sdk.Severity.ERROR,
            attributes={"run_id": run},
            trace_id=otlp_trace,
            span_id=otlp_span,
        )
    )
    logs.flush(30)
    assert logs.stats().sent == 2
    yield World(
        client,
        run,
        svc_a,
        svc_b,
        svc_otlp,
        svc_logs,
        native_trace,
        seg_a,
        seg_b,
        otlp_trace,
        otlp_span,
    )
    client.close()


# --------------------------------------------------------------------------- core + GraphQL


def test_core_graphql_time_info_capabilities(world: World) -> None:
    c = world.client
    assert c.health().healthy
    assert c.ping() >= 0
    version = c.server_version()
    assert version.startswith("10.4.")
    assert c.graphql("{version}").data == {"version": version}
    assert c.query.graphql("query{version}").data == {"version": version}
    with pytest.raises(sdk.ValidationError):
        c.graphql("{nope}")
    info = c.query.time_info()
    assert isinstance(info.timezone, str)
    assert abs(info.current_timestamp - time.time() * 1000) < 300_000
    assert c.query.capabilities() == sdk.SkyWalkingCapabilities(True, False)


# --------------------------------------------------------------------------- trace query client


def test_traces_zipkin_store_round_trip(world: World) -> None:
    c = world.client
    trace = c.traces.wait_for_trace(world.otlp_trace, timeout_s=90, interval_s=2, min_spans=2)
    assert trace.trace_id == world.otlp_trace
    root = next(s for s in trace.spans if s.parent_span_id is None)
    child = next(s for s in trace.spans if s.parent_span_id is not None)
    assert root.name == "get /checkout"
    assert root.service_name == world.svc_otlp
    assert root.span_id == world.otlp_span
    assert root.attributes["run_id"] == world.run
    assert root.attributes["retries"] == "2"
    assert child.parent_span_id == root.span_id
    assert child.events[0].name == "cache.miss"
    assert child.events[0].attributes == {"k": "v"}
    again = c.traces.get_trace(world.otlp_trace)
    assert again is not None
    assert len(again.spans) == 2
    assert c.traces.get_trace("0000000000000000000000000000beef") is None


def test_traces_search(world: World) -> None:
    c = world.client
    hits = poll(
        "search_traces",
        lambda: c.traces.search_traces(
            sdk.TraceSearchQuery(service=world.svc_otlp, tags={"run_id": world.run}, start=ago(10))
        ),
        lambda h: any(x.trace_id == world.otlp_trace for x in h),
    )
    hit = next(x for x in hits if x.trace_id == world.otlp_trace)
    assert hit.root_name == "get /checkout"
    assert hit.span_count == 2


def test_traces_services_and_operations(world: World) -> None:
    c = world.client
    services = poll(
        "traces.list_services",
        c.traces.list_services,
        lambda s: world.svc_otlp in s and world.svc_a in s,
    )
    assert world.svc_b in services
    ops = poll(
        "traces.list_operations",
        lambda: c.traces.list_operations(world.svc_otlp),
        lambda o: "get /checkout" in o,
    )
    assert "select orders" in ops
    native_ops = poll(
        "traces.list_operations native",
        lambda: c.traces.list_operations(world.svc_a),
        lambda o: "/it/entry" in o,
    )
    assert native_ops


def test_traces_native_trace_and_dependencies(world: World) -> None:
    c = world.client
    trace = c.traces.wait_for_trace(world.native_trace, timeout_s=90, interval_s=2, min_spans=3)
    assert trace.trace_id == world.native_trace
    b = next(s for s in trace.spans if s.service_name == world.svc_b)
    assert b.parent_span_id == f"{world.seg_a}-1"
    assert b.status.code == "error"
    assert b.events[0].name == "error"
    deps = poll(
        "get_dependencies",
        lambda: c.traces.get_dependencies(start=ago(15)),
        lambda d: any(x.parent == world.svc_a and x.child == world.svc_b for x in d),
    )
    assert next(x for x in deps if x.parent == world.svc_a).call_count == 0


def test_traces_backends(env: dict[str, str], world: World) -> None:
    with sdk.SkyWalkingClient.from_env(env, trace_backend="native") as native:
        assert native.traces.backend == "native"
        hits = poll(
            "native search",
            lambda: native.traces.search_traces(
                sdk.TraceSearchQuery(service=world.svc_a, tags={"run_id": world.run}, start=ago(15))
            ),
            lambda h: any(x.trace_id == world.native_trace for x in h),
        )
        assert next(x for x in hits if x.trace_id == world.native_trace).span_count == 3
        assert native.traces.get_trace(world.otlp_trace) is None
    with sdk.SkyWalkingClient.from_env(env, trace_backend="zipkin") as zipkin:
        with pytest.raises(sdk.UnsupportedError):
            zipkin.traces.get_dependencies()
        with pytest.raises(sdk.ValidationError):
            zipkin.traces.get_trace(world.native_trace)


# --------------------------------------------------------------------------- query client


def test_query_services_endpoints_instances(world: World) -> None:
    q = world.client.query
    services = poll(
        "query.list_services",
        lambda: q.list_services("GENERAL"),
        lambda s: any(x.name == world.svc_a for x in s),
    )
    assert next(x for x in services if x.name == world.svc_a).id == sdk.service_id(world.svc_a)
    assert q.list_services("NO_SUCH_LAYER", sdk.TimeRange(start=ago(1))) == []
    found = q.find_service(world.svc_a)
    assert found is not None
    assert found.name == world.svc_a
    assert q.find_service(f"no-such-{world.run}") is None
    eps = poll(
        "list_endpoints",
        lambda: q.list_endpoints(world.svc_a),
        lambda e: any(x.name == "/it/entry" for x in e),
    )
    assert eps
    kw = q.list_endpoints(world.svc_b, "downstream", limit=5)
    assert [e.name for e in kw] == ["/it/downstream"]
    inst = poll(
        "list_instances",
        lambda: q.list_instances(world.svc_a, sdk.TimeRange(start=ago(15))),
        lambda i: len(i) > 0,
    )
    assert inst[0].name == f"inst-a-{world.run}"


def test_query_traces_get_trace_topology(world: World) -> None:
    q = world.client.query
    window = sdk.TimeRange(start=ago(15))
    res = poll(
        "query_traces",
        lambda: q.query_traces(service=world.svc_a, tags={"run_id": world.run}, range=window),
        lambda r: any(t.trace_id == world.native_trace for t in r.traces),
    )
    assert len(next(t for t in res.traces if t.trace_id == world.native_trace).spans) == 3
    errors = q.query_traces(service=world.svc_b, trace_state="ERROR", range=window)
    assert any(t.trace_id == world.native_trace for t in errors.traces)
    none = q.query_traces(service=world.svc_a, min_duration_ms=60_000, range=window)
    assert none.traces == ()
    by_id = q.query_traces(trace_id=world.native_trace)
    assert any(t.trace_id == world.native_trace for t in by_id.traces)
    native = q.get_trace(world.native_trace)
    assert native is not None
    b_span = next(s for s in native.spans if s.service_code == world.svc_b)
    assert b_span.refs[0].parent_segment_id == world.seg_a
    assert b_span.refs[0].type == "CROSS_PROCESS"
    assert q.get_trace(world.otlp_trace) is None
    topo = poll(
        "topology",
        lambda: q.topology(window),
        lambda t: any(
            c.source == sdk.service_id(world.svc_a) and c.target == sdk.service_id(world.svc_b)
            for c in t.calls
        ),
    )
    assert any(n.name == world.svc_b for n in topo.nodes)
    svc_topo = q.topology(window, service=world.svc_a)
    assert any(n.name == world.svc_b for n in svc_topo.nodes)


def test_query_logs_and_tag_keys(world: World) -> None:
    q = world.client.query
    by_service = poll(
        "query_logs",
        lambda: q.query_logs(service=world.svc_logs),
        lambda r: len(r.logs) >= 2,
    )
    assert all(lg.service_name == world.svc_logs for lg in by_service.logs)
    by_tag = q.query_logs(tags={"run_id": world.run}, order="ASC")
    assert len(by_tag.logs) == 2
    assert by_tag.logs[0].timestamp <= by_tag.logs[1].timestamp
    related = q.query_logs(trace_id=world.otlp_trace)
    assert len(related.logs) == 1
    assert "payment failed" in related.logs[0].content
    kw = q.query_logs(service=world.svc_logs, keywords=["payment"], excluding_keywords=["order"])
    assert [lg.content for lg in kw.logs] == [f"payment failed native-log-{world.run}"]
    keys = poll("list_log_tag_keys", q.list_log_tag_keys, lambda k: "run_id" in k)
    assert "level" in keys


# --------------------------------------------------------------------------- log query client


def test_log_query_client(env: dict[str, str], world: World) -> None:
    logs = world.client.logs
    found = logs.wait_for_log(f"payment native-log-{world.run}", timeout_s=60, interval_s=2)
    assert found.severity == sdk.Severity.ERROR
    assert found.trace_id == world.otlp_trace
    assert found.span_id == world.otlp_span
    assert found.attributes["run_id"] == world.run
    assert found.resource["service.name"] == world.svc_logs
    first = logs.search(f"run_id={world.run}", limit=1)
    assert len(first.records) == 1
    assert first.next_cursor
    second = logs.search(f"run_id={world.run}", limit=1, cursor=first.next_cursor)
    assert len(second.records) == 1
    assert second.records[0].body != first.records[0].body
    assert len(logs.search(f"trace_id={world.otlp_trace}").records) == 1
    by_service = logs.search(f"service={world.svc_logs} instance=inst-{world.run} created")
    assert [r.body for r in by_service.records] == [f"order created native-log-{world.run}"]
    assert world.svc_logs in logs.list_streams()
    assert "run_id" in logs.list_labels()

    seen: list[sdk.LogRecord] = []
    tail = logs.tail(f"tail-{world.run}", seen.append, interval_s=1)
    writer = sdk.create_log_client(
        sdk.SkyWalkingConfig.from_env(env),
        service_name=world.svc_logs,
        options=sdk.BatchOptions(flush_interval_s=0.1),
    )
    try:
        writer.send(sdk.LogInput(f"tail-{world.run} live"))
        writer.flush(30)
        poll("tail", lambda: len(seen), lambda n: n >= 1, 60)
        assert seen[0].body == f"tail-{world.run} live"
    finally:
        tail.stop()
        writer.close()


# --------------------------------------------------------------------------- tracing + sw8


def test_standalone_init_tracing_and_sw8(env: dict[str, str], world: World) -> None:
    handle = tracing_handle(env, f"{world.svc_otlp}-standalone", propagators=("sw8",))
    span = handle.tracer("it").start_span("standalone")
    trace_id = format(span.get_span_context().trace_id, "032x")
    span.end()
    handle.force_flush(30)
    handle.shutdown()
    got = world.client.traces.wait_for_trace(trace_id, timeout_s=90, interval_s=2)
    assert got.spans[0].name == "standalone"

    p = sdk.Sw8Propagator()
    native = world.client.query.get_trace(world.native_trace)
    assert native is not None
    exit_span = next(s for s in native.spans if s.type == "Exit")
    ctx = sdk.Sw8Context(
        True,
        exit_span.trace_id,
        exit_span.segment_id,
        exit_span.span_id,
        exit_span.service_code,
        exit_span.service_instance_name,
        "/it/entry",
        exit_span.peer,
    )
    carrier: dict[str, str] = {}
    inject = sdk.Sw8InjectContext(**{**ctx.__dict__, "correlation": {"run": world.run}})
    p.inject(carrier, sdk.with_sw8_context(inject, Context()))
    assert carrier["sw8"] == sdk.encode_sw8(ctx)
    assert carrier["sw8-correlation"] == sdk.encode_sw8_correlation({"run": world.run})
    assert sdk.decode_sw8_correlation(carrier["sw8-correlation"]) == {"run": world.run}
    assert sdk.get_sw8_context(p.extract(carrier)) == ctx
    assert sdk.sw8_attributes(ctx)["sw8.parent.segment_id"] == world.seg_a


def test_init_tracing_with_token(env: dict[str, str]) -> None:
    # OAP in the test stack has no SW_AUTHENTICATION: the token is simply ignored.
    sw = sdk.SkyWalkingConfig.from_env(env, authentication="unused-token")
    handle = sdk.init_tracing(service_name=unique("it-token"), skywalking=sw, set_global=False)
    with handle.tracer("it").start_as_current_span("with-token") as span:
        trace_id = format(span.get_span_context().trace_id, "032x")
    handle.force_flush(30)
    handle.shutdown()
    with sdk.SkyWalkingClient(sw) as c:
        assert c.traces.wait_for_trace(trace_id, timeout_s=90, interval_s=2).spans
    assert otel_trace.get_tracer_provider() is not handle.tracer_provider


# --------------------------------------------------------------------------- async client


def test_async_client_round_trips(env: dict[str, str], world: World) -> None:
    async def run() -> None:
        async with sdk.AsyncSkyWalkingClient.from_env(env) as c:
            trace = await c.traces.wait_for_trace(world.otlp_trace, timeout_s=60, interval_s=2)
            assert trace.trace_id == world.otlp_trace
            assert world.svc_otlp in await c.traces.list_services()
            assert "get /checkout" in await c.traces.list_operations(world.svc_otlp)
            hits = await c.traces.search_traces(
                sdk.TraceSearchQuery(service=world.svc_otlp, start=ago(30))
            )
            assert any(h.trace_id == world.otlp_trace for h in hits)
            assert await c.traces.get_dependencies(start=ago(15))
            assert (await c.query.find_service(world.svc_a)) is not None
            native = await c.query.get_trace(world.native_trace)
            assert native is not None
            assert len(native.spans) == 3
            assert (await c.query.query_traces(trace_id=world.native_trace)).traces
            assert len((await c.query.query_logs(service=world.svc_logs)).logs) >= 2
            assert await c.query.list_instances(world.svc_a, sdk.TimeRange(start=ago(15)))
            assert await c.query.list_endpoints(world.svc_a)
            assert await c.query.list_services()
            assert "run_id" in await c.query.list_log_tag_keys()
            assert (await c.query.topology(sdk.TimeRange(start=ago(15)))).calls
            assert (await c.query.time_info()).timezone
            assert (await c.query.capabilities()).query_traces_v2
            assert (await c.query.graphql("{version}")).data
            res = await c.logs.search(f"payment native-log-{world.run}")
            assert len(res.records) == 1
            assert world.svc_logs in await c.logs.list_streams()
            assert "run_id" in await c.logs.list_labels()
            writer = c.log_client(service_name=world.svc_logs)
            writer.send(sdk.LogInput(f"async-writer-{world.run}"))
            await writer.flush(30)
            found = await c.logs.wait_for_log(f"async-writer-{world.run}", timeout_s=60)
            assert found.body == f"async-writer-{world.run}"
            standalone = sdk.create_async_log_client(
                sdk.SkyWalkingConfig.from_env(env), service_name=world.svc_logs
            )
            standalone.send(sdk.LogInput(f"async-standalone-{world.run}"))
            await standalone.close()
            assert standalone.stats().sent == 1
            handle = c.init_tracing(service_name=f"{world.svc_otlp}-async", set_global=False)
            with handle.tracer("it").start_as_current_span("async-span") as span:
                tid = format(span.get_span_context().trace_id, "032x")
            handle.force_flush(30)
            assert (await c.traces.wait_for_trace(tid, timeout_s=90, interval_s=2)).spans

    asyncio.run(run())
