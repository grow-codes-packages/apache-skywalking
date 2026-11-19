// Service code (Apache SkyWalking): the native query model and its SPEC §5 conversion.
//! The native SkyWalking query model (GraphQL: spans, traces, topology) and its conversion into
//! the SPEC §5 trace model. Native trace ids are opaque strings (`<hex>.<n>.<ms>`) and are kept
//! verbatim; native span ids become `"<segmentId>-<spanId>"`.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::tracing::{
    sort_spans, AttrValue, Attributes, DependencyLink, Span, SpanEvent, SpanKind, SpanLink,
    SpanStatus, StatusCode, Trace,
};

/// A `{key, value}` pair (span tags, log tags, log data).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct KeyValue {
    /// Key.
    pub key: String,
    /// Value (non-string values are stringified, `null` → `""`).
    pub value: String,
}

impl KeyValue {
    /// A pair.
    pub fn new(key: impl Into<String>, value: impl Into<String>) -> Self {
        KeyValue {
            key: key.into(),
            value: value.into(),
        }
    }
}

/// Cross-segment reference of a native span.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeSpanRef {
    /// Trace id.
    pub trace_id: String,
    /// Segment id of the parent.
    pub parent_segment_id: String,
    /// Span id of the parent within its segment.
    pub parent_span_id: i64,
    /// `CROSS_PROCESS` or `CROSS_THREAD`.
    #[serde(rename = "type")]
    pub ref_type: String,
}

/// A log entry recorded on a native span.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct NativeSpanLog {
    /// Epoch milliseconds.
    pub time: i64,
    /// Log fields.
    pub data: Vec<KeyValue>,
}

/// A span as stored by SkyWalking (agents, `/v3/segments`); identity = `(segment_id, span_id)`.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeSpan {
    /// Opaque native trace id, or a hex id from an OTel context.
    pub trace_id: String,
    /// Segment id.
    pub segment_id: String,
    /// Span id within the segment.
    pub span_id: i64,
    /// Parent within the segment; `-1` = segment root (parent in `refs`).
    pub parent_span_id: i64,
    /// Cross-segment references.
    pub refs: Vec<NativeSpanRef>,
    /// Service name.
    pub service_code: String,
    /// Instance name.
    pub service_instance_name: String,
    /// Epoch milliseconds.
    pub start_time: i64,
    /// Epoch milliseconds.
    pub end_time: i64,
    /// Endpoint (operation) name.
    pub endpoint_name: String,
    /// `Entry`, `Exit` or `Local`.
    #[serde(rename = "type")]
    pub span_type: String,
    /// Remote address.
    pub peer: String,
    /// Component name.
    pub component: String,
    /// Error flag.
    pub is_error: bool,
    /// Layer (`Http`, `Database`, …).
    pub layer: String,
    /// Tags.
    pub tags: Vec<KeyValue>,
    /// Span logs.
    pub logs: Vec<NativeSpanLog>,
}

/// A native trace: every segment's spans.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeTrace {
    /// Trace id (verbatim).
    pub trace_id: String,
    /// Spans of every segment.
    pub spans: Vec<NativeSpan>,
}

/// A node of the service topology.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TopologyNode {
    /// Service id.
    pub id: String,
    /// Service name.
    pub name: String,
    /// Node type (component), may be empty.
    #[serde(rename = "type")]
    pub node_type: String,
    /// `false` for virtual nodes (e.g. `User`).
    pub is_real: bool,
    /// Layers.
    pub layers: Vec<String>,
}

/// An edge of the service topology.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TopologyCall {
    /// Edge id.
    pub id: String,
    /// Source node id.
    pub source: String,
    /// Target node id.
    pub target: String,
    /// `CLIENT` / `SERVER` / `PROXY`.
    pub detect_points: Vec<String>,
    /// Components seen at the source.
    pub source_components: Vec<String>,
    /// Components seen at the target.
    pub target_components: Vec<String>,
}

/// `getGlobalTopology` / `getServiceTopology` result.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Topology {
    /// Nodes.
    pub nodes: Vec<TopologyNode>,
    /// Edges.
    pub calls: Vec<TopologyCall>,
}

/// GraphQL selection used for native spans.
pub const NATIVE_SPAN_FIELDS: &str = "traceId segmentId spanId parentSpanId refs{traceId parentSegmentId parentSpanId type} serviceCode serviceInstanceName startTime endTime endpointName type peer component isError layer tags{key value} logs{time data{key value}}";

