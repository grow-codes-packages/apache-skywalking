// Service code (Apache SkyWalking): `SkyWalkingQueryClient`, the OAP GraphQL query API.
//! `SkyWalkingQueryClient` — the OAP GraphQL query API (`POST /graphql` on port 12800):
//! metadata, native traces, logs, topology and raw GraphQL. Services, instances and endpoints
//! are named by their names; the SDK derives the ids (`base64(name).1`).

use std::collections::BTreeMap;
use std::sync::Arc;

use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};

use crate::api::{Api, GraphQLResponse, SkyWalkingCapabilities, TimeInfo};
use crate::core::{Error, Result};
use crate::duration::{endpoint_id, instance_id, service_id, TimeRange};
use crate::native::{
    decode_topology, i64_of, key_values, native_trace_from_spans, objects, str_of, strings,
    KeyValue, NativeTrace, Topology, NATIVE_SPAN_FIELDS,
};

/// A service known to OAP (native agents, native logs, OTLP logs).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkyWalkingService {
    /// `base64(name) + ".1"` for real services.
    pub id: String,
    /// Name.
    pub name: String,
    /// Group.
    pub group: String,
    /// Short name.
    pub short_name: String,
    /// Layers (e.g. `GENERAL`).
    pub layers: Vec<String>,
    /// `false` for virtual (conjectured) services.
    pub normal: bool,
}

/// An endpoint (operation) of a service.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct SkyWalkingEndpoint {
    /// Endpoint id.
    pub id: String,
    /// Endpoint name.
    pub name: String,
}

/// An attribute of a service instance.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct InstanceAttribute {
    /// Name.
    pub name: String,
    /// Value.
    pub value: String,
}

/// A service instance.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkyWalkingInstance {
    /// Instance id.
    pub id: String,
    /// Instance name.
    pub name: String,
    /// Language reported by the agent.
    pub language: String,
    /// Instance UUID.
    #[serde(rename = "instanceUUID")]
    pub instance_uuid: String,
    /// Attributes.
    pub attributes: Vec<InstanceAttribute>,
}

/// 1-based page selection.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Paging {
    /// Page number (default 1).
    pub page_num: usize,
    /// Page size (default 20, at most 10000).
    pub page_size: usize,
}

impl Default for Paging {
    fn default() -> Self {
        Paging {
            page_num: 1,
            page_size: 20,
        }
    }
}

impl Paging {
    /// A page.
    pub fn new(page_num: usize, page_size: usize) -> Self {
        Paging {
            page_num,
            page_size,
        }
    }

    fn to_json(self, operation: &str) -> Result<Value> {
        if self.page_num < 1 {
            return Err(Error::validation(format!(
                "{operation}: pageNum must be a positive integer"
            )));
        }
        if self.page_size < 1 || self.page_size > 10_000 {
            return Err(Error::validation(format!(
                "{operation}: pageSize must be 1..10000"
            )));
        }
        Ok(json!({ "pageNum": self.page_num, "pageSize": self.page_size }))
    }
}

/// Trace state filter of [`SkyWalkingQueryClient::query_traces`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum TraceState {
    /// Every trace (default).
    #[default]
    All,
    /// Successful traces.
    Success,
    /// Traces with an error.
    Error,
}

impl TraceState {
    /// Wire value.
    pub fn as_str(self) -> &'static str {
        match self {
            TraceState::All => "ALL",
            TraceState::Success => "SUCCESS",
            TraceState::Error => "ERROR",
        }
    }
}

/// Order of [`SkyWalkingQueryClient::query_traces`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum TraceOrder {
    /// Newest first (default).
    #[default]
    ByStartTime,
    /// Longest first.
    ByDuration,
}

impl TraceOrder {
    /// Wire value.
    pub fn as_str(self) -> &'static str {
        match self {
            TraceOrder::ByStartTime => "BY_START_TIME",
            TraceOrder::ByDuration => "BY_DURATION",
        }
    }
}

/// Order of [`SkyWalkingQueryClient::query_logs`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum LogOrder {
    /// Newest first (default).
    #[default]
    Des,
    /// Oldest first.
    Asc,
}

