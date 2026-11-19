// Service code (Apache SkyWalking): OAP's Zipkin span model and its SPEC §5 conversion.
//! OAP's Zipkin v2 span model (how OTLP spans are stored and read back, `:9412/zipkin`) and its
//! conversion into the SPEC §5 trace model.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::core::{Error, Result};
use crate::tracing::{
    normalize_trace_id, sort_spans, AttrValue, Attributes, Span, SpanEvent, SpanKind, SpanStatus,
    StatusCode, Trace, TraceSummary,
};

/// Endpoint of a Zipkin span.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ZipkinEndpoint {
    /// Service name.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub service_name: Option<String>,
    /// IPv4 address.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ipv4: Option<String>,
    /// IPv6 address.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ipv6: Option<String>,
    /// Port.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub port: Option<u16>,
}

/// A Zipkin annotation (OAP encodes OTLP events as `name|attributesJSON|droppedCount`).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct ZipkinAnnotation {
    /// Epoch microseconds.
    #[serde(default)]
    pub timestamp: u64,
    /// Annotation value.
    #[serde(default)]
    pub value: String,
}

/// A span as returned by OAP's Zipkin query API (`:9412/zipkin/api/v2`).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ZipkinSpan {
    /// Trace id (hex).
    pub trace_id: String,
    /// Span id (hex).
    pub id: String,
    /// Parent span id.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parent_id: Option<String>,
    /// `CLIENT`, `SERVER`, `PRODUCER` or `CONSUMER` (absent for internal spans).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    /// Span name (lower-cased by OAP).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// Epoch microseconds.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub timestamp: Option<u64>,
    /// Microseconds.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub duration: Option<u64>,
    /// Local endpoint (`serviceName` = the OTel `service.name`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub local_endpoint: Option<ZipkinEndpoint>,
    /// Remote endpoint.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub remote_endpoint: Option<ZipkinEndpoint>,
    /// Annotations (events).
    #[serde(default)]
    pub annotations: Vec<ZipkinAnnotation>,
    /// Tags (strings).
    #[serde(default)]
    pub tags: BTreeMap<String, String>,
}

fn is_hex_len(id: &str, lens: &[usize]) -> bool {
    lens.contains(&id.len()) && id.bytes().all(|b| b.is_ascii_hexdigit())
}

/// True for a 16- or 32-character hex id (OTLP/Zipkin), false for native SkyWalking ids.
pub fn is_hex_trace_id(id: &str) -> bool {
    is_hex_len(id.trim(), &[16, 32])
}

/// Lower-cases and validates a Zipkin trace id (16 or 32 hex, not all zeros); `VALIDATION`
/// otherwise.
pub fn zipkin_trace_id(id: &str, operation: &str) -> Result<String> {
    let lower = id.trim().to_ascii_lowercase();
    if !is_hex_len(&lower, &[16, 32]) || lower.bytes().all(|b| b == b'0') {
        return Err(Error::validation(format!(
            "{operation}: trace id must be 16 or 32 hex characters"
        )));
    }
    Ok(lower)
}

/// Keeps the well-formed spans of a response body.
pub fn zipkin_spans_from_body(body: &Value) -> Vec<ZipkinSpan> {
    body.as_array()
        .into_iter()
        .flatten()
        .filter(|s| {
            s.get("traceId").is_some_and(Value::is_string)
                && s.get("id").is_some_and(Value::is_string)
        })
        .filter_map(|s| serde_json::from_value(s.clone()).ok())
        .collect()
}

/// Keeps the non-empty traces of a `[[span…]…]` body.
pub fn zipkin_traces_from_body(body: &Value) -> Vec<Vec<ZipkinSpan>> {
    body.as_array()
        .into_iter()
        .flatten()
        .map(zipkin_spans_from_body)
        .filter(|t| !t.is_empty())
        .collect()
}

/// Decodes a `string[]` body.
pub fn strings_from_body(body: &Value) -> Vec<String> {
    crate::native::strings(Some(body))
}

/// Tags OAP adds as bookkeeping; they are mapped to status/kind and dropped from attributes.
pub fn is_bookkeeping_tag(key: &str) -> bool {
    key.starts_with("otel.") || key == "error" || key == "w3c.tracestate"
}

