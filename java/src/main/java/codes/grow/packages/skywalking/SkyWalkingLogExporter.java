package codes.grow.packages.skywalking;

import codes.grow.packages.skywalking.core.HttpTransport;
import codes.grow.packages.skywalking.core.Json;
import codes.grow.packages.skywalking.core.TransportRequest;
import codes.grow.packages.skywalking.logging.ExportOptions;
import codes.grow.packages.skywalking.logging.ExportResult;
import codes.grow.packages.skywalking.logging.LogExporter;
import codes.grow.packages.skywalking.logging.LogRecord;
import codes.grow.packages.skywalking.logging.Severity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * {@link LogExporter} that posts batches to OAP's native log API ({@code POST /v3/logs}, JSON
 * array, port 12800). Native logs carry the trace context, register the service and are queryable a
 * few seconds later. The body is sent uncompressed (gzip is not verified on OAP's HTTP receiver).
 *
 * <p>Mapping of a SPEC {@link LogRecord} to native {@code LogData}: {@code
 * resource["service.name"]} → {@code service}, {@code service.instance.id} → {@code
 * serviceInstance}, body → {@code body.text.text}, {@code severityText} → tag {@code level},
 * attributes (stringified, nested values as JSON) → tags, {@code traceId} → {@code
 * traceContext.traceId} (enables {@code relatedTrace} queries), {@code spanId} → tag {@code
 * span_id}.
 */
public final class SkyWalkingLogExporter implements LogExporter {
  /** Service name used when a record has no {@code service.name} resource attribute. */
  public static final String DEFAULT_SERVICE_NAME = "unknown_service";

  private static final Pattern HEX32 = Pattern.compile("^[0-9a-f]{32}$");
  private static final Pattern HEX16 = Pattern.compile("^[0-9a-f]{16}$");

  private final HttpTransport transport;
  private final String serviceName;
  private final boolean ownsTransport;
  private final AtomicBoolean shutdown = new AtomicBoolean();

  /**
   * Creates an exporter on a transport whose base URL is the OAP REST URL.
   *
   * @param transport transport (for example {@link SkyWalkingClient#transport()})
   * @param serviceName service name for records without {@code service.name} (null = {@value
   *     #DEFAULT_SERVICE_NAME})
   * @param ownsTransport close the transport on {@link #shutdown()}
   */
  public SkyWalkingLogExporter(HttpTransport transport, String serviceName, boolean ownsTransport) {
    this.transport = transport;
    this.serviceName = serviceName == null ? DEFAULT_SERVICE_NAME : serviceName;
    this.ownsTransport = ownsTransport;
  }

  /**
   * Converts an attribute value to SkyWalking's string tag form (JSON for lists and maps).
   *
   * @param value attribute value
   * @return the text
   */
  public static String tagText(Object value) {
    if (value instanceof String) {
      return (String) value;
    }
    if (value instanceof Double) {
      double d = (Double) value;
      if (Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) < 1e15) {
        return Long.toString((long) d);
      }
      return value.toString();
    }
    if (value instanceof Number || value instanceof Boolean) {
      return value.toString();
    }
    return Json.stringify(value);
  }

  /**
   * Maps a record to the native {@code LogData} JSON object (see the class description).
   *
   * @param record record
   * @param defaultService service name for records without {@code service.name}
   * @return the {@code LogData} map
   */
  public static Map<String, Object> toLogData(LogRecord record, String defaultService) {
    List<Map<String, String>> tags = new ArrayList<>();
    tags.add(Map.of("key", "level", "value", record.severityText()));
    record
        .attributes()
        .forEach(
            (k, v) -> {
              if (!k.equals("level")) {
                tags.add(Map.of("key", k, "value", tagText(v)));
              }
            });
    record.spanId().ifPresent(s -> tags.add(Map.of("key", "span_id", "value", s)));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("timestamp", record.timestamp().toEpochMilli());
    Object service = record.resource().get("service.name");
    out.put("service", service == null ? defaultService : tagText(service));
    Object instance = record.resource().get("service.instance.id");
    if (instance != null) {
      out.put("serviceInstance", tagText(instance));
    }
    out.put("body", Map.of("text", Map.of("text", record.body())));
    record.traceId().ifPresent(t -> out.put("traceContext", Map.of("traceId", t)));
    out.put("tags", Map.of("data", tags));
    out.put("layer", "GENERAL");
    return out;
  }

  /**
   * Maps a stored log back to a SPEC record: tag {@code level} → severity and severity text
   * (default INFO), a 16-hex {@code span_id} tag → {@code spanId}, a 32-hex {@code traceId} →
   * {@code traceId} (native trace ids go to attribute {@code sw.trace_id}), other tags → attributes
   * (strings), service/instance → resource {@code service.name}/{@code service.instance.id},
   * endpoint → resource {@code sw.endpoint}.
   *
   * @param log stored log
   * @return the record
   */
  public static LogRecord toRecord(NativeLog log) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    String level = null;
    String spanId = null;
    for (KeyValue kv : log.tags()) {
      if (kv.key().equals("level")) {
        level = kv.value();
      } else if (kv.key().equals("span_id") && HEX16.matcher(kv.value()).matches()) {
        spanId = kv.value();
      } else {
        attributes.put(kv.key(), kv.value());
      }
    }
    Map<String, Object> resource = new LinkedHashMap<>();
    resource.put("service.name", log.serviceName());
    if (!log.serviceInstanceName().isEmpty()) {
      resource.put("service.instance.id", log.serviceInstanceName());
    }
    if (!log.endpointName().isEmpty()) {
      resource.put("sw.endpoint", log.endpointName());
    }
    String traceId = log.traceId().toLowerCase(Locale.ROOT);
    String hexTrace = null;
    if (HEX32.matcher(traceId).matches()) {
      hexTrace = traceId;
    } else if (!log.traceId().isEmpty()) {
      attributes.put("sw.trace_id", log.traceId());
    }
    Severity severity = level == null ? Severity.INFO : Severity.parse(level).orElse(Severity.INFO);
    return LogRecord.builder()
        .timestamp(java.time.Instant.ofEpochMilli(log.timestamp()))
        .severity(severity)
        .severityText(level == null ? "INFO" : level)
        .body(log.content())
        .attributes(attributes)
        .resource(resource)
        .traceId(hexTrace)
        .spanId(spanId)
        .build();
  }

  /**
   * Posts one batch; transport errors fail the future (the batch processor retries retryable ones).
   *
   * @param records records
   * @param options export options (gzip is ignored: OAP's HTTP receiver gets plain JSON)
   * @return a future with the result
   */
  @Override
  public CompletableFuture<ExportResult> export(List<LogRecord> records, ExportOptions options) {
    if (records.isEmpty()) {
      return CompletableFuture.completedFuture(ExportResult.success(0));
    }
    List<Map<String, Object>> body = new ArrayList<>(records.size());
    for (LogRecord r : records) {
      body.add(toLogData(r, serviceName));
    }
    TransportRequest req =
        TransportRequest.post("/v3/logs")
            .operation("logging.export")
            .json(body)
            .timeout(options.timeout())
            .build();
    int n = records.size();
    return transport.sendAsync(req).thenApply(r -> ExportResult.success(n));
  }

  /**
   * Idempotent; closes the transport when the exporter owns it.
   *
   * @return a completed future
   */
  @Override
  public CompletableFuture<Void> shutdown() {
    if (shutdown.compareAndSet(false, true) && ownsTransport) {
      transport.close();
    }
    return CompletableFuture.completedFuture(null);
  }
}
