// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! # grow-apache-skywalking
//!
//! Rust SDK for [Apache SkyWalking](https://grow-codes-packages.github.io/apache-skywalking/) by
//! Grow Codes: the OAP GraphQL query API, OpenTelemetry tracing exported over OTLP/gRPC,
//! native logs, `sw8` propagation and the SPEC trace/log query clients.
//!
//! ```no_run
//! # async fn demo() -> grow_apache_skywalking::Result<()> {
//! use grow_apache_skywalking::tracing::TraceQueryClient;
//! use grow_apache_skywalking::{SkyWalkingClient, SkyWalkingConfig};
//!
//! let client = SkyWalkingClient::new(SkyWalkingConfig::from_env()?)?;
//! println!("healthy: {}", client.health().await?.healthy);
//! println!("version: {}", client.server_version().await?);
//! let services = client.query().list_services(None, None).await?;
//! let traced = client.traces().list_services().await?;
//! println!("{} native services, {} traced services", services.len(), traced.len());
//! client.close().await;
//! # Ok(())
//! # }
//! ```
//!
//! | Module | What |
//! | - | - |
//! | [`SkyWalkingClient`] | health, ping, server version, raw GraphQL, log client, tracing setup |
//! | [`SkyWalkingQueryClient`] | services, endpoints, instances, native traces, logs, topology |
//! | [`SkyWalkingTraceQueryClient`] | SPEC `TraceQueryClient` (Zipkin store for OTLP, native store for agents) |
//! | [`SkyWalkingLogQueryClient`] | SPEC `LogQueryClient` (`queryLogs`, client-side text filter) |
//! | [`init_tracing`] | OpenTelemetry → OTLP/gRPC to OAP (port 11800), `sw8` propagator |
//!
//! Features: `blocking` (a blocking client), `testing` (in-memory fakes and the conformance
//! runner), `tracing-layer` and `log-bridge` (logging adapters, on by default), `tower` and
//! `reqwest-middleware` (tracing instrumentation).
#![cfg_attr(docsrs, feature(doc_cfg))]

pub mod core;

mod api;
mod client;
mod config;
mod duration;
mod logs;
mod native;
mod query;
mod setup;
mod sw8;
mod traces;
mod zipkin;

#[cfg(feature = "blocking")]
#[cfg_attr(docsrs, doc(cfg(feature = "blocking")))]
pub mod blocking;

#[cfg(feature = "testing")]
#[cfg_attr(docsrs, doc(cfg(feature = "testing")))]
pub mod testing;

pub mod logging;
pub mod tracing;

pub use crate::core::{Error, ErrorCode, HealthStatus, Result, RetryPolicy, Secret};

pub use crate::api::{
    graphql_error, graphql_errors, GraphQLErrorInfo, GraphQLResponse, SkyWalkingCapabilities,
    TimeInfo,
};
pub use crate::client::{create_log_client, SkyWalkingClient, SkyWalkingLogClientOptions};
pub use crate::config::{
    base_from_graphql_url, collector_to_url, parse_otlp_headers, zipkin_query_url, OtlpProtocol,
    SkyWalkingConfig, SkyWalkingConfigBuilder, TraceBackend, DEFAULT_GRAPHQL_PATH, DEFAULT_URL,
    OTLP_GRPC_PORT, VENDOR_AUTHENTICATION_VARS, VENDOR_OTLP_ENDPOINT_VARS,
    VENDOR_OTLP_HEADERS_VARS, VENDOR_OTLP_PROTOCOL_VARS, VENDOR_TRACE_BACKEND_VARS,
    VENDOR_URL_VARS, VENDOR_ZIPKIN_URL_VARS, ZIPKIN_QUERY_PORT,
};
pub use crate::duration::{
    endpoint_id, epoch_ms, format_duration_time, from_epoch_ms, instance_id, parse_timezone,
    resolve_range, service_id, service_id_with, to_graphql_duration, GraphQLDuration,
    ResolvedRange, Step, TimeRange, DEFAULT_RANGE,
};
pub use crate::logs::{
    log_to_record, matches_text, parse_log_query, record_to_log_data, tag_text, ParsedLogQuery,
    SkyWalkingLogExporter, SkyWalkingLogQueryClient, DEFAULT_LOG_SERVICE_NAME,
    DEFAULT_MAX_SCAN_PAGES, LOG_SCAN_PAGE_SIZE,
};
pub use crate::native::{
    decode_native_span, decode_topology, key_values, native_span_key, native_span_to_span,
    native_trace_from_spans, native_trace_to_trace, topology_to_dependencies, KeyValue, NativeSpan,
    NativeSpanLog, NativeSpanRef, NativeTrace, Topology, TopologyCall, TopologyNode,
    NATIVE_SPAN_FIELDS,
};
pub use crate::query::{
    decode_native_log, InstanceAttribute, LogOrder, NativeLog, Paging, QueryLogsOptions,
    QueryLogsResult, QueryTracesOptions, QueryTracesResult, SkyWalkingEndpoint, SkyWalkingInstance,
    SkyWalkingQueryClient, SkyWalkingService, TopologyOptions, TraceOrder, TraceState,
};
pub use crate::setup::{
    apply_skywalking_defaults, init_tracing, init_tracing_with_config, skywalking_propagators,
    skywalking_span_exporter, tracing_config, AUTHENTICATION_HEADER,
};
pub use crate::sw8::{
    decode_sw8, decode_sw8_correlation, encode_sw8, encode_sw8_correlation, sw8_attributes,
    sw8_context, with_sw8_context, Sw8Context, Sw8InjectContext, Sw8Propagator,
    SW8_CORRELATION_HEADER, SW8_HEADER, SW8_PROPAGATOR,
};
pub use crate::traces::{SkyWalkingTraceQueryClient, DEFAULT_TRACE_SEARCH_LIMIT};
pub use crate::zipkin::{
    annotation_to_event, is_bookkeeping_tag, is_hex_trace_id, strings_from_body, summarize,
    zipkin_span_to_span, zipkin_spans_from_body, zipkin_trace_id, zipkin_trace_to_trace,
    zipkin_traces_from_body, ZipkinAnnotation, ZipkinEndpoint, ZipkinSpan,
};

/// Service-prefixed alias of [`Error`].
pub type SkyWalkingError = Error;
