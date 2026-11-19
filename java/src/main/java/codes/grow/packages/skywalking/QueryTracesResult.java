package codes.grow.packages.skywalking;

import java.util.List;
import java.util.Optional;

/**
 * Result of {@link SkyWalkingQueryClient#queryTraces}.
 *
 * @param traces whole traces (every segment) that match
 * @param retrievedTimeRange window the server searched, when reported
 */
public record QueryTracesResult(
    List<NativeTrace> traces, Optional<RetrievedTimeRange> retrievedTimeRange) {
  /**
   * Copies the traces.
   *
   * @param traces traces
   * @param retrievedTimeRange window
   */
  public QueryTracesResult {
    traces = traces == null ? List.of() : List.copyOf(traces);
    retrievedTimeRange = retrievedTimeRange == null ? Optional.empty() : retrievedTimeRange;
  }

  /**
   * A searched window.
   *
   * @param startTime start (epoch ms)
   * @param endTime end (epoch ms)
   */
  public record RetrievedTimeRange(long startTime, long endTime) {}
}
