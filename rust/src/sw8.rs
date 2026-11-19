// Service code (Apache SkyWalking): `sw8` (v3) and `sw8-correlation` propagation.
//! SkyWalking cross-process propagation headers `sw8` (v3) and `sw8-correlation`, and the
//! OpenTelemetry [`Sw8Propagator`] (propagator name `sw8`).
//!
//! An OpenTelemetry span has no segment id, native span id or endpoint, so the propagator
//! **extracts** by default (the parent is stored in the context, see [`sw8_context`]) and
//! **injects** only native metadata supplied with [`with_sw8_context`]. Teams that need full
//! native cross-process traces should use the official SkyWalking agents.

use base64::engine::general_purpose::STANDARD;
use base64::Engine as _;
use opentelemetry::baggage::{Baggage, BaggageExt};
use opentelemetry::propagation::text_map_propagator::FieldIter;
use opentelemetry::propagation::{Extractor, Injector, TextMapPropagator};
use opentelemetry::Context;
use serde::{Deserialize, Serialize};

use crate::tracing::{AttrValue, Attributes};

/// Header name of the SkyWalking v3 trace context.
pub const SW8_HEADER: &str = "sw8";
/// Header name of the SkyWalking correlation context.
pub const SW8_CORRELATION_HEADER: &str = "sw8-correlation";
/// Name of the propagator in `TracingConfig::propagators` (`PropagatorKind::Other("sw8")`).
pub const SW8_PROPAGATOR: &str = "sw8";

/// Decoded `sw8` header (SkyWalking cross-process propagation v3).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Sw8Context {
    /// Whether the upstream sampled the trace.
    pub sample: bool,
    /// Native trace id (opaque string, e.g. `<hex>.<n>.<ms>`).
    pub trace_id: String,
    /// Segment id of the parent (calling) segment.
    pub parent_trace_segment_id: String,
    /// Span id of the exit span in the parent segment.
    pub parent_span_id: i64,
    /// Parent service name.
    pub parent_service: String,
    /// Parent service instance name.
    pub parent_service_instance: String,
    /// Endpoint (operation) of the parent's entry span.
    pub parent_endpoint: String,
    /// Address the caller used to reach this service (`host:port`).
    pub network_address_used_at_peer: String,
}

/// [`Sw8Context`] plus an optional correlation list, for [`with_sw8_context`].
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Sw8InjectContext {
    /// Native metadata written as `sw8`.
    pub sw8: Sw8Context,
    /// Entries written as `sw8-correlation` (in order).
    pub correlation: Vec<(String, String)>,
}

fn enc(text: &str) -> String {
    STANDARD.encode(text.as_bytes())
}

fn dec(text: &str) -> Option<String> {
    let bytes = STANDARD.decode(text.as_bytes()).ok()?;
    String::from_utf8(bytes).ok()
}

/// Encodes an `sw8` header value:
/// `sample-b64(traceId)-b64(segment)-spanId-b64(service)-b64(instance)-b64(endpoint)-b64(peer)`.
pub fn encode_sw8(ctx: &Sw8Context) -> String {
    [
        if ctx.sample { "1" } else { "0" }.to_owned(),
        enc(&ctx.trace_id),
        enc(&ctx.parent_trace_segment_id),
        ctx.parent_span_id.to_string(),
        enc(&ctx.parent_service),
        enc(&ctx.parent_service_instance),
        enc(&ctx.parent_endpoint),
        enc(&ctx.network_address_used_at_peer),
    ]
    .join("-")
}

/// Decodes an `sw8` header value; `None` when it is malformed (wrong field count, bad Base64,
/// non-numeric span id, empty trace or segment id).
pub fn decode_sw8(header: &str) -> Option<Sw8Context> {
    let parts: Vec<&str> = header.trim().split('-').collect();
    let [sample, trace, segment, span, service, instance, endpoint, peer] = parts.as_slice() else {
        return None;
    };
    let sample = match *sample {
        "1" => true,
        "0" => false,
        _ => return None,
    };
    let parent_span_id: i64 = span.parse().ok()?;
    let trace_id = dec(trace)?;
    let parent_trace_segment_id = dec(segment)?;
    if trace_id.is_empty() || parent_trace_segment_id.is_empty() {
        return None;
    }
    Some(Sw8Context {
        sample,
        trace_id,
        parent_trace_segment_id,
        parent_span_id,
        parent_service: dec(service)?,
        parent_service_instance: dec(instance)?,
        parent_endpoint: dec(endpoint)?,
        network_address_used_at_peer: dec(peer)?,
    })
}