impl LogOrder {
    /// Wire value.
    pub fn as_str(self) -> &'static str {
        match self {
            LogOrder::Des => "DES",
            LogOrder::Asc => "ASC",
        }
    }
}

/// Filter of [`SkyWalkingQueryClient::query_traces`] (native traces).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct QueryTracesOptions {
    /// Service name.
    pub service: Option<String>,
    /// Instance name (needs `service`).
    pub instance: Option<String>,
    /// Endpoint name (needs `service`).
    pub endpoint: Option<String>,
    /// Native trace id.
    pub trace_id: Option<String>,
    /// Trace state (default `ALL`).
    pub trace_state: TraceState,
    /// Minimum trace duration in ms.
    pub min_duration_ms: Option<u64>,
    /// Maximum trace duration in ms.
    pub max_duration_ms: Option<u64>,
    /// Tag matches; only keys in the server's `SW_SEARCHABLE_TAG_KEYS` can match.
    pub tags: BTreeMap<String, String>,
    /// Window (default: last hour; omitted when only `trace_id` is given).
    pub range: Option<TimeRange>,
    /// Page (default 1 × 20).
    pub paging: Paging,
    /// Order (default `BY_START_TIME`).
    pub order: TraceOrder,
}

impl QueryTracesOptions {
    /// Filter on a service.
    pub fn service(service: impl Into<String>) -> Self {
        QueryTracesOptions {
            service: Some(service.into()),
            ..Default::default()
        }
    }
    /// Adds a tag match.
    pub fn tag(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.tags.insert(key.into(), value.into());
        self
    }
    /// Sets the window.
    pub fn range(mut self, range: TimeRange) -> Self {
        self.range = Some(range);
        self
    }
    /// Sets the trace state.
    pub fn trace_state(mut self, state: TraceState) -> Self {
        self.trace_state = state;
        self
    }
}

/// Result of [`SkyWalkingQueryClient::query_traces`].
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QueryTracesResult {
    /// Whole traces (every segment) that match.
    pub traces: Vec<NativeTrace>,
    /// Window the server searched, epoch ms `(start, end)`, when reported.
    pub retrieved_time_range: Option<(i64, i64)>,
}

/// A log entry as stored by OAP.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeLog {
    /// Service name.
    pub service_name: String,
    /// Service id.
    pub service_id: String,
    /// Instance name.
    pub service_instance_name: String,
    /// Endpoint name.
    pub endpoint_name: String,
    /// Native or hex trace id; `""` when the log has no trace context.
    pub trace_id: String,
    /// Epoch milliseconds.
    pub timestamp: i64,
    /// `TEXT`, `JSON` or `YAML`.
    pub content_type: String,
    /// Content.
    pub content: String,
    /// Tags.
    pub tags: Vec<KeyValue>,
}

impl NativeLog {
    /// Value of the first tag named `key`.
    pub fn tag(&self, key: &str) -> Option<&str> {
        self.tags
            .iter()
            .find(|t| t.key == key)
            .map(|t| t.value.as_str())
    }
}

/// Filter of [`SkyWalkingQueryClient::query_logs`].
#[derive(Debug, Clone, Default, PartialEq)]
pub struct QueryLogsOptions {
    /// Service name.
    pub service: Option<String>,
    /// Instance name (needs `service`).
    pub instance: Option<String>,
    /// Endpoint name (needs `service`).
    pub endpoint: Option<String>,
    /// Logs related to this trace (native or hex id).
    pub trace_id: Option<String>,
    /// With `trace_id`: the segment.
    pub segment_id: Option<String>,
    /// With `segment_id`: the span.
    pub span_id: Option<i64>,
    /// Words the content must contain. Sent to the server when it supports keyword search
    /// (Elasticsearch storage); on BanyanDB the returned page is filtered client side
    /// (case-insensitive substring).
    pub keywords: Vec<String>,
    /// Words the content must not contain (same rules as `keywords`).
    pub excluding_keywords: Vec<String>,
    /// Tag matches; only keys in `SW_SEARCHABLE_LOGS_TAG_KEYS` can match.
    pub tags: BTreeMap<String, String>,
    /// Window (default last hour; omitted when only `trace_id` is given).
    pub range: Option<TimeRange>,
    /// Page (default 1 × 20).
    pub paging: Paging,
    /// Order (default `DES`, newest first).
    pub order: LogOrder,
}

