# Java examples

Install the package into your local Maven repository, point it at a running Apache SkyWalking and run an
example:

```bash
make -C ../../java pack            # builds and installs codes.grow.packages:apache-skywalking
export GROW_APACHE_SKYWALKING_URL=http://127.0.0.1:12800
export SW_AUTHENTICATION=...               # only when OAP sets SW_AUTHENTICATION (gRPC token)
mvn -q compile exec:java                                                  # BasicExample
mvn -q compile exec:java -Dexec.mainClass=codes.grow.packages.skywalking.examples.AsyncExample
```

Against the published package, set `-Dsdk.version=<version>` (or edit `pom.xml`) instead of
installing it locally.

| Class | Shows |
| - | - |
| `BasicExample` | tour: health, version, `initTracing` (OTLP/gRPC), native logs correlated with the span, reading the trace (Zipkin store), logs, services and topology back |
| `AsyncExample` | `…Async` methods, combining futures, cancellation |
| `HooksAndLoggingExample` | request hooks, a logger, retries and timeouts |

OAP must run with `SW_RECEIVER_ZIPKIN=default` and `SW_QUERY_ZIPKIN=default` (Zipkin query API on
port 9412) for spans exported over OTLP to be readable; the OTLP receiver is gRPC on port 11800.
