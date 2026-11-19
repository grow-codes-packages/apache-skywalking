package codes.grow.packages.skywalking;

/** Order of {@link SkyWalkingQueryClient#queryTraces} results. */
public enum TraceOrder {
  /** Newest first (default). */
  BY_START_TIME,
  /** Longest first. */
  BY_DURATION
}