impl QueryLogsOptions {
    /// Filter on a service.
    pub fn service(service: impl Into<String>) -> Self {
        QueryLogsOptions {
            service: Some(service.into()),
            ..Default::default()
        }
    }
    /// Filter on a related trace.
    pub fn trace_id(trace_id: impl Into<String>) -> Self {
        QueryLogsOptions {
            trace_id: Some(trace_id.into()),
            ..Default::default()
        }
    }
    /// Adds a tag match.
    pub fn tag(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.tags.insert(key.into(), value.into());
        self
    }
    /// Sets the window.
    pub fn range(mut self, range: TimeRange) -> Self {
        self.range = Some(range);
        self
    }
}

/// Result of [`SkyWalkingQueryClient::query_logs`].
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QueryLogsResult {
    /// Logs of the page (after client-side keyword filtering).
    pub logs: Vec<NativeLog>,
    /// Server-side explanation when the query could not run as asked.
    pub error_reason: Option<String>,
}

/// Options of [`SkyWalkingQueryClient::topology`].
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TopologyOptions {
    /// Only this service's neighbourhood (`getServiceTopology`).
    pub service: Option<String>,
    /// Only this layer (global topology).
    pub layer: Option<String>,
}

const SERVICE_FIELDS: &str = "id name group shortName layers normal";
const LOG_FIELDS: &str = "serviceName serviceId serviceInstanceName endpointName traceId timestamp contentType content tags{key value}";
const TOPOLOGY_FIELDS: &str = "nodes{id name type isReal layers} calls{id source target detectPoints sourceComponents targetComponents}";

fn decode_service(s: &Value) -> SkyWalkingService {
    SkyWalkingService {
        id: str_of(s, "id"),
        name: str_of(s, "name"),
        group: str_of(s, "group"),
        short_name: str_of(s, "shortName"),
        layers: strings(s.get("layers")),
        normal: s.get("normal") != Some(&Value::Bool(false)),
    }
}

/// Decodes one native log.
pub fn decode_native_log(l: &Value) -> NativeLog {
    NativeLog {
        service_name: str_of(l, "serviceName"),
        service_id: str_of(l, "serviceId"),
        service_instance_name: str_of(l, "serviceInstanceName"),
        endpoint_name: str_of(l, "endpointName"),
        trace_id: str_of(l, "traceId"),
        timestamp: i64_of(l, "timestamp", 0),
        content_type: str_of(l, "contentType"),
        content: str_of(l, "content"),
        tags: key_values(l.get("tags")),
    }
}

fn required<'a>(value: &'a str, what: &str, operation: &str) -> Result<&'a str> {
    if value.trim().is_empty() {
        return Err(Error::validation(format!(
            "{operation}: {what} is required"
        )));
    }
    Ok(value)
}

fn tag_list(tags: &BTreeMap<String, String>) -> Option<Value> {
    (!tags.is_empty()).then(|| {
        Value::Array(
            tags.iter()
                .map(|(k, v)| json!({ "key": k, "value": v }))
                .collect(),
        )
    })
}

fn scoped_ids(
    service: Option<&str>,
    instance: Option<&str>,
    endpoint: Option<&str>,
    operation: &str,
    out: &mut Map<String, Value>,
) -> Result<()> {
    let Some(service) = service else {
        if instance.is_some() || endpoint.is_some() {
            return Err(Error::validation(format!(
                "{operation}: instance and endpoint need service"
            )));
        }
        return Ok(());
    };
    out.insert(
        "serviceId".into(),
        service_id(required(service, "service", operation)?).into(),
    );
    if let Some(i) = instance {
        out.insert("serviceInstanceId".into(), instance_id(service, i).into());
    }
    if let Some(e) = endpoint {
        out.insert("endpointId".into(), endpoint_id(service, e).into());
    }
    Ok(())
}

fn contains_all(text: &str, words: &[String]) -> bool {
    let lower = text.to_lowercase();
    words.iter().all(|w| lower.contains(&w.to_lowercase()))
}

fn contains_none(text: &str, words: &[String]) -> bool {
    let lower = text.to_lowercase();
    words.iter().all(|w| !lower.contains(&w.to_lowercase()))
}

