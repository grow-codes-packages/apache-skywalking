# Python examples

Install the package, point it at a running Apache SkyWalking and run an example:

```bash
pip install grow-apache-skywalking
export GROW_APACHE_SKYWALKING_URL=http://127.0.0.1:12800
export GROW_APACHE_SKYWALKING_TOKEN=...   # when the service needs one
python examples/python/basic.py
```

| File | Shows |
| - | - |
| `basic.py` | sync client: health, ping, server version, error handling |
| `async_basic.py` | async client, cancellation by task |
| `hooks_and_logging.py` | request hooks, the `grow_apache_skywalking` logger, retries and timeouts |
