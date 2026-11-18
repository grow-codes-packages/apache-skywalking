# grow-apache-skywalking (Python)

Python SDK for [Apache SkyWalking](https://grow-codes-packages.github.io/apache-skywalking/) by Grow Codes.
Unofficial and community maintained; "Apache SkyWalking" is a trademark of The Apache Software Foundation.

```bash
pip install grow-apache-skywalking
```

```python
from grow_apache_skywalking import SkyWalkingClient, SkyWalkingConfig

# Reads GROW_APACHE_SKYWALKING_URL, GROW_APACHE_SKYWALKING_TOKEN, ... from the environment.
with SkyWalkingClient.from_env() as client:
    print(client.health())
    print(client.server_version())
```

The async client has the same methods:

```python
import asyncio
from grow_apache_skywalking import AsyncSkyWalkingClient

async def main() -> None:
    async with AsyncSkyWalkingClient.from_env() as client:
        print(await client.health())

asyncio.run(main())
```

- Python 3.10 or newer, typed (`py.typed`), `httpx` based, environment proxies ignored unless
  configured.
- Retries with full-jitter backoff and `Retry-After`, connect/request/total timeouts,
  cancellation, hooks and a silent-by-default `logging` logger named `grow_apache_skywalking`.
- Credentials are wrapped in `Secret` and never appear in `repr()`, logs or error messages.
- `grow_apache_skywalking.testing` ships in-memory fakes and the conformance runner.

Documentation: https://grow-codes-packages.github.io/apache-skywalking/ ·
Source: https://github.com/grow-codes-packages/apache-skywalking · Licence: Apache-2.0
