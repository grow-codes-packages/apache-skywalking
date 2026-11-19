package codes.grow.packages.skywalking;

/** Outcome filter of {@link SkyWalkingQueryClient#queryTraces}. */
public enum TraceState {
  /** Every trace (default). */
  ALL,
  /** Traces without errors. */
  SUCCESS,
  /** Traces with an error span. */
  ERROR
}
