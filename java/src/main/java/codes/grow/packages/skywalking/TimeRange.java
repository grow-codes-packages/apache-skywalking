package codes.grow.packages.skywalking;

import codes.grow.packages.skywalking.core.ValidationException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Time window of a metadata, topology, trace or log query. Defaults: {@code end} = now, {@code
 * start} = {@code end} − 1 h ({@link #DEFAULT_WINDOW}), {@code step} = {@link Step#MINUTE}. OAP
 * expects the bounds in its own time zone; the client converts them.
 *
 * @param start window start, or empty for {@code end − 1 h}
 * @param end window end, or empty for now
 * @param step bucket granularity, or empty for {@link Step#MINUTE}
 */
public record TimeRange(Optional<Instant> start, Optional<Instant> end, Optional<Step> step) {
  /** Default query window: one hour. */
  public static final Duration DEFAULT_WINDOW = Duration.ofHours(1);

  /** Everything defaulted: the last hour, step MINUTE. */
  public static final TimeRange DEFAULT =
      new TimeRange(Optional.empty(), Optional.empty(), Optional.empty());

  /**
   * Normalises nulls.
   *
   * @param start start
   * @param end end
   * @param step step
   */
  public TimeRange {
    start = start == null ? Optional.empty() : start;
    end = end == null ? Optional.empty() : end;
    step = step == null ? Optional.empty() : step;
  }

  /**
   * A window between two instants (either may be null).
   *
   * @param start start or null
   * @param end end or null
   * @return the range
   */
  public static TimeRange of(Instant start, Instant end) {
    return new TimeRange(Optional.ofNullable(start), Optional.ofNullable(end), Optional.empty());
  }

  /**
   * A window from {@code start} to now.
   *
   * @param start start
   * @return the range
   */
  public static TimeRange since(Instant start) {
    return of(start, null);
  }

  /**
   * The last {@code window} until now (resolved when the query runs).
   *
   * @param window positive duration
   * @return the range
   */
  public static TimeRange last(Duration window) {
    return since(Instant.now().minus(window));
  }

  /**
   * Returns a copy with another step.
   *
   * @param step step
   * @return the range
   */
  public TimeRange withStep(Step step) {
    return new TimeRange(start, end, Optional.ofNullable(step));
  }

  /**
   * Whether no bound and no step is set.
   *
   * @return true when everything is defaulted
   */
  public boolean isDefault() {
    return start.isEmpty() && end.isEmpty() && step.isEmpty();
  }

  /**
   * Applies the defaults and validates.
   *
   * @param now current time
   * @param operation operation name for the error
   * @return the resolved window
   * @throws ValidationException when start is after end
   */
  public GraphQLDuration.Resolved resolve(Instant now, String operation) {
    Instant e = end.orElse(now);
    Instant s = start.orElse(e.minus(DEFAULT_WINDOW));
    if (s.isAfter(e)) {
      throw new ValidationException(operation + ": start must not be after end");
    }
    return new GraphQLDuration.Resolved(s, e, step.orElse(Step.MINUTE));
  }
}
