package codes.grow.packages.skywalking;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The GraphQL {@code Duration} input: bounds in the OAP time zone, formatted per step ({@code DAY
 * yyyy-MM-dd}, {@code HOUR yyyy-MM-dd HH}, {@code MINUTE yyyy-MM-dd HHmm}, {@code SECOND yyyy-MM-dd
 * HHmmss}).
 *
 * @param start formatted start
 * @param end formatted end
 * @param step step
 */
public record GraphQLDuration(String start, String end, Step step) {
  private static final Pattern TZ = Pattern.compile("^([+-])(\\d{2}):?(\\d{2})$");

  /**
   * A window with defaults applied.
   *
   * @param start start
   * @param end end
   * @param step step
   */
  public record Resolved(Instant start, Instant end, Step step) {}

  /**
   * Parses an OAP time zone ({@code +0000}, {@code -0530}, {@code +08:00}) into minutes east of
   * UTC; unknown text → 0.
   *
   * @param tz time zone text (may be null)
   * @return minutes east of UTC
   */
  public static int parseTimezone(String tz) {
    Matcher m = TZ.matcher(tz == null ? "" : tz.trim());
    if (!m.matches()) {
      return 0;
    }
    int minutes = Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3));
    return "-".equals(m.group(1)) ? -minutes : minutes;
  }

  /**
   * Formats an instant for a {@code Duration} field.
   *
   * @param instant time
   * @param step step
   * @param offsetMinutes OAP offset in minutes east of UTC
   * @return the formatted time
   */
  public static String format(Instant instant, Step step, int offsetMinutes) {
    String pattern;
    switch (step) {
      case DAY:
        pattern = "yyyy-MM-dd";
        break;
      case HOUR:
        pattern = "yyyy-MM-dd HH";
        break;
      case SECOND:
        pattern = "yyyy-MM-dd HHmmss";
        break;
      default:
        pattern = "yyyy-MM-dd HHmm";
        break;
    }
    return DateTimeFormatter.ofPattern(pattern)
        .withZone(ZoneOffset.ofTotalSeconds(offsetMinutes * 60))
        .format(instant);
  }

  /**
   * Builds the GraphQL {@code Duration} for a resolved window.
   *
   * @param range the window
   * @param offsetMinutes OAP offset in minutes east of UTC
   * @return the duration
   */
  public static GraphQLDuration of(Resolved range, int offsetMinutes) {
    return new GraphQLDuration(
        format(range.start(), range.step(), offsetMinutes),
        format(range.end(), range.step(), offsetMinutes),
        range.step());
  }

  /**
   * The GraphQL variable value.
   *
   * @return {@code {start, end, step}}
   */
  public Map<String, Object> toVariable() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("start", start);
    m.put("end", end);
    m.put("step", step.name());
    return m;
  }
}
