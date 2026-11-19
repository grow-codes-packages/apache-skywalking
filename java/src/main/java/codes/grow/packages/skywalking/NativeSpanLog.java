package codes.grow.packages.skywalking;

import java.util.List;

/**
 * A log recorded on a native span.
 *
 * @param time epoch milliseconds
 * @param data key/value pairs (agents write {@code event}, {@code message}, {@code error.kind},
 *     ...)
 */
public record NativeSpanLog(long time, List<KeyValue> data) {
  /**
   * Copies the data.
   *
   * @param time time
   * @param data data
   */
  public NativeSpanLog {
    data = data == null ? List.of() : List.copyOf(data);
  }
}
