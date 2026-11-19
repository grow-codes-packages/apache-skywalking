package codes.grow.packages.skywalking;

import codes.grow.packages.skywalking.core.HttpTransport;
import codes.grow.packages.skywalking.core.ValidationException;
import codes.grow.packages.skywalking.logging.LogQueryClient;
import codes.grow.packages.skywalking.logging.LogRecord;
import codes.grow.packages.skywalking.logging.LogSearchResult;
import codes.grow.packages.skywalking.logging.SearchOptions;
import codes.grow.packages.skywalking.logging.SortOrder;
import codes.grow.packages.skywalking.logging.TailHandle;
import codes.grow.packages.skywalking.logging.TailOptions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link LogQueryClient} for SkyWalking (GraphQL {@code queryLogs}); query syntax in {@link
 * ParsedLogQuery}. OAP on BanyanDB ignores content keywords, so free-text terms are filtered client
 * side: {@link #search} reads raw pages of {@value #SCAN_PAGE_SIZE} logs (newest first by default)
 * and keeps the matching ones until {@code limit} matches or {@code maxScanPages} pages; {@code
 * nextCursor} ({@code "page:offset:size"}) continues where it stopped. Without free text the page
 * size is {@code limit} and nothing is filtered except the exact time window. Default window: the
 * last hour. Obtain it from {@link SkyWalkingClient#logs()}.
 */
public final class SkyWalkingLogQueryClient implements LogQueryClient {
  /** Raw page size used while filtering free text client side. */
  public static final int SCAN_PAGE_SIZE = 1000;

  /** Default number of raw pages one search may scan. */
  public static final int DEFAULT_MAX_SCAN_PAGES = 10;

  private static final Pattern CURSOR = Pattern.compile("^(\\d+):(\\d+):(\\d+)$");

  private final SkyWalkingQueryClient query;
  private final SkyWalkingApi api;
  private final int maxScanPages;

  SkyWalkingLogQueryClient(SkyWalkingQueryClient query, SkyWalkingApi api, int maxScanPages) {
    if (maxScanPages < 1) {
      throw new ValidationException("logging: maxScanPages must be a positive integer");
    }
    this.query = query;
    this.api = api;
    this.maxScanPages = maxScanPages;
  }

  /**
   * Returns a client that scans at most {@code maxScanPages} raw pages per search.
   *
   * @param maxScanPages positive page count
   * @return a new client sharing this client's connections
   */
  public SkyWalkingLogQueryClient withMaxScanPages(int maxScanPages) {
    return new SkyWalkingLogQueryClient(query, api, maxScanPages);
  }

  /**
   * The number of raw pages one search may scan.
   *
   * @return the page count
   */
  public int maxScanPages() {
    return maxScanPages;
  }

  private record Cursor(int page, int offset, int size) {}

  private static Cursor decodeCursor(String cursor) {
    Matcher m = CURSOR.matcher(cursor);
    if (m.matches()) {
      try {
        Cursor c =
            new Cursor(
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)));
        if (c.page() >= 1 && c.size() >= 1 && c.size() <= 10_000) {
          return c;
        }
      } catch (NumberFormatException e) {
        // falls through to the error
      }
    }
    throw new ValidationException("logging.search: invalid cursor");
  }

  /**
   * Searches logs (see the class description for paging and {@link ParsedLogQuery} for the syntax).
   *
   * @param query query text
   * @param options window, limit (1..10000), order and cursor
   * @return matching records and the next cursor
   */
  @Override
  public LogSearchResult search(String query, SearchOptions options) {
    return HttpTransport.await(searchAsync(query, options), "logging.search");
  }

  @Override
  public CompletableFuture<LogSearchResult> searchAsync(String query, SearchOptions options) {
    return SkyWalkingApi.guard(
        () -> {
          String op = "logging.search";
          SearchOptions opts = options == null ? SearchOptions.defaults() : options;
          int limit = opts.limit();
          if (limit > 10_000) {
            throw new ValidationException(op + ": limit must be 1..10000");
          }
          ParsedLogQuery parsed = ParsedLogQuery.parse(query);
          Instant end = opts.end().orElse(api.now());
          Instant start = opts.start().orElse(end.minus(TimeRange.DEFAULT_WINDOW));
          if (start.isAfter(end)) {
            throw new ValidationException(op + ": invalid time window");
          }
          Cursor cursor =
              opts.cursor().isPresent()
                  ? decodeCursor(opts.cursor().get())
                  : new Cursor(1, 0, parsed.text().isEmpty() ? limit : SCAN_PAGE_SIZE);
          QueryLogsOptions.Builder base =
              QueryLogsOptions.builder()
                  .range(TimeRange.of(start, end).withStep(Step.MINUTE))
                  .order(opts.order() == SortOrder.ASC ? SortOrder.ASC : SortOrder.DESC)
                  .tags(parsed.tags())
                  .service(parsed.service().orElse(null))
                  .instance(parsed.instance().orElse(null))
                  .endpoint(parsed.endpoint().orElse(null))
                  .traceId(parsed.traceId().orElse(null));
          Scan scan =
              new Scan(
                  base, parsed, start.toEpochMilli(), end.toEpochMilli(), limit, cursor.size());
          return scan.page(cursor.page(), cursor.offset(), 0);
        });
  }

  /** State of one paged scan. */
  private final class Scan {
    final QueryLogsOptions.Builder base;
    final ParsedLogQuery parsed;
    final long startMs;
    final long endMs;
    final int limit;
    final int size;
    final List<LogRecord> records = new ArrayList<>();

    Scan(
        QueryLogsOptions.Builder base,
        ParsedLogQuery parsed,
        long startMs,
        long endMs,
        int limit,
        int size) {
      this.base = base;
      this.parsed = parsed;
      this.startMs = startMs;
      this.endMs = endMs;
      this.limit = limit;
      this.size = size;
    }

    CompletableFuture<LogSearchResult> page(int page, int offset, int scanned) {
      if (scanned >= maxScanPages) {
        return CompletableFuture.completedFuture(result(page + ":0:" + size));
      }
      return query
          .queryLogsAsync(base.paging(Paging.of(page, size)).build())
          .thenCompose(
              res -> {
                List<NativeLog> logs = res.logs();
                for (int i = offset; i < logs.size(); i++) {
                  NativeLog log = logs.get(i);
                  if (log.timestamp() < startMs
                      || log.timestamp() > endMs
                      || !parsed.matchesText(log)) {
                    continue;
                  }
                  records.add(SkyWalkingLogExporter.toRecord(log));
                  if (records.size() >= limit) {
                    boolean more = i + 1 < logs.size() || logs.size() >= size;
                    String next =
                        i + 1 < logs.size()
                            ? page + ":" + (i + 1) + ":" + size
                            : (page + 1) + ":0:" + size;
                    return CompletableFuture.completedFuture(result(more ? next : null));
                  }
                }
                if (logs.size() < size) {
                  return CompletableFuture.completedFuture(result(null));
                }
                return page(page + 1, 0, scanned + 1);
              });
    }

    LogSearchResult result(String next) {
      return new LogSearchResult(records, Optional.ofNullable(next), OptionalLong.empty());
    }
  }

  /**
   * Polling tail on top of {@link #search}: every {@code interval} it asks for records newer than
   * the last one seen (ascending) and calls {@code onRecord} once per new record. Errors go to
   * {@link TailOptions#onError()}.
   *
   * @param query query text
   * @param onRecord callback (called from the tail's daemon thread)
   * @param options start, interval and error callback
   * @return a handle; {@link TailHandle#stop()} ends the tail
   */
  @Override
  public TailHandle tail(String query, Consumer<LogRecord> onRecord, TailOptions options) {
    TailOptions opts = options == null ? TailOptions.defaults() : options;
    ScheduledExecutorService timer =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "grow-skywalking-log-tail");
              t.setDaemon(true);
              return t;
            });
    Tailer tailer = new Tailer(query, onRecord, opts.start().orElse(api.now()), opts);
    long interval = Math.max(1, opts.interval().toMillis());
    timer.scheduleWithFixedDelay(tailer::tick, interval, interval, TimeUnit.MILLISECONDS);
    return () -> {
      tailer.stopped = true;
      timer.shutdownNow();
    };
  }

  /** One polling tail. */
  private final class Tailer {
    final String query;
    final Consumer<LogRecord> onRecord;
    final TailOptions options;
    Instant since;
    Set<String> seenAtSince = new HashSet<>();
    volatile boolean stopped;

    Tailer(String query, Consumer<LogRecord> onRecord, Instant since, TailOptions options) {
      this.query = query;
      this.onRecord = onRecord;
      this.since = since;
      this.options = options;
    }

    void tick() {
      try {
        LogSearchResult res =
            search(
                query,
                SearchOptions.builder().start(since).order(SortOrder.ASC).limit(1000).build());
        for (LogRecord r : res.records()) {
          if (stopped) {
            return;
          }
          Instant t = r.timestamp();
          String key = r.body() + "|" + r.attributes();
          if (t.isBefore(since) || (t.equals(since) && seenAtSince.contains(key))) {
            continue;
          }
          if (t.isAfter(since)) {
            since = t;
            seenAtSince = new HashSet<>();
          }
          seenAtSince.add(key);
          onRecord.accept(r);
        }
      } catch (RuntimeException e) {
        if (!stopped) {
          try {
            options.onError().accept(e);
          } catch (RuntimeException ignored) {
            // the callback must not stop the tail
          }
        }
      }
    }
  }

  /**
   * Service names known to OAP (SkyWalking has no streams; the service is the equivalent), sorted.
   *
   * @return the names
   */
  @Override
  public List<String> listStreams() {
    return HttpTransport.await(listStreamsAsync(), "logging.listStreams");
  }

  @Override
  public CompletableFuture<List<String>> listStreamsAsync() {
    return query
        .listServicesAsync(null, null)
        .thenApply(
            services -> {
              TreeSet<String> names = new TreeSet<>();
              for (SkyWalkingService s : services) {
                if (s.normal()) {
                  names.add(s.name());
                }
              }
              return List.copyOf(names);
            });
  }

  /**
   * Log tag keys seen in the last hour ({@code queryLogTagAutocompleteKeys}).
   *
   * @return the keys
   */
  @Override
  public List<String> listLabels() {
    return query.listLogTagKeys();
  }

  @Override
  public CompletableFuture<List<String>> listLabelsAsync() {
    return query.listLogTagKeysAsync(null);
  }
}