/// The SkyWalking GraphQL query API (`POST /graphql` on port 12800). Obtain it from
/// [`SkyWalkingClient::query`](crate::SkyWalkingClient::query). Cheap to clone.
#[derive(Debug, Clone)]
pub struct SkyWalkingQueryClient {
    api: Arc<Api>,
}

impl SkyWalkingQueryClient {
    pub(crate) fn new(api: Arc<Api>) -> Self {
        SkyWalkingQueryClient { api }
    }

    /// Raw GraphQL: returns `{data, errors}` (partial data possible). HTTP 400 (syntax or
    /// validation errors) fails with `VALIDATION`.
    pub async fn graphql(&self, query: &str, variables: Value) -> Result<GraphQLResponse> {
        self.api.graphql(query, variables, "graphql").await
    }

    /// OAP clock and timezone: `getTimeInfo`.
    pub async fn time_info(&self) -> Result<TimeInfo> {
        self.api.time_info("query.timeInfo").await
    }

    /// Server features (`hasQueryTracesV2Support`, `supportQueryLogsByKeywords`), cached.
    pub async fn capabilities(&self) -> Result<SkyWalkingCapabilities> {
        self.api.capabilities("query.capabilities").await
    }

    /// Services: `listServices(layer)`. `None` = all layers; an unknown layer → empty.
    /// OAP has no time filter for services, so `range` is accepted for API symmetry and ignored.
    /// Services that only sent OTLP **traces** are not listed (use the trace query client).
    pub async fn list_services(
        &self,
        layer: Option<&str>,
        range: Option<TimeRange>,
    ) -> Result<Vec<SkyWalkingService>> {
        let _ = range;
        let rows = self
            .api
            .field(
                &format!("query($l:String){{listServices(layer:$l){{{SERVICE_FIELDS}}}}}"),
                json!({ "l": layer }),
                "listServices",
                "query.listServices",
            )
            .await?;
        Ok(objects(Some(&rows)).map(decode_service).collect())
    }

    /// One service by name: `findService`; `None` when unknown.
    pub async fn find_service(&self, name: &str) -> Result<Option<SkyWalkingService>> {
        let operation = "query.findService";
        let row = self
            .api
            .field(
                &format!("query($n:String!){{findService(serviceName:$n){{{SERVICE_FIELDS}}}}}"),
                json!({ "n": required(name, "name", operation)? }),
                "findService",
                operation,
            )
            .await?;
        Ok(row.is_object().then(|| decode_service(&row)))
    }

    /// Endpoints of a service: `findEndpoint(keyword, serviceId, limit)` (default limit 100).
    /// `keyword` is a tokenised match (`downstream` finds `/native/downstream`, `down` does
    /// not). No duration is sent (OAP 10.4 + BanyanDB returns nothing with one).
    pub async fn list_endpoints(
        &self,
        service: &str,
        keyword: Option<&str>,
        limit: Option<usize>,
    ) -> Result<Vec<SkyWalkingEndpoint>> {
        let operation = "query.listEndpoints";
        let limit = limit.unwrap_or(100);
        if limit < 1 {
            return Err(Error::validation(format!(
                "{operation}: limit must be a positive integer"
            )));
        }
        let rows = self
            .api
            .field(
                "query($k:String,$s:ID!,$l:Int!){findEndpoint(keyword:$k,serviceId:$s,limit:$l){id name}}",
                json!({
                    "k": keyword.filter(|k| !k.is_empty()),
                    "s": service_id(required(service, "service", operation)?),
                    "l": limit,
                }),
                "findEndpoint",
                operation,
            )
            .await?;
        Ok(objects(Some(&rows))
            .map(|e| SkyWalkingEndpoint {
                id: str_of(e, "id"),
                name: str_of(e, "name"),
            })
            .collect())
    }

