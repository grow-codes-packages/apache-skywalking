package codes.grow.packages.skywalking;

import codes.grow.packages.skywalking.logging.BatchOptions;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Options of {@link SkyWalkingClient#logClient} and {@link SkyWalkingClient#createLogClient}: the
 * resource of the records ({@code service.name}, {@code service.instance.id}, more attributes) and
 * the batch settings.
 */
public final class SkyWalkingLogClientOptions {
  private final String serviceName;
  private final String serviceInstance;
  private final Map<String, Object> resource;
  private final BatchOptions batchOptions;

  private SkyWalkingLogClientOptions(Builder b) {
    this.serviceName = b.serviceName;
    this.serviceInstance = b.serviceInstance;
    this.resource = Collections.unmodifiableMap(new LinkedHashMap<>(b.resource));
    this.batchOptions = b.batchOptions;
  }

  /**
   * Returns a builder.
   *
   * @return builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Options with a service name and default batching.
   *
   * @param serviceName {@code service.name} of the records
   * @return options
   */
  public static SkyWalkingLogClientOptions forService(String serviceName) {
    return builder().serviceName(serviceName).build();
  }

  /**
   * {@code service.name} of the records (default {@value
   * SkyWalkingLogExporter#DEFAULT_SERVICE_NAME}).
   *
   * @return the name, or empty
   */
  public Optional<String> serviceName() {
    return Optional.ofNullable(serviceName);
  }

  /**
   * {@code service.instance.id} of the records.
   *
   * @return the instance, or empty
   */
  public Optional<String> serviceInstance() {
    return Optional.ofNullable(serviceInstance);
  }

  /**
   * The resource attributes added to every record (including service name and instance).
   *
   * @return immutable map
   */
  public Map<String, Object> resource() {
    Map<String, Object> out = new LinkedHashMap<>(resource);
    if (serviceName != null) {
      out.put("service.name", serviceName);
    }
    if (serviceInstance != null) {
      out.put("service.instance.id", serviceInstance);
    }
    return Collections.unmodifiableMap(out);
  }

  /**
   * Batch settings.
   *
   * @return the options
   */
  public BatchOptions batchOptions() {
    return batchOptions;
  }

  /** Builder of {@link SkyWalkingLogClientOptions}. */
  public static final class Builder {
    private String serviceName;
    private String serviceInstance;
    private final Map<String, Object> resource = new LinkedHashMap<>();
    private BatchOptions batchOptions = BatchOptions.defaults();

    private Builder() {}

    /**
     * Sets {@code service.name}.
     *
     * @param serviceName name
     * @return this builder
     */
    public Builder serviceName(String serviceName) {
      this.serviceName = serviceName;
      return this;
    }

    /**
     * Sets {@code service.instance.id}.
     *
     * @param serviceInstance instance name
     * @return this builder
     */
    public Builder serviceInstance(String serviceInstance) {
      this.serviceInstance = serviceInstance;
      return this;
    }

    /**
     * Adds resource attributes.
     *
     * @param resource attributes
     * @return this builder
     */
    public Builder resource(Map<String, ?> resource) {
      this.resource.putAll(resource);
      return this;
    }

    /**
     * Sets the batch settings.
     *
     * @param batchOptions options
     * @return this builder
     */
    public Builder batchOptions(BatchOptions batchOptions) {
      this.batchOptions = Objects.requireNonNull(batchOptions, "batchOptions");
      return this;
    }

    /**
     * Builds the options.
     *
     * @return options
     */
    public SkyWalkingLogClientOptions build() {
      return new SkyWalkingLogClientOptions(this);
    }
  }
}
