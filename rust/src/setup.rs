// Service code (Apache SkyWalking): `init_tracing` exporting OTLP/gRPC to the OAP receiver.
//! `init_tracing` — OpenTelemetry tracing that exports OTLP/gRPC to SkyWalking OAP (port 11800).
//! Spans sent this way are stored in OAP's Zipkin model and are readable with
//! [`SkyWalkingClient::traces`](crate::SkyWalkingClient::traces) about 5–15 s after export.

use std::collections::HashMap;

use opentelemetry_otlp::SpanExporter;

use crate::config::{OtlpProtocol, SkyWalkingConfig};
use crate::core::{Error, Result};
use crate::sw8::{Sw8Propagator, SW8_PROPAGATOR};
use crate::tracing::{
    init_tracing_with_propagators, otlp_exporter, BoxedPropagator, Protocol, TracingConfig,
    TracingHandle,
};

/// gRPC metadata key OAP reads the token from (`SW_AUTHENTICATION`).
pub const AUTHENTICATION_HEADER: &str = "authentication";

/// A [`TracingConfig`] for `service_name` with the SkyWalking protocol default (`grpc`).
pub fn tracing_config(service_name: impl Into<String>) -> TracingConfig {
    TracingConfig::new(service_name).with_protocol(Protocol::Grpc)
}

/// Merges the SkyWalking defaults under `config`: when `config.endpoint` is unset, the endpoint
/// and protocol come from `otlp_endpoint` / `otlp_protocol` (an explicit endpoint keeps the
/// config's protocol); headers = `otlp_headers` plus the `authentication` token, overridden by
/// the config's own headers; TLS from the SkyWalking config when the config has none.
pub fn apply_skywalking_defaults(
    mut config: TracingConfig,
    sw: &SkyWalkingConfig,
) -> TracingConfig {
    if config.endpoint.is_none() {
        config.endpoint = Some(sw.otlp_endpoint().to_owned());
        config.protocol = match sw.otlp_protocol() {
            OtlpProtocol::Grpc => Protocol::Grpc,
            OtlpProtocol::HttpProtobuf => Protocol::HttpProtobuf,
        };
    }
    let mut headers = sw.otlp_headers().clone();
    if let Some(token) = sw.authentication() {
        headers.insert(
            AUTHENTICATION_HEADER.to_owned(),
            token.expose_secret().to_owned(),
        );
    }
    headers.extend(std::mem::take(&mut config.headers));
    config.headers = headers;
    if config.tls.is_none() {
        config.tls = Some(sw.common().tls().clone());
    }
    config
}

/// Builds the span exporter: `grpc` → OTLP/gRPC to the OAP receiver (headers become gRPC
/// metadata); `http/protobuf` → OTLP/HTTP protobuf to `<endpoint>/v1/traces` (only for an
/// OpenTelemetry Collector in front of OAP — OAP 10.4 serves no OTLP/HTTP). `native` is not
/// supported (`CONFIG`): use the official SkyWalking agents.
pub fn skywalking_span_exporter(config: &TracingConfig) -> Result<SpanExporter> {
    if config.protocol == Protocol::Native {
        return Err(Error::config(
            "tracing: the SkyWalking native segment protocol is not supported; use grpc (OTLP) or the official SkyWalking agents",
        ));
    }
    otlp_exporter(config)
}

/// The extra propagators this package provides (`sw8`, see [`Sw8Propagator`]).
pub fn skywalking_propagators() -> HashMap<String, BoxedPropagator> {
    let mut map: HashMap<String, BoxedPropagator> = HashMap::new();
    map.insert(SW8_PROPAGATOR.to_owned(), Box::new(Sw8Propagator::new()));
    map
}

/// Sets up OpenTelemetry tracing that exports OTLP/gRPC to SkyWalking OAP (SPEC §5), with the
/// connection settings from [`SkyWalkingConfig::from_env`]. See [`init_tracing_with_config`].
pub fn init_tracing(config: TracingConfig) -> Result<TracingHandle> {
    init_tracing_with_config(config, &SkyWalkingConfig::from_env()?)
}

/// Sets up OpenTelemetry tracing that exports to SkyWalking OAP. Endpoint, protocol, headers
/// and token come from `sw` unless the [`TracingConfig`] sets them (see
/// [`apply_skywalking_defaults`]). The `sw8` propagator name is available
/// (`PropagatorKind::Other("sw8".into())`; extract by default, see [`Sw8Propagator`]). Must be
/// called inside a tokio runtime; `CONFIG` for invalid settings.
pub fn init_tracing_with_config(
    config: TracingConfig,
    sw: &SkyWalkingConfig,
) -> Result<TracingHandle> {
    init_tracing_with_propagators(
        apply_skywalking_defaults(config, sw),
        skywalking_span_exporter,
        skywalking_propagators(),
    )
}