    /// Instances of a service active in `range` (default last hour): `listInstances`.
    pub async fn list_instances(
        &self,
        service: &str,
        range: Option<TimeRange>,
    ) -> Result<Vec<SkyWalkingInstance>> {
        let operation = "query.listInstances";
        let id = service_id(required(service, "service", operation)?);
        let duration = self
            .api
            .duration(&range.unwrap_or_default(), operation)
            .await?;
        let rows = self
            .api
            .field(
                "query($s:ID!,$d:Duration!){listInstances(serviceId:$s,duration:$d){id name language instanceUUID attributes{name value}}}",
                json!({ "s": id, "d": duration }),
                "listInstances",
                operation,
            )
            .await?;
        Ok(objects(Some(&rows))
            .map(|i| SkyWalkingInstance {
                id: str_of(i, "id"),
                name: str_of(i, "name"),
                language: str_of(i, "language"),
                instance_uuid: str_of(i, "instanceUUID"),
                attributes: objects(i.get("attributes"))
                    .map(|a| InstanceAttribute {
                        name: str_of(a, "name"),
                        value: str_of(a, "value"),
                    })
                    .collect(),
            })
            .collect())
    }

    /// Native traces: `queryTraces(condition)` (whole traces). Durations in ms; tags must be
    /// searchable keys. The legacy `queryBasicTraces` is not used (unsupported on BanyanDB).
    pub async fn query_traces(&self, opts: &QueryTracesOptions) -> Result<QueryTracesResult> {
        let operation = "query.queryTraces";
        let mut c = Map::new();
        scoped_ids(
            opts.service.as_deref(),
            opts.instance.as_deref(),
            opts.endpoint.as_deref(),
            operation,
            &mut c,
        )?;
        c.insert("traceState".into(), opts.trace_state.as_str().into());
        c.insert("queryOrder".into(), opts.order.as_str().into());
        c.insert("paging".into(), opts.paging.to_json(operation)?);
        if let Some(v) = opts.min_duration_ms {
            c.insert("minTraceDuration".into(), v.into());
        }
        if let Some(v) = opts.max_duration_ms {
            c.insert("maxTraceDuration".into(), v.into());
        }
        if let Some(id) = &opts.trace_id {
            c.insert("traceId".into(), required(id, "traceId", operation)?.into());
        }
        if let Some(tags) = tag_list(&opts.tags) {
            c.insert("tags".into(), tags);
        }
        if opts.trace_id.is_none() || opts.range.is_some() {
            let d = self
                .api
                .duration(&opts.range.unwrap_or_default(), operation)
                .await?;
            c.insert("queryDuration".into(), serde_json::to_value(d)?);
        }
        let res = self
            .api
            .field(
                &format!("query($c:TraceQueryCondition){{queryTraces(condition:$c){{traces{{spans{{{NATIVE_SPAN_FIELDS}}}}} retrievedTimeRange{{startTime endTime}}}}}}"),
                json!({ "c": c }),
                "queryTraces",
                operation,
            )
            .await?;
        let traces = objects(res.get("traces"))
            .filter_map(|t| native_trace_from_spans(t.get("spans")))
            .collect();
        let retrieved_time_range = res
            .get("retrievedTimeRange")
            .and_then(|r| Some((r.get("startTime")?.as_i64()?, r.get("endTime")?.as_i64()?)));
        Ok(QueryTracesResult {
            traces,
            retrieved_time_range,
        })
    }

    /// One native trace: `queryTrace(traceId)`; `None` when unknown (OTLP trace ids are never
    /// native).
    pub async fn get_trace(&self, trace_id: &str) -> Result<Option<NativeTrace>> {
        let operation = "query.getTrace";
        let res = self
            .api
            .field(
                &format!(
                    "query($id:ID!){{queryTrace(traceId:$id){{spans{{{NATIVE_SPAN_FIELDS}}}}}}}"
                ),
                json!({ "id": required(trace_id, "traceId", operation)? }),
                "queryTrace",
                operation,
            )
            .await?;
        Ok(native_trace_from_spans(res.get("spans")))
    }

