# @grow-codes/apache-skywalking

TypeScript SDK for [Apache SkyWalking](https://grow-codes-packages.github.io/apache-skywalking/) by Grow Codes.
Node.js 22.12 or newer, ESM and CommonJS, full type definitions.

```sh
npm install @grow-codes/apache-skywalking
```

```ts
import { SkyWalkingClient } from "@grow-codes/apache-skywalking";

// Reads GROW_APACHE_SKYWALKING_URL, GROW_APACHE_SKYWALKING_TOKEN, timeouts, TLS and proxy settings.
const client = SkyWalkingClient.fromEnv();
try {
  console.log(await client.health()); // { healthy: true, status: "ok", details: {} }
  console.log(`ping: ${(await client.ping()).toFixed(1)} ms`);
} finally {
  await client.close();
}
```

Every error is a `SkyWalkingError` subclass with a canonical `code` (`NOT_FOUND`,
`AUTHENTICATION`, `RATE_LIMITED`, …), `httpStatus`, `requestId` and `retryable`. Credentials
never appear in messages, logs, hook data or `toString()` output.

Test helpers (in-memory fakes and the conformance runner) live in
`@grow-codes/apache-skywalking/testing`.

Documentation: https://grow-codes-packages.github.io/apache-skywalking/ · Licence: Apache-2.0.
Apache SkyWalking is a trademark of The Apache Software Foundation; this package is not affiliated with or endorsed by it.
