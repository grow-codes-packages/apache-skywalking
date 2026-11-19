"""Send OTLP spans and native logs to SkyWalking OAP, then read both back.

Needs an OAP with the OTLP receiver (gRPC 11800) and, to read OTLP spans, the Zipkin query API
(``SW_QUERY_ZIPKIN=default`` + ``SW_RECEIVER_ZIPKIN=default``, port 9412).
"""

from __future__ import annotations

import uuid

import grow_apache_skywalking as sdk


def main() -> None:
    run = uuid.uuid4().hex[:8]
    service = f"example-checkout-{run}"
    with sdk.SkyWalkingClient.from_env() as client:
        # Tracing: OTLP/gRPC to OAP; the handle is shut down by client.close().
        handle = client.init_tracing(service_name=service, set_global=False)
        tracer = handle.tracer("example")
        with tracer.start_as_current_span("GET /checkout", attributes={"run_id": run}) as root:
            tracer.start_span("select orders").end()
        trace_id = format(root.get_span_context().trace_id, "032x")
        handle.force_flush(30)

        # Logs: native /v3/logs, correlated with the active trace when there is one.
        logs = client.log_client(service_name=service)
        log = logs.logger("checkout")
        log.warn(f"payment retried {run}", {"run_id": run})
        logs.flush(30)

        # Read back (OAP needs a few seconds to index spans and logs).
        trace = client.traces.wait_for_trace(trace_id, timeout_s=90, interval_s=2, min_spans=2)
        for span in trace.spans:
            print(f"span {span.name!r} ({span.kind}) {span.duration_micros} µs")
        record = client.logs.wait_for_log(f"payment retried {run}", timeout_s=60, interval_s=2)
        print(f"log [{record.severity_text}] {record.body}")

        # The GraphQL query API: services that sent logs or native segments.
        for svc in client.query.list_services("GENERAL"):
            print(f"service {svc.name} ({svc.id})")


if __name__ == "__main__":
    main()
