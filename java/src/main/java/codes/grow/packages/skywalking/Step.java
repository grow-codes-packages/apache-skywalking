package codes.grow.packages.skywalking;

/** Granularity of a GraphQL {@code Duration}. */
public enum Step {
  /** {@code yyyy-MM-dd}. */
  DAY,
  /** {@code yyyy-MM-dd HH}. */
  HOUR,
  /** {@code yyyy-MM-dd HHmm} (default). */
  MINUTE,
  /** {@code yyyy-MM-dd HHmmss}. */
  SECOND
}