/// Encodes `sw8-correlation`: `b64(key):b64(value),…` in order.
pub fn encode_sw8_correlation<K: AsRef<str>, V: AsRef<str>>(entries: &[(K, V)]) -> String {
    entries
        .iter()
        .map(|(k, v)| format!("{}:{}", enc(k.as_ref()), enc(v.as_ref())))
        .collect::<Vec<_>>()
        .join(",")
}

/// Decodes `sw8-correlation`; malformed entries are skipped.
pub fn decode_sw8_correlation(header: &str) -> Vec<(String, String)> {
    header
        .split(',')
        .filter_map(|part| {
            let mut it = part.trim().split(':');
            let (k, v) = (it.next()?, it.next()?);
            if it.next().is_some() {
                return None;
            }
            let key = dec(k)?;
            let value = dec(v)?;
            (!key.is_empty()).then_some((key, value))
        })
        .collect()
}

/// Context value: the `sw8` parent extracted from an incoming request.
#[derive(Debug, Clone)]
struct Extracted(Sw8Context);

/// Context value: native metadata to inject on outgoing requests.
#[derive(Debug, Clone)]
struct ToInject(Sw8InjectContext);

/// The `sw8` context extracted from an incoming request, if any.
pub fn sw8_context(cx: &Context) -> Option<Sw8Context> {
    cx.get::<Extracted>().map(|e| e.0.clone())
}

/// Returns a context whose outgoing requests carry this native metadata as `sw8` (and
/// `sw8-correlation`). The SDK cannot derive native metadata from an OpenTelemetry span; supply
/// it only when you bridge to SkyWalking agents.
pub fn with_sw8_context(cx: &Context, sw8: Sw8InjectContext) -> Context {
    cx.with_value(ToInject(sw8))
}

/// Span attributes describing an extracted `sw8` parent (`sw8.parent.*`, `sw8.peer`).
pub fn sw8_attributes(sw8: &Sw8Context) -> Attributes {
    let s = |v: &String| AttrValue::String(v.clone());
    [
        ("sw8.parent.trace_id", s(&sw8.trace_id)),
        ("sw8.parent.segment_id", s(&sw8.parent_trace_segment_id)),
        ("sw8.parent.span_id", AttrValue::from(sw8.parent_span_id)),
        ("sw8.parent.service", s(&sw8.parent_service)),
        (
            "sw8.parent.service_instance",
            s(&sw8.parent_service_instance),
        ),
        ("sw8.parent.endpoint", s(&sw8.parent_endpoint)),
        ("sw8.peer", s(&sw8.network_address_used_at_peer)),
    ]
    .into_iter()
    .map(|(k, v)| (k.to_owned(), v))
    .collect()
}

/// OpenTelemetry propagator for SkyWalking headers (propagator name `sw8`).
///
/// - **extract** (default use): a valid `sw8` header is stored in the context
///   ([`sw8_context`], [`sw8_attributes`]); its trace id is not hex, so the W3C context stays
///   the OpenTelemetry trace. `sw8-correlation` entries are added to the baggage.
/// - **inject**: only when native metadata was set with [`with_sw8_context`]; otherwise
///   nothing is written.
#[derive(Debug, Clone)]
pub struct Sw8Propagator {
    fields: [String; 2],
}

impl Default for Sw8Propagator {
    fn default() -> Self {
        Self::new()
    }
}

impl Sw8Propagator {
    /// A propagator.
    pub fn new() -> Self {
        Sw8Propagator {
            fields: [SW8_HEADER.to_owned(), SW8_CORRELATION_HEADER.to_owned()],
        }
    }
}

impl TextMapPropagator for Sw8Propagator {
    fn inject_context(&self, cx: &Context, injector: &mut dyn Injector) {
        let Some(ToInject(data)) = cx.get::<ToInject>() else {
            return;
        };
        injector.set(SW8_HEADER, encode_sw8(&data.sw8));
        if !data.correlation.is_empty() {
            injector.set(
                SW8_CORRELATION_HEADER,
                encode_sw8_correlation(&data.correlation),
            );
        }
    }

    fn extract_with_context(&self, cx: &Context, extractor: &dyn Extractor) -> Context {
        let mut out = cx.clone();
        if let Some(sw8) = extractor.get(SW8_HEADER).and_then(decode_sw8) {
            out = out.with_value(Extracted(sw8));
        }
        let correlation = extractor
            .get(SW8_CORRELATION_HEADER)
            .map(decode_sw8_correlation)
            .unwrap_or_default();
        if !correlation.is_empty() {
            let mut baggage: Baggage = out
                .baggage()
                .iter()
                .map(|(k, v)| (k.clone(), v.clone()))
                .collect();
            for (k, v) in correlation {
                baggage.insert(k, v);
            }
            out = out.with_baggage(baggage);
        }
        out
    }

    fn fields(&self) -> FieldIter<'_> {
        FieldIter::new(&self.fields)
    }
}
