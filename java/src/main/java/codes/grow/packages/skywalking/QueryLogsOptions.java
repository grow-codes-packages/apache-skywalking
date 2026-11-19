package codes.grow.packages.skywalking;

import codes.grow.packages.skywalking.logging.SortOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Filter of {@link SkyWalkingQueryClient#queryLogs}. Either a window (default the last hour) or a
 * trace id is sent.
 */
public final class QueryLogsOptions {
  private final String service;
  private final String instance;
  private final String endpoint;
  private final String traceId;
  private final String segmentId;
  private final Integer spanId;
  private final List<String> keywords;
  private final List<String> excludingKeywords;
  private final Map<String, String> tags;
  private final TimeRange range;
  private final Paging paging;
  private final SortOrder order;

  private QueryLogsOptions(Builder b) {
    this.service = b.service;
    this.instance = b.instance;
    this.endpoint = b.endpoint;
    this.traceId = b.traceId;
    this.segmentId = b.segmentId;
    this.spanId = b.spanId;
    this.keywords = List.copyOf(b.keywords);
    this.excludingKeywords = List.copyOf(b.excludingKeywords);
    this.tags = Collections.unmodifiableMap(new LinkedHashMap<>(b.tags));
    this.range = b.range;
    this.paging = b.paging;
    this.order = b.order;
  }

  /**
   * Returns a builder.
   *
   * @return builder with every field unset
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * No filter: the last hour, newest first, first page.
   *
   * @return options
   */
  public static QueryLogsOptions defaults() {
    return builder().build();
  }

  /**
   * Returns a builder holding these values.
   *
   * @return builder copy
   */
  public Builder toBuilder() {
    Builder b =
        builder()
            .service(service)
            .instance(instance)
            .endpoint(endpoint)
            .traceId(traceId)
            .segmentId(segmentId)
            .spanId(spanId)
            .keywords(keywords)
            .excludingKeywords(excludingKeywords)
            .tags(tags)
            .range(range)
            .paging(paging)
            .order(order);
    return b;
  }

  /**
   * Service name.
   *
   * @return the service, or empty
   */
  public Optional<String> service() {
    return Optional.ofNullable(service);
  }

  /**
   * Instance name (needs a service).
   *
   * @return the instance, or empty
   */
  public Optional<String> instance() {
    return Optional.ofNullable(instance);
  }

  /**
   * Endpoint name (needs a service).
   *
   * @return the endpoint, or empty
   */
  public Optional<String> endpoint() {
    return Optional.ofNullable(endpoint);
  }

  /**
   * Related trace id (native or hex). OTLP logs keep their trace id only in the {@code trace_id}
   * tag.
   *
   * @return the id, or empty
   */
  public Optional<String> traceId() {
    return Optional.ofNullable(traceId);
  }

  /**
   * Segment of the related trace.
   *
   * @return the segment id, or empty
   */
  public Optional<String> segmentId() {
    return Optional.ofNullable(segmentId);
  }

  /**
   * Span of the related segment.
   *
   * @return the span id, or empty
   */
  public OptionalInt spanId() {
    return spanId == null ? OptionalInt.empty() : OptionalInt.of(spanId);
  }

  /**
   * Words the content must contain: sent to the server when it supports keyword search
   * (Elasticsearch storage); on BanyanDB the page is filtered client side (case-insensitive
   * substring).
   *
   * @return the words
   */
  public List<String> keywords() {
    return keywords;
  }

  /**
   * Words the content must not contain (same rules as {@link #keywords()}).
   *
   * @return the words
   */
  public List<String> excludingKeywords() {
    return excludingKeywords;
  }

  /**
   * Tag matches; only keys in {@code SW_SEARCHABLE_LOGS_TAG_KEYS} can match.
   *
   * @return immutable map
   */
  public Map<String, String> tags() {
    return tags;
  }

  /**
   * Window (default the last hour; not sent when only a trace id is given).
   *
   * @return the range, or empty
   */
  public Optional<TimeRange> range() {
    return Optional.ofNullable(range);
  }

  /**
   * Page selection (default {@link Paging#DEFAULT}; OAP accepts up to 1000 per page).
   *
   * @return the paging
   */
  public Paging paging() {
    return paging;
  }

  /**
   * Order (default {@link SortOrder#DESC}, newest first).
   *
   * @return the order
   */
  public SortOrder order() {
    return order;
  }

  /** Builder of {@link QueryLogsOptions}. */
  public static final class Builder {
    private String service;
    private String instance;
    private String endpoint;
    private String traceId;
    private String segmentId;
    private Integer spanId;
    private final List<String> keywords = new ArrayList<>();
    private final List<String> excludingKeywords = new ArrayList<>();
    private final Map<String, String> tags = new LinkedHashMap<>();
    private TimeRange range;
    private Paging paging = Paging.DEFAULT;
    private SortOrder order = SortOrder.DESC;

    private Builder() {}

    /**
     * Sets the service name.
     *
     * @param service name
     * @return this builder
     */
    public Builder service(String service) {
      this.service = service;
      return this;
    }

    /**
     * Sets the instance name (needs a service).
     *
     * @param instance name
     * @return this builder
     */
    public Builder instance(String instance) {
      this.instance = instance;
      return this;
    }

    /**
     * Sets the endpoint name (needs a service).
     *
     * @param endpoint name
     * @return this builder
     */
    public Builder endpoint(String endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    /**
     * Sets the related trace id.
     *
     * @param traceId id
     * @return this builder
     */
    public Builder traceId(String traceId) {
      this.traceId = traceId;
      return this;
    }

    /**
     * Sets the related segment (needs a trace id).
     *
     * @param segmentId id
     * @return this builder
     */
    public Builder segmentId(String segmentId) {
      this.segmentId = segmentId;
      return this;
    }

    /**
     * Sets the related span (needs a trace id).
     *
     * @param spanId id or null
     * @return this builder
     */
    public Builder spanId(Integer spanId) {
      this.spanId = spanId;
      return this;
    }

    /**
     * Adds words the content must contain.
     *
     * @param words words
     * @return this builder
     */
    public Builder keywords(List<String> words) {
      this.keywords.addAll(words);
      return this;
    }

    /**
     * Adds words the content must not contain.
     *
     * @param words words
     * @return this builder
     */
    public Builder excludingKeywords(List<String> words) {
      this.excludingKeywords.addAll(words);
      return this;
    }

    /**
     * Adds a tag match.
     *
     * @param key key
     * @param value value
     * @return this builder
     */
    public Builder tag(String key, String value) {
      this.tags.put(key, value);
      return this;
    }

    /**
     * Adds tag matches.
     *
     * @param tags tags
     * @return this builder
     */
    public Builder tags(Map<String, String> tags) {
      this.tags.putAll(tags);
      return this;
    }

    /**
     * Sets the window.
     *
     * @param range range or null
     * @return this builder
     */
    public Builder range(TimeRange range) {
      this.range = range;
      return this;
    }

    /**
     * Sets the page.
     *
     * @param paging paging
     * @return this builder
     */
    public Builder paging(Paging paging) {
      this.paging = paging == null ? Paging.DEFAULT : paging;
      return this;
    }

    /**
     * Sets the order.
     *
     * @param order order
     * @return this builder
     */
    public Builder order(SortOrder order) {
      this.order = order == null ? SortOrder.DESC : order;
      return this;
    }

    /**
     * Builds the options (validated when the query runs).
     *
     * @return options
     */
    public QueryLogsOptions build() {
      return new QueryLogsOptions(this);
    }
  }
}
