package codes.grow.packages.skywalking;

/**
 * A decoded {@code sw8} header (SkyWalking cross-process propagation v3): the parent of a native
 * SkyWalking span.
 *
 * @param sample whether the upstream sampled the trace
 * @param traceId native trace id (opaque string, e.g. {@code <hex>.<n>.<ms>})
 * @param parentTraceSegmentId segment id of the parent (calling) segment
 * @param parentSpanId span id of the exit span in the parent segment
 * @param parentService service of the caller
 * @param parentServiceInstance instance of the caller
 * @param parentEndpoint endpoint (operation) of the caller's entry span
 * @param networkAddressUsedAtPeer address the caller used to reach this service ({@code host:port})
 */
public record Sw8Context(
    boolean sample,
    String traceId,
    String parentTraceSegmentId,
    int parentSpanId,
    String parentService,
    String parentServiceInstance,
    String parentEndpoint,
    String networkAddressUsedAtPeer) {
  /**
   * Normalises nulls to empty strings.
   *
   * @param sample sample flag
   * @param traceId trace id
   * @param parentTraceSegmentId segment id
   * @param parentSpanId span id
   * @param parentService service
   * @param parentServiceInstance instance
   * @param parentEndpoint endpoint
   * @param networkAddressUsedAtPeer peer address
   */
  public Sw8Context {
    traceId = traceId == null ? "" : traceId;
    parentTraceSegmentId = parentTraceSegmentId == null ? "" : parentTraceSegmentId;
    parentService = parentService == null ? "" : parentService;
    parentServiceInstance = parentServiceInstance == null ? "" : parentServiceInstance;
    parentEndpoint = parentEndpoint == null ? "" : parentEndpoint;
    networkAddressUsedAtPeer = networkAddressUsedAtPeer == null ? "" : networkAddressUsedAtPeer;
  }

  /**
   * Encodes the header value (see {@link Sw8#encode}).
   *
   * @return the {@code sw8} value
   */
  public String encode() {
    return Sw8.encode(this);
  }
}
