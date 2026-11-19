package codes.grow.packages.skywalking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.skywalking.core.HttpTransport;
import codes.grow.packages.skywalking.core.MockServer;
import codes.grow.packages.skywalking.core.RetryPolicy;
import codes.grow.packages.skywalking.core.UnavailableException;
import codes.grow.packages.skywalking.core.ValidationException;
import codes.grow.packages.skywalking.logging.ExportOptions;
import codes.grow.packages.skywalking.logging.LogRecord;
import codes.grow.packages.skywalking.logging.LogSearchResult;
import codes.grow.packages.skywalking.logging.SearchOptions;
import codes.grow.packages.skywalking.logging.Severity;
import codes.grow.packages.skywalking.logging.SortOrder;
import codes.grow.packages.skywalking.logging.TailHandle;
import codes.grow.packages.skywalking.logging.TailOptions;
import codes.grow.packages.skywalking.logging.WaitForLogOptions;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogsTest {
  static final Instant NOW = Instant.ofEpochMilli(1795036300000L);

  private MockServer server;
  private SkyWalkingClient client;

  @BeforeEach
  void start() throws Exception {
    server = MockServer.start();
    client =
        SkyWalkingClient.create(
            SkyWalkingConfig.builder()
                .url(server.url())
                .retry(RetryPolicy.builder().initialBackoff(Duration.ofMillis(1)).build())
                .build(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void stop() {
    client.close();
    server.close();
  }

  private JsonNode cond(int i) {
    return Fixtures.request(server.requests().get(i)).get("variables").get("c");
  }

  private static String log(String content, long ts, String... tags) {
    StringBuilder sb =
        new StringBuilder("{\"serviceName\":\"svc\",\"content\":\"")
            .append(content)
            .append("\",\"timestamp\":")
            .append(ts)
            .append(",\"tags\":[");
    for (int i = 0; i + 1 < tags.length; i += 2) {
      sb.append(i == 0 ? "" : ",")
          .append("{\"key\":\"")
          .append(tags[i])
          .append("\",\"value\":\"")
          .append(tags[i + 1])
          .append("\"}");
    }
    return sb.append("]}").toString();
  }

  private static String page(String... logs) {
    return "{\"data\":{\"queryLogs\":{\"logs\":[" + String.join(",", logs) + "]}}}";
  }

  @Test
  void exporterPostsNativeLogData() {
    server.fallback(ex -> MockServer.send(ex, 200, ""));
    SkyWalkingLogExporter e = new SkyWalkingLogExporter(client.transport(), null, false);
    LogRecord r =
        LogRecord.builder()
            .timestamp(Instant.ofEpochMilli(1234))
            .severity(Severity.WARN)
            .body("order created")
            .attributes(Map.of("level", "ignored", "n", 1, "ok", true, "f", 1.5, "w", 2.0))
            .attribute("nested", Map.of("a", List.of(1, 2)))
            .resource(Map.of("service.name", "svc", "service.instance.id", "inst"))
            .traceId("4bf92f3577b34da6a3ce929d0e0e4736")
            .spanId("00f067aa0ba902b7")
            .build();
    assertThat(
            e.export(List.of(r), new ExportOptions(false, Duration.ofSeconds(5))).join().accepted())
        .isEqualTo(1);
    assertThat(
            e.export(List.of(), new ExportOptions(false, Duration.ofSeconds(5))).join().accepted())
        .isZero();
    var req = server.requests().get(0);
    assertThat(req.uri()).isEqualTo("/v3/logs");
    assertThat(req.method()).isEqualTo("POST");
    JsonNode d = Fixtures.request(req).get(0);
    assertThat(d.get("timestamp").asLong()).isEqualTo(1234);
    assertThat(d.get("service").asText()).isEqualTo("svc");
    assertThat(d.get("serviceInstance").asText()).isEqualTo("inst");
    assertThat(d.get("layer").asText()).isEqualTo("GENERAL");
    assertThat(d.get("traceContext").get("traceId").asText())
        .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    Map<String, String> tags = new java.util.HashMap<>();
    d.get("tags")
        .get("data")
        .forEach(t -> tags.put(t.get("key").asText(), t.get("value").asText()));
    assertThat(tags)
        .containsEntry("level", "WARN")
        .containsEntry("n", "1")
        .containsEntry("ok", "true")
        .containsEntry("f", "1.5")
        .containsEntry("w", "2")
        .containsEntry("nested", "{\"a\":[1,2]}")
        .containsEntry("span_id", "00f067aa0ba902b7");
    e.shutdown().join();
    assertThat(client.transport().isClosed()).isFalse();
    HttpTransport own = new HttpTransport(client.config().toTransportConfig());
    SkyWalkingLogExporter owner = new SkyWalkingLogExporter(own, "x", true);
    owner.shutdown().join();
    owner.shutdown().join();
    assertThat(own.isClosed()).isTrue();
    LogRecord bare = LogRecord.builder().body("b").build();
    assertThat(SkyWalkingLogExporter.toLogData(bare, "dflt"))
        .containsEntry("service", "dflt")
        .doesNotContainKeys("serviceInstance", "traceContext");
    assertThat(SkyWalkingLogExporter.tagText(Double.NaN)).isEqualTo("NaN");
    assertThat(SkyWalkingLogExporter.tagText(1e20)).isEqualTo("1.0E20");
  }

  @Test
  void exporterFailuresAreExceptions() {
    Fixtures.enqueue(server, "reportLogs.bad-json");
    SkyWalkingLogExporter e = new SkyWalkingLogExporter(client.transport(), "s", false);
    assertThatThrownBy(
            () ->
                e.export(
                        List.of(LogRecord.builder().body("x").build()),
                        new ExportOptions(false, Duration.ofSeconds(5)))
                    .join())
        .hasCauseInstanceOf(UnavailableException.class);
    assertThat(server.requests()).hasSize(1);
  }

  @Test
  void storedLogsMapBackToRecords() {
    NativeLog otlp =
        new NativeLog(
            "svc",
            "id",
            "inst",
            "/ep",
            "4BF92F3577B34DA6A3CE929D0E0E4736",
            5,
            "TEXT",
            "c",
            List.of(
                new KeyValue("level", "warning"),
                new KeyValue("span_id", "00f067aa0ba902b7"),
                new KeyValue("run", "1")));
    LogRecord r = SkyWalkingLogExporter.toRecord(otlp);
    assertThat(r.severity()).isEqualTo(Severity.WARN);
    assertThat(r.severityText()).isEqualTo("warning");
    assertThat(r.traceId()).contains("4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(r.spanId()).contains("00f067aa0ba902b7");
    assertThat(r.attributes()).containsExactly(Map.entry("run", "1"));
    assertThat(r.resource())
        .containsEntry("service.name", "svc")
        .containsEntry("service.instance.id", "inst")
        .containsEntry("sw.endpoint", "/ep");
    assertThat(r.timestamp()).isEqualTo(Instant.ofEpochMilli(5));
    NativeLog nat =
        new NativeLog(
            "svc",
            "",
            "",
            "",
            "abc.1.2",
            0,
            "",
            "c",
            List.of(new KeyValue("level", "nonsense"), new KeyValue("span_id", "7")));
    LogRecord n = SkyWalkingLogExporter.toRecord(nat);
    assertThat(n.severity()).isEqualTo(Severity.INFO);
    assertThat(n.traceId()).isEmpty();
    assertThat(n.attributes())
        .containsEntry("sw.trace_id", "abc.1.2")
        .containsEntry("span_id", "7");
    assertThat(n.resource()).containsOnlyKeys("service.name");
    LogRecord none =
        SkyWalkingLogExporter.toRecord(new NativeLog("s", "", "", "", "", 0, "", "", null));
    assertThat(none.severityText()).isEqualTo("INFO");
    assertThat(none.attributes()).isEmpty();
  }

  @Test
  void parseLogQuery() {
    ParsedLogQuery q =
        ParsedLogQuery.parse(
            "service=a instance=\"i 1\" endpoint=/x trace_id=t level=ERROR \"two words\" free");
    assertThat(q.service()).contains("a");
    assertThat(q.instance()).contains("i 1");
    assertThat(q.endpoint()).contains("/x");
    assertThat(q.traceId()).contains("t");
    assertThat(q.tags()).containsExactly(Map.entry("level", "ERROR"));
    assertThat(q.text()).containsExactly("two words", "free");
    assertThat(ParsedLogQuery.parse("traceId=x").traceId()).contains("x");
    assertThat(ParsedLogQuery.parse(null).text()).isEmpty();
    assertThat(ParsedLogQuery.parse("\"\"").text()).isEmpty();
    NativeLog l =
        new NativeLog(
            "s", "", "", "", "", 0, "", "Payment FAILED", List.of(new KeyValue("k", "Tagged")));
    assertThat(ParsedLogQuery.parse("payment tagged").matchesText(l)).isTrue();
    assertThat(ParsedLogQuery.parse("payment other").matchesText(l)).isFalse();
    assertThat(ParsedLogQuery.parse("").matchesText(l)).isTrue();
    ParsedLogQuery empty = new ParsedLogQuery(null, null, null, null, null, null);
    assertThat(empty.tags()).isEmpty();
    assertThat(empty.text()).isEmpty();
  }

  @Test
  void searchWithoutTextUsesLimitAsPageSize() {
    Fixtures.enqueue(server, "ping.ok");
    server.enqueue(
        200,
        page(
            log("a", NOW.toEpochMilli() - 1000, "level", "ERROR"),
            log("b", NOW.toEpochMilli() - 2000)));
    server.enqueue(200, page(log("c", NOW.toEpochMilli() - 3000)));
    LogSearchResult r =
        client
            .logs()
            .search(
                "service=svc instance=i endpoint=/e trace_id=t run_id=1",
                SearchOptions.builder().limit(2).build());
    assertThat(r.records()).extracting(LogRecord::body).containsExactly("a", "b");
    assertThat(r.records().get(0).severity()).isEqualTo(Severity.ERROR);
    assertThat(r.nextCursor()).contains("2:0:2");
    JsonNode c = cond(1);
    assertThat(c.get("paging").get("pageSize").asInt()).isEqualTo(2);
    assertThat(c.get("serviceId").asText()).isEqualTo(SkyWalkingIds.serviceId("svc"));
    assertThat(c.get("serviceInstanceId")).isNotNull();
    assertThat(c.get("endpointId")).isNotNull();
    assertThat(c.get("relatedTrace").get("traceId").asText()).isEqualTo("t");
    assertThat(c.get("tags").get(0).get("value").asText()).isEqualTo("1");
    assertThat(c.get("queryDuration").get("step").asText()).isEqualTo("MINUTE");
    assertThat(c.get("queryOrder").asText()).isEqualTo("DES");
    LogSearchResult next =
        client
            .logs()
            .search(
                "",
                SearchOptions.builder()
                    .limit(2)
                    .cursor(r.nextCursor().get())
                    .order(SortOrder.ASC)
                    .build());
    assertThat(next.records()).extracting(LogRecord::body).containsExactly("c");
    assertThat(next.nextCursor()).isEmpty();
    assertThat(cond(2).get("paging").get("pageNum").asInt()).isEqualTo(2);
    assertThat(cond(2).get("queryOrder").asText()).isEqualTo("ASC");
  }

  @Test
  void searchFiltersFreeTextAcrossPages() {
    long t = NOW.toEpochMilli() - 1000;
    List<String> first = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      first.add(log(i == 10 ? "match one" : "noise", t, "k", i == 20 ? "MATCH tag" : "x"));
    }
    Fixtures.enqueue(server, "ping.ok");
    server.enqueue(200, page(first.toArray(new String[0])));
    server.enqueue(
        200,
        page(
            log("match old", NOW.toEpochMilli() - 7_200_000L),
            log("match future", NOW.toEpochMilli() + 60_000),
            log("match three", t),
            log("match four", t)));
    LogSearchResult r = client.logs().search("match", SearchOptions.builder().limit(3).build());
    assertThat(r.records())
        .extracting(LogRecord::body)
        .containsExactly("match one", "noise", "match three");
    assertThat(r.nextCursor()).contains("2:3:1000");
    assertThat(cond(1).get("paging").get("pageSize").asInt()).isEqualTo(1000);
    server.enqueue(200, page(log("match old", 1), log("match three", t), log("match four", t)));
    LogSearchResult more =
        client.logs().search("match", SearchOptions.builder().limit(3).cursor("2:2:1000").build());
    assertThat(more.records()).extracting(LogRecord::body).containsExactly("match four");
    assertThat(more.nextCursor()).isEmpty();
    // exactly limit at the end of a full page: the next page may exist
    server.enqueue(200, page(log("x", t), log("match last", t)));
    LogSearchResult edge =
        client.logs().search("match", SearchOptions.builder().limit(1).cursor("1:0:2").build());
    assertThat(edge.nextCursor()).contains("2:0:2");
    server.enqueue(200, page(log("match a", t), log("match b", t)));
    LogSearchResult mid =
        client.logs().search("match", SearchOptions.builder().limit(1).cursor("1:0:5").build());
    assertThat(mid.nextCursor()).contains("1:1:5");
  }

  @Test
  void searchStopsAfterMaxScanPages() {
    Fixtures.enqueue(server, "ping.ok");
    server.enqueue(200, page(log("x", NOW.toEpochMilli()), log("y", NOW.toEpochMilli())));
    server.enqueue(200, page(log("x", NOW.toEpochMilli()), log("y", NOW.toEpochMilli())));
    SkyWalkingLogQueryClient logs = client.logs().withMaxScanPages(2);
    assertThat(logs.maxScanPages()).isEqualTo(2);
    LogSearchResult r =
        logs.searchAsync("zzz", SearchOptions.builder().cursor("1:0:2").build()).join();
    assertThat(r.records()).isEmpty();
    assertThat(r.nextCursor()).contains("3:0:2");
    assertThatThrownBy(() -> client.logs().withMaxScanPages(0))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void searchValidation() {
    SkyWalkingLogQueryClient logs = client.logs();
    for (String bad : new String[] {"x", "0:0:1", "1:0:0", "1:0:10001", "1:0:99999999999"}) {
      assertThatThrownBy(() -> logs.search("q", SearchOptions.builder().cursor(bad).build()))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("cursor");
    }
    assertThatThrownBy(() -> logs.search("q", SearchOptions.builder().limit(10_001).build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () -> logs.search("q", SearchOptions.builder().start(NOW.plusSeconds(10)).build()))
        .isInstanceOf(ValidationException.class);
    assertThat(server.requests()).isEmpty();
    Fixtures.enqueue(server, "ping.ok");
    server.enqueue(200, page());
    assertThat(logs.searchAsync("q", null).join().records()).isEmpty();
    server.enqueue(200, page());
    assertThat(logs.search("q").records()).isEmpty();
  }

  @Test
  void waitForLogAndStreamsAndLabels() {
    Fixtures.enqueue(server, "ping.ok");
    Fixtures.enqueue(server, "queryLogs.by-service");
    LogRecord found =
        client
            .logs()
            .waitForLog(
                "payment",
                new WaitForLogOptions(
                    Duration.ofSeconds(5),
                    Duration.ofMillis(10),
                    null,
                    SearchOptions.builder().start(Instant.ofEpochMilli(1795036000000L)).build()));
    assertThat(found.body()).contains("payment accepted");
    assertThat(found.attributes()).containsEntry("trace_id", "055ffba8ecc69a64950538fda698a308");
    assertThat(found.spanId()).contains("999a2365604ef4c8");
    Fixtures.enqueue(server, "listServices.all");
    assertThat(client.logs().listStreams()).isSorted().contains("sw-native-a-1fddf66a");
    server.enqueue(200, "{\"data\":{\"listServices\":[{\"name\":\"v\",\"normal\":false}]}}");
    assertThat(client.logs().listStreamsAsync().join()).isEmpty();
    Fixtures.enqueue(server, "queryLogTagAutocompleteKeys.ok");
    assertThat(client.logs().listLabels()).contains("run_id");
    Fixtures.enqueue(server, "queryLogTagAutocompleteKeys.ok");
    assertThat(client.logs().listLabelsAsync().join()).contains("level");
  }

  @Test
  void tailPollsForNewRecords() throws Exception {
    long base = NOW.toEpochMilli();
    List<String> pages =
        Collections.synchronizedList(
            new ArrayList<>(
                List.of(
                    Fixtures.body("ping.ok"),
                    page(
                        log("old", base - 200), log("first", base - 100), log("second", base - 50)),
                    page(
                        log("first", base - 100),
                        log("second", base - 50),
                        log("third", base - 50)),
                    "{\"errors\":[{\"message\":\"boom\"}]}")));
    server.fallback(
        ex -> {
          String body = pages.isEmpty() ? page() : pages.remove(0);
          MockServer.send(ex, 200, body);
        });
    List<LogRecord> seen = new CopyOnWriteArrayList<>();
    List<Throwable> errors = new CopyOnWriteArrayList<>();
    TailHandle h =
        client
            .logs()
            .tail(
                "",
                seen::add,
                new TailOptions(
                    Optional.of(NOW.minusMillis(100)), Duration.ofMillis(20), errors::add));
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while ((seen.size() < 3 || errors.isEmpty()) && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    h.stop();
    assertThat(seen).extracting(LogRecord::body).containsExactly("first", "second", "third");
    assertThat(errors).isNotEmpty();
    h.close();
    TailHandle d = client.logs().tail("x", r -> {}, null);
    d.stop();
  }
}
