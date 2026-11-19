package codes.grow.packages.skywalking;

import java.time.Instant;

/**
 * The OAP clock (GraphQL {@code getTimeInfo}).
 *
 * @param timezone OAP offset, for example {@code +0000}
 * @param currentTimestamp OAP wall clock in epoch milliseconds
 */
public record TimeInfo(String timezone, long currentTimestamp) {
  /**
   * The OAP wall clock as an instant.
   *
   * @return the instant
   */
  public Instant currentTime() {
    return Instant.ofEpochMilli(currentTimestamp);
  }
}
