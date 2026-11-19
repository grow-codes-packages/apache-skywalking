package codes.grow.packages.skywalking;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * SkyWalking entity ids. OAP derives stable ids from names: service id {@code base64(name) + ".1"}
 * ({@code ".0"} for a virtual service), instance id {@code serviceId + "_" + base64(instance)},
 * endpoint id {@code serviceId + "_" + base64(endpoint)} (standard Base64 with padding).
 */
public final class SkyWalkingIds {
  private SkyWalkingIds() {}

  private static String b64(String text) {
    return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The id of a real (normal) service.
   *
   * @param name service name
   * @return {@code base64(name) + ".1"}
   */
  public static String serviceId(String name) {
    return serviceId(name, true);
  }

  /**
   * The id of a service.
   *
   * @param name service name
   * @param normal false for a virtual (conjectured) service
   * @return {@code base64(name) + ".1"} or {@code ".0"}
   */
  public static String serviceId(String name, boolean normal) {
    return b64(name) + (normal ? ".1" : ".0");
  }

  /**
   * The id of a service instance (of a normal service).
   *
   * @param service service name
   * @param instance instance name
   * @return {@code serviceId + "_" + base64(instance)}
   */
  public static String instanceId(String service, String instance) {
    return serviceId(service) + "_" + b64(instance);
  }

  /**
   * The id of an endpoint (of a normal service).
   *
   * @param service service name
   * @param endpoint endpoint name
   * @return {@code serviceId + "_" + base64(endpoint)}
   */
  public static String endpointId(String service, String endpoint) {
    return serviceId(service) + "_" + b64(endpoint);
  }
}
