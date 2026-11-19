package codes.grow.packages.skywalking;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * OpenTelemetry propagator for SkyWalking headers (propagator name {@code sw8}).
 *
 * <ul>
 *   <li><b>extract</b> (default use): a valid {@code sw8} header is stored in the context ({@link
 *       Sw8#fromContext}, {@link Sw8#attributes}); its trace id is not hex, so the W3C context
 *       stays the OpenTelemetry trace. {@code sw8-correlation} entries are added to the baggage.
 *   <li><b>inject</b>: only when native metadata was set with {@link Sw8#withContext}; otherwise
 *       nothing is written (an OpenTelemetry span has no segment id or native span id).
 * </ul>
 *
 * <p>For full native cross-process traces use the official SkyWalking Java agent.
 */
public final class Sw8Propagator implements TextMapPropagator {
  private static final Sw8Propagator INSTANCE = new Sw8Propagator();

  /**
   * The shared instance.
   *
   * @return the propagator
   */
  public static Sw8Propagator getInstance() {
    return INSTANCE;
  }

  @Override
  public Collection<String> fields() {
    return List.of(Sw8.HEADER, Sw8.CORRELATION_HEADER);
  }

  @Override
  public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
    if (context == null || setter == null) {
      return;
    }
    Sw8Context sw8 = context.get(Sw8.INJECT);
    if (sw8 == null) {
      return;
    }
    setter.set(carrier, Sw8.HEADER, Sw8.encode(sw8));
    Map<String, String> correlation = context.get(Sw8.CORRELATION);
    if (correlation != null && !correlation.isEmpty()) {
      setter.set(carrier, Sw8.CORRELATION_HEADER, Sw8.encodeCorrelation(correlation));
    }
  }

  @Override
  public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
    Context out = context == null ? Context.root() : context;
    if (getter == null) {
      return out;
    }
    Context[] holder = {out};
    Sw8.decode(getter.get(carrier, Sw8.HEADER))
        .ifPresent(c -> holder[0] = holder[0].with(Sw8.EXTRACTED, c));
    Map<String, String> correlation =
        Sw8.decodeCorrelation(getter.get(carrier, Sw8.CORRELATION_HEADER));
    if (!correlation.isEmpty()) {
      BaggageBuilder b = Baggage.fromContext(holder[0]).toBuilder();
      correlation.forEach(b::put);
      holder[0] = holder[0].with(b.build());
    }
    return holder[0];
  }

  @Override
  public String toString() {
    return "Sw8Propagator";
  }
}