pub(crate) fn str_of(v: &Value, key: &str) -> String {
    v.get(key)
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_owned()
}

pub(crate) fn i64_of(v: &Value, key: &str, fallback: i64) -> i64 {
    v.get(key)
        .and_then(|x| x.as_i64().or_else(|| x.as_f64().map(|f| f as i64)))
        .unwrap_or(fallback)
}

pub(crate) fn objects(v: Option<&Value>) -> impl Iterator<Item = &Value> {
    v.and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter(|x| x.is_object())
}

pub(crate) fn strings(v: Option<&Value>) -> Vec<String> {
    v.and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .map(str::to_owned)
        .collect()
}

/// Decodes a `[{key, value}]` list (non-string values are stringified, `null` → `""`).
pub fn key_values(value: Option<&Value>) -> Vec<KeyValue> {
    objects(value)
        .map(|kv| KeyValue {
            key: str_of(kv, "key"),
            value: match kv.get("value") {
                Some(Value::String(s)) => s.clone(),
                None | Some(Value::Null) => String::new(),
                Some(other) => other.to_string(),
            },
        })
        .collect()
}

/// Decodes one native span from a GraphQL response (missing fields get neutral defaults).
pub fn decode_native_span(raw: &Value) -> NativeSpan {
    NativeSpan {
        trace_id: str_of(raw, "traceId"),
        segment_id: str_of(raw, "segmentId"),
        span_id: i64_of(raw, "spanId", 0),
        parent_span_id: i64_of(raw, "parentSpanId", -1),
        refs: objects(raw.get("refs"))
            .map(|r| NativeSpanRef {
                trace_id: str_of(r, "traceId"),
                parent_segment_id: str_of(r, "parentSegmentId"),
                parent_span_id: i64_of(r, "parentSpanId", 0),
                ref_type: str_of(r, "type"),
            })
            .collect(),
        service_code: str_of(raw, "serviceCode"),
        service_instance_name: str_of(raw, "serviceInstanceName"),
        start_time: i64_of(raw, "startTime", 0),
        end_time: i64_of(raw, "endTime", 0),
        endpoint_name: str_of(raw, "endpointName"),
        span_type: str_of(raw, "type"),
        peer: str_of(raw, "peer"),
        component: str_of(raw, "component"),
        is_error: raw.get("isError") == Some(&Value::Bool(true)),
        layer: str_of(raw, "layer"),
        tags: key_values(raw.get("tags")),
        logs: objects(raw.get("logs"))
            .map(|l| NativeSpanLog {
                time: i64_of(l, "time", 0),
                data: key_values(l.get("data")),
            })
            .collect(),
    }
}

/// Decodes a list of native spans into a [`NativeTrace`]; `None` when empty.
pub fn native_trace_from_spans(raw: Option<&Value>) -> Option<NativeTrace> {
    let spans: Vec<NativeSpan> = raw
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .map(decode_native_span)
        .collect();
    let trace_id = spans.first()?.trace_id.clone();
    Some(NativeTrace { trace_id, spans })
}

/// Decodes a topology result.
pub fn decode_topology(raw: &Value) -> Topology {
    Topology {
        nodes: objects(raw.get("nodes"))
            .map(|n| TopologyNode {
                id: str_of(n, "id"),
                name: str_of(n, "name"),
                node_type: str_of(n, "type"),
                is_real: n.get("isReal") == Some(&Value::Bool(true)),
                layers: strings(n.get("layers")),
            })
            .collect(),
        calls: objects(raw.get("calls"))
            .map(|c| TopologyCall {
                id: str_of(c, "id"),
                source: str_of(c, "source"),
                target: str_of(c, "target"),
                detect_points: strings(c.get("detectPoints")),
                source_components: strings(c.get("sourceComponents")),
                target_components: strings(c.get("targetComponents")),
            })
            .collect(),
    }
}

/// Composite SPEC span id of a native span: `<segmentId>-<spanId>`.
pub fn native_span_key(segment_id: &str, span_id: i64) -> String {
    format!("{segment_id}-{span_id}")
}

fn kind_of(span_type: &str) -> SpanKind {
    match span_type {
        "Entry" => SpanKind::Server,
        "Exit" => SpanKind::Client,
        "Local" => SpanKind::Internal,
        _ => SpanKind::Unspecified,
    }
}

fn ms_to_micros(ms: i64) -> u64 {
    (ms.max(0) as u64).saturating_mul(1000)
}

