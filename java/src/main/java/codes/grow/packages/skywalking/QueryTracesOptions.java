package codes.grow.packages.skywalking;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Filter of {@link SkyWalkingQueryClient#queryTraces} (native traces). Services, instances and
 * endpoints are given by name; the SDK derives the ids.
 */
public final class QueryTracesOptions {
  private final String service;
  private final String instance;
  private final String endpoint;
  private final String traceId;
  private final TraceState traceState;
  private final Long minDurationMs;
  private final Long maxDurationMs;
  private final Map<String, String> tags;
  private final TimeRange range;
  private final Paging paging;
  private final TraceOrder order;

  private QueryTracesOptions(Builder b) {
    this.service = b.service;
    this.instance = b.instance;
    this.endpoint = b.endpoint;
    this.traceId = b.traceId;
    this.traceState = b.traceState;
    this.minDurationMs = b.minDurationMs;
    this.maxDurationMs = b.maxDurationMs;
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
   * No filter: the last hour, first page.
   *
   * @return options
   */
  public static QueryTracesOptions defaults() {
    return builder().build();
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
   * Native trace id.
   *
   * @return the id, or empty
   */
  public Optional<String> traceId() {
    return Optional.ofNullable(traceId);
  }

  /**
   * Trace state (default {@link TraceState#ALL}).
   *
   * @return the state
   */
  public TraceState traceState() {
    return traceState;
  }

  /**
   * Minimum trace duration in ms.
   *
   * @return the duration, or empty
   */
  public OptionalLong minDurationMs() {
    return minDurationMs == null ? OptionalLong.empty() : OptionalLong.of(minDurationMs);
  }

  /**
   * Maximum trace duration in ms.
   *
   * @return the duration, or empty
   */
  public OptionalLong maxDurationMs() {
    return maxDurationMs == null ? OptionalLong.empty() : OptionalLong.of(maxDurationMs);
  }

  /**
   * Tag matches; only keys in the server's {@code SW_SEARCHABLE_TAG_KEYS} can match.
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
   * Page selection (default {@link Paging#DEFAULT}).
   *
   * @return the paging
   */
  public Paging paging() {
    return paging;
  }

  /**
   * Result order (default {@link TraceOrder#BY_START_TIME}).
   *
   * @return the order
   */
  public TraceOrder order() {
    return order;
  }

  /** Builder of {@link QueryTracesOptions}. */
  public static final class Builder {
    private String service;
    private String instance;
    private String endpoint;
    private String traceId;
    private TraceState traceState = TraceState.ALL;
    private Long minDurationMs;
    private Long maxDurationMs;
    private final Map<String, String> tags = new LinkedHashMap<>();
    private TimeRange range;
    private Paging paging = Paging.DEFAULT;
    private TraceOrder order = TraceOrder.BY_START_TIME;

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
     * Sets the native trace id.
     *
     * @param traceId id
     * @return this builder
     */
    public Builder traceId(String traceId) {
      this.traceId = traceId;
      return this;
    }

    /**
     * Sets the trace state.
     *
     * @param traceState state
     * @return this builder
     */
    public Builder traceState(TraceState traceState) {
      this.traceState = traceState == null ? TraceState.ALL : traceState;
      return this;
    }

    /**
     * Sets the minimum trace duration in ms.
     *
     * @param ms milliseconds or null
     * @return this builder
     */
    public Builder minDurationMs(Long ms) {
      this.minDurationMs = ms;
      return this;
    }

    /**
     * Sets the maximum trace duration in ms.
     *
     * @param ms milliseconds or null
     * @return this builder
     */
    public Builder maxDurationMs(Long ms) {
      this.maxDurationMs = ms;
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
    public Builder order(TraceOrder order) {
      this.order = order == null ? TraceOrder.BY_START_TIME : order;
      return this;
    }

    /**
     * Builds the options (validated when the query runs).
     *
     * @return options
     */
    public QueryTracesOptions build() {
      return new QueryTracesOptions(this);
    }
  }
}
