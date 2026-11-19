package codes.grow.packages.skywalking;

/**
 * An endpoint (operation) of a native service.
 *
 * @param id {@code serviceId + "_" + base64(name)}
 * @param name endpoint name
 */
public record SkyWalkingEndpoint(String id, String name) {}