/// Converts a native span into the SPEC §5 model. Ids are kept verbatim: `span_id =
/// "<segmentId>-<spanId>"`; the parent is the same segment's `parentSpanId` when ≥ 0, else
/// `refs[0]` (`"<parentSegmentId>-<parentSpanId>"`); further refs become links. `kind`: Entry →
/// server, Exit → client, Local → internal. Times ms → µs. Status `error` when `isError`.
/// Attributes = tags plus `sw.component`, `sw.layer`, `sw.peer`, `sw.service_instance` (when
/// set). Span logs become events (name = the `event` data value, else `log`).
pub fn native_span_to_span(span: &NativeSpan) -> Span {
    let mut attributes = Attributes::new();
    for kv in &span.tags {
        attributes.insert(kv.key.clone(), AttrValue::String(kv.value.clone()));
    }
    for (key, value) in [
        ("sw.component", &span.component),
        ("sw.layer", &span.layer),
        ("sw.peer", &span.peer),
        ("sw.service_instance", &span.service_instance_name),
    ] {
        if !value.is_empty() {
            attributes.insert(key.to_owned(), AttrValue::String(value.clone()));
        }
    }
    let start = ms_to_micros(span.start_time);
    let end = ms_to_micros(span.end_time.max(span.start_time));
    let parent_span_id = if span.parent_span_id >= 0 {
        Some(native_span_key(&span.segment_id, span.parent_span_id))
    } else {
        span.refs
            .first()
            .map(|r| native_span_key(&r.parent_segment_id, r.parent_span_id))
    };
    let skip = usize::from(span.parent_span_id < 0);
    Span {
        trace_id: span.trace_id.clone(),
        span_id: native_span_key(&span.segment_id, span.span_id),
        parent_span_id,
        name: span.endpoint_name.clone(),
        kind: kind_of(&span.span_type),
        service_name: span.service_code.clone(),
        start_micros: start,
        end_micros: end,
        duration_micros: end - start,
        status: SpanStatus {
            code: if span.is_error {
                StatusCode::Error
            } else {
                StatusCode::Unset
            },
            message: None,
        },
        attributes,
        events: span
            .logs
            .iter()
            .map(|log| {
                let attributes: Attributes = log
                    .data
                    .iter()
                    .map(|kv| (kv.key.clone(), AttrValue::String(kv.value.clone())))
                    .collect();
                let name = log
                    .data
                    .iter()
                    .find(|kv| kv.key == "event")
                    .map(|kv| kv.value.clone())
                    .unwrap_or_else(|| "log".to_owned());
                SpanEvent {
                    name,
                    time_micros: ms_to_micros(log.time),
                    attributes,
                }
            })
            .collect(),
        links: span
            .refs
            .iter()
            .skip(skip)
            .map(|r| SpanLink {
                trace_id: r.trace_id.clone(),
                span_id: native_span_key(&r.parent_segment_id, r.parent_span_id),
                attributes: [(
                    "sw.ref_type".to_owned(),
                    AttrValue::String(r.ref_type.clone()),
                )]
                .into_iter()
                .collect(),
            })
            .collect(),
    }
}

/// Converts a native trace into a SPEC [`Trace`] (spans sorted); `None` when empty.
pub fn native_trace_to_trace(trace: &NativeTrace) -> Option<Trace> {
    if trace.spans.is_empty() {
        return None;
    }
    let mut spans: Vec<Span> = trace.spans.iter().map(native_span_to_span).collect();
    sort_spans(&mut spans);
    Some(Trace {
        trace_id: trace.trace_id.clone(),
        spans,
    })
}

/// Topology → dependency links (`parent` = source node name, `child` = target node name,
/// `call_count` = 0 because the topology carries no call counts). Sorted by parent, child.
pub fn topology_to_dependencies(topology: &Topology) -> Vec<DependencyLink> {
    let name = |id: &str| {
        topology
            .nodes
            .iter()
            .find(|n| n.id == id)
            .map(|n| n.name.clone())
            .unwrap_or_else(|| id.to_owned())
    };
    let mut links: Vec<DependencyLink> = topology
        .calls
        .iter()
        .map(|c| DependencyLink {
            parent: name(&c.source),
            child: name(&c.target),
            call_count: 0,
        })
        .collect();
    links.sort_by(|a, b| a.parent.cmp(&b.parent).then_with(|| a.child.cmp(&b.child)));
    links
}
