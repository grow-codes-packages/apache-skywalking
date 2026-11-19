# Python examples

Install the package, point it at a running Apache SkyWalking and run an example:

```bash
pip install grow-apache-skywalking
export GROW_APACHE_SKYWALKING_URL=http://127.0.0.1:12800
export GROW_APACHE_SKYWALKING_OTLP_ENDPOINT=http://127.0.0.1:11800   # default: <url host>:11800
export GROW_APACHE_SKYWALKING_AUTHENTICATION=...   # only when OAP sets SW_AUTHENTICATION
python examples/python/basic.py
```

| File | Shows |
| - | - |
| `basic.py` | sync client: health, ping, server version, error handling |
| `async_basic.py` | async client, cancellation by task |
| `tracing_and_logs.py` | `init_tracing` (OTLP/gRPC), native log client, reading spans and logs back, the GraphQL query API |
| `hooks_and_logging.py` | request hooks, the `grow_apache_skywalking` logger, retries and timeouts |
