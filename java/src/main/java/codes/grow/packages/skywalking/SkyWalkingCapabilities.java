package codes.grow.packages.skywalking;

/**
 * Optional query features of the connected OAP (GraphQL {@code hasQueryTracesV2Support}, {@code
 * supportQueryLogsByKeywords}), probed once per client.
 *
 * @param queryTracesV2 {@code queryTraces} (whole traces) is available
 * @param logsByKeywords the log store honours {@code keywordsOfContent} (false on BanyanDB)
 */
public record SkyWalkingCapabilities(boolean queryTracesV2, boolean logsByKeywords) {}
