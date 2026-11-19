package codes.grow.packages.skywalking;

import java.util.List;
import java.util.Optional;

/**
 * Result of {@link SkyWalkingQueryClient#queryLogs}.
 *
 * @param logs matching logs
 * @param errorReason server explanation when the query could not run as asked
 */
public record QueryLogsResult(List<NativeLog> logs, Optional<String> errorReason) {
  /**
   * Copies the logs.
   *
   * @param logs logs
   * @param errorReason reason
   */
  public QueryLogsResult {
    logs = logs == null ? List.of() : List.copyOf(logs);
    errorReason = errorReason == null ? Optional.empty() : errorReason;
  }
}