/// Decodes an OAP annotation into an event: `name|{"k":"v"}|0` (split on the first and last
/// `|`) → name + attributes; any other value is the event name without attributes.
pub fn annotation_to_event(annotation: &ZipkinAnnotation) -> SpanEvent {
    let value = &annotation.value;
    if let (Some(first), Some(last)) = (value.find('|'), value.rfind('|')) {
        if first > 0 && last > first {
            let json = &value[first + 1..last];
            let parsed = if json.is_empty() {
                Ok(Value::Object(Default::default()))
            } else {
                serde_json::from_str::<Value>(json)
            };
            if let Ok(Value::Object(map)) = parsed {
                return SpanEvent {
                    name: value[..first].to_owned(),
                    time_micros: annotation.timestamp,
                    attributes: map.into_iter().collect(),
                };
            }
        }
    }
    SpanEvent {
        name: value.clone(),
        time_micros: annotation.timestamp,
        attributes: Attributes::new(),
    }
}

/// Converts a stored Zipkin span into the SPEC §5 model: ids lower-case (trace id padded to
/// 32), `kind` lower-case (absent → `internal`), `service_name` = `localEndpoint.serviceName`,
/// status from `otel.status_code` (`STATUS_CODE_ERROR` → error with `otel.status_description`,
/// `STATUS_CODE_OK` → ok; an `error` tag alone → error), attributes = tags without the
/// `otel.*`, `error` and `w3c.tracestate` bookkeeping keys (values are strings: OAP stores tags
/// as text), annotations → events. Span names are lower-cased by OAP on ingest.
pub fn zipkin_span_to_span(span: &ZipkinSpan) -> Span {
    let tags = &span.tags;
    let start = span.timestamp.unwrap_or(0);
    let duration = span.duration.unwrap_or(0);
    let code = tags
        .get("otel.status_code")
        .map(|c| c.to_ascii_uppercase())
        .unwrap_or_default();
    let status = if code == "STATUS_CODE_ERROR" || code == "ERROR" {
        SpanStatus {
            code: StatusCode::Error,
            message: tags
                .get("otel.status_description")
                .filter(|m| !m.is_empty())
                .cloned(),
        }
    } else if code == "STATUS_CODE_OK" || code == "OK" {
        SpanStatus {
            code: StatusCode::Ok,
            message: None,
        }
    } else if tags.contains_key("error") {
        SpanStatus {
            code: StatusCode::Error,
            message: None,
        }
    } else {
        SpanStatus::default()
    };
    let attributes: Attributes = tags
        .iter()
        .filter(|(k, _)| !is_bookkeeping_tag(k))
        .map(|(k, v)| (k.clone(), AttrValue::String(v.clone())))
        .collect();
    let kind = match span.kind.as_deref() {
        None => SpanKind::Internal,
        Some(k) => match SpanKind::parse(k) {
            SpanKind::Internal => SpanKind::Unspecified,
            other => other,
        },
    };
    Span {
        trace_id: normalize_trace_id(&span.trace_id)
            .unwrap_or_else(|_| span.trace_id.to_ascii_lowercase()),
        span_id: span.id.to_ascii_lowercase(),
        parent_span_id: span
            .parent_id
            .as_deref()
            .filter(|p| !p.is_empty())
            .map(str::to_ascii_lowercase),
        name: span.name.clone().unwrap_or_default(),
        kind,
        service_name: span
            .local_endpoint
            .as_ref()
            .and_then(|e| e.service_name.clone())
            .unwrap_or_default(),
        start_micros: start,
        end_micros: start + duration,
        duration_micros: duration,
        status,
        attributes,
        events: span.annotations.iter().map(annotation_to_event).collect(),
        links: Vec::new(),
    }
}

/// Converts one Zipkin trace into a SPEC [`Trace`]; `None` when empty.
pub fn zipkin_trace_to_trace(spans: &[ZipkinSpan]) -> Option<Trace> {
    let first = spans.first()?;
    let mut out: Vec<Span> = spans.iter().map(zipkin_span_to_span).collect();
    sort_spans(&mut out);
    Some(Trace {
        trace_id: normalize_trace_id(&first.trace_id)
            .unwrap_or_else(|_| first.trace_id.to_ascii_lowercase()),
        spans: out,
    })
}

/// Summarises a SPEC trace; the root falls back to the earliest span.
pub fn summarize(trace: &Trace) -> TraceSummary {
    let mut summary = trace.summary();
    if summary.root_name.is_none() {
        if let Some(earliest) = trace.spans.first() {
            summary.root_name = Some(earliest.name.clone());
            summary.root_service_name = Some(earliest.service_name.clone());
        }
    }
    summary
}