    /// Logs: `queryLogs(condition)`. Either a window (default last hour) or `trace_id` is sent;
    /// see [`QueryLogsOptions::keywords`] for keyword handling on BanyanDB.
    pub async fn query_logs(&self, opts: &QueryLogsOptions) -> Result<QueryLogsResult> {
        let operation = "query.queryLogs";
        let mut c = Map::new();
        scoped_ids(
            opts.service.as_deref(),
            opts.instance.as_deref(),
            opts.endpoint.as_deref(),
            operation,
            &mut c,
        )?;
        c.insert("paging".into(), opts.paging.to_json(operation)?);
        c.insert("queryOrder".into(), opts.order.as_str().into());
        if let Some(id) = &opts.trace_id {
            let mut related = Map::new();
            related.insert("traceId".into(), required(id, "traceId", operation)?.into());
            if let Some(s) = &opts.segment_id {
                related.insert("segmentId".into(), s.clone().into());
            }
            if let Some(s) = opts.span_id {
                related.insert("spanId".into(), s.into());
            }
            c.insert("relatedTrace".into(), related.into());
        }
        if let Some(tags) = tag_list(&opts.tags) {
            c.insert("tags".into(), tags);
        }
        let mut server_keywords = false;
        if !opts.keywords.is_empty() || !opts.excluding_keywords.is_empty() {
            server_keywords = self.api.capabilities(operation).await?.logs_by_keywords;
            if server_keywords {
                if !opts.keywords.is_empty() {
                    c.insert("keywordsOfContent".into(), json!(opts.keywords));
                }
                if !opts.excluding_keywords.is_empty() {
                    c.insert(
                        "excludingKeywordsOfContent".into(),
                        json!(opts.excluding_keywords),
                    );
                }
            }
        }
        if opts.trace_id.is_none() || opts.range.is_some() {
            let d = self
                .api
                .duration(&opts.range.unwrap_or_default(), operation)
                .await?;
            c.insert("queryDuration".into(), serde_json::to_value(d)?);
        }
        let res = self
            .api
            .field(
                &format!("query($c:LogQueryCondition){{queryLogs(condition:$c){{errorReason logs{{{LOG_FIELDS}}}}}}}"),
                json!({ "c": c }),
                "queryLogs",
                operation,
            )
            .await?;
        let mut logs: Vec<NativeLog> = objects(res.get("logs")).map(decode_native_log).collect();
        if !server_keywords {
            logs.retain(|l| {
                contains_all(&l.content, &opts.keywords)
                    && contains_none(&l.content, &opts.excluding_keywords)
            });
        }
        Ok(QueryLogsResult {
            logs,
            error_reason: res
                .get("errorReason")
                .and_then(Value::as_str)
                .filter(|s| !s.is_empty())
                .map(str::to_owned),
        })
    }

    /// Log tag keys seen in `range` (default last hour): `queryLogTagAutocompleteKeys`, sorted.
    pub async fn list_log_tag_keys(&self, range: Option<TimeRange>) -> Result<Vec<String>> {
        let operation = "query.listLogTagKeys";
        let d = self
            .api
            .duration(&range.unwrap_or_default(), operation)
            .await?;
        let keys = self
            .api
            .field(
                "query($d:Duration!){queryLogTagAutocompleteKeys(duration:$d)}",
                json!({ "d": d }),
                "queryLogTagAutocompleteKeys",
                operation,
            )
            .await?;
        let mut out = strings(Some(&keys));
        out.sort();
        Ok(out)
    }

    /// Service topology in `range` (default last hour): `getGlobalTopology(duration, layer)`,
    /// or `getServiceTopology` when `opts.service` is set. Native traffic only (OTLP spans
    /// create no edges); includes the virtual `User` node.
    pub async fn topology(
        &self,
        range: Option<TimeRange>,
        opts: &TopologyOptions,
    ) -> Result<Topology> {
        let operation = "query.topology";
        let d = self
            .api
            .duration(&range.unwrap_or_default(), operation)
            .await?;
        let res = match &opts.service {
            Some(service) => {
                self.api
                    .field(
                        &format!("query($s:ID!,$d:Duration!){{getServiceTopology(serviceId:$s,duration:$d){{{TOPOLOGY_FIELDS}}}}}"),
                        json!({ "s": service_id(required(service, "service", operation)?), "d": d }),
                        "getServiceTopology",
                        operation,
                    )
                    .await?
            }
            None => {
                self.api
                    .field(
                        &format!("query($d:Duration!,$l:String){{getGlobalTopology(duration:$d,layer:$l){{{TOPOLOGY_FIELDS}}}}}"),
                        json!({ "d": d, "l": opts.layer }),
                        "getGlobalTopology",
                        operation,
                    )
                    .await?
            }
        };
        Ok(decode_topology(&res))
    }
}
