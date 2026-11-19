// Service code (Apache SkyWalking): native log exporter and the LogQueryClient.
//! Logging for SkyWalking: the native log exporter (`POST /v3/logs`, carries the trace context)
//! and the SPEC §4 `LogQueryClient` over GraphQL `queryLogs` with client-side text filtering
//! (OAP on BanyanDB ignores content keywords).

use std::collections::BTreeMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, LazyLock};
use std::time::SystemTime;

use async_trait::async_trait;
use regex::Regex;
use serde_json::{json, Value};

use crate::api::Api;
use crate::core::{Error, Result};
use crate::duration::{epoch_ms, from_epoch_ms, Step, TimeRange, DEFAULT_RANGE};
use crate::logging::{
    poll_tail, AttrValue, Attributes, ExportOptions, ExportResult, LogExporter, LogQueryClient,
    LogRecord, LogSearchResult, RecordCallback, SearchOptions, Severity, SortOrder, TailHandle,
    TailOptions,
};
use crate::query::{LogOrder, NativeLog, Paging, QueryLogsOptions, SkyWalkingQueryClient};

/// Service name used when a record has no `service.name` resource attribute.
pub const DEFAULT_LOG_SERVICE_NAME: &str = "unknown_service";
/// Raw page size used while filtering free text client side.
pub const LOG_SCAN_PAGE_SIZE: usize = 1000;
/// Default number of raw pages one `search` call may scan.
pub const DEFAULT_MAX_SCAN_PAGES: usize = 10;

fn is_lower_hex(s: &str, len: usize) -> bool {
    s.len() == len && s.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

/// Converts an attribute value to SkyWalking's string tag form (JSON for arrays/objects).
pub fn tag_text(value: &AttrValue) -> String {
    match value {
        AttrValue::String(s) => s.clone(),
        other => other.to_string(),
    }
}

/// Maps a SPEC [`LogRecord`] to native `LogData` JSON: `resource["service.name"]` →
/// `service`, `service.instance.id` → `serviceInstance`, body → `body.text.text`,
/// `severity_text` → tag `level`, attributes (stringified, nested values as JSON) → tags,
/// `trace_id` → `traceContext.traceId` (enables `relatedTrace` queries), `span_id` → tag
/// `span_id`.
pub fn record_to_log_data(record: &LogRecord, default_service: &str) -> Value {
    let level = if record.severity_text.is_empty() {
        record.severity.text().to_owned()
    } else {
        record.severity_text.clone()
    };
    let mut tags = vec![json!({ "key": "level", "value": level })];
    for (key, value) in &record.attributes {
        if key != "level" {
            tags.push(json!({ "key": key, "value": tag_text(value) }));
        }
    }
    if let Some(span) = record.span_id.as_deref().filter(|s| !s.is_empty()) {
        tags.push(json!({ "key": "span_id", "value": span }));
    }
    let mut out = json!({
        "timestamp": epoch_ms(record.timestamp),
        "service": record
            .resource
            .get("service.name")
            .map(tag_text)
            .unwrap_or_else(|| default_service.to_owned()),
        "body": { "text": { "text": record.body } },
        "tags": { "data": tags },
        "layer": "GENERAL",
    });
    if let Some(instance) = record.resource.get("service.instance.id") {
        out["serviceInstance"] = Value::String(tag_text(instance));
    }
    if let Some(trace) = record.trace_id.as_deref().filter(|s| !s.is_empty()) {
        out["traceContext"] = json!({ "traceId": trace });
    }
    out
}

/// Maps a stored log back to a SPEC [`LogRecord`]: tag `level` → severity / severity text
/// (default INFO), `span_id` → `span_id` (16 hex), a 32-hex trace id → `trace_id` (native trace
/// ids go to attribute `sw.trace_id`), other tags → attributes (strings), service/instance →
/// resource `service.name` / `service.instance.id`, endpoint → resource `sw.endpoint`.
pub fn log_to_record(log: &NativeLog) -> LogRecord {
    let mut attributes = Attributes::new();
    let mut level: Option<String> = None;
    let mut span_id: Option<String> = None;
    for kv in &log.tags {
        if kv.key == "level" {
            level = Some(kv.value.clone());
        } else if kv.key == "span_id" && is_lower_hex(&kv.value, 16) {
            span_id = Some(kv.value.clone());
        } else {
            attributes.insert(kv.key.clone(), AttrValue::String(kv.value.clone()));
        }
    }
    let mut resource = Attributes::new();
    resource.insert(
        "service.name".into(),
        AttrValue::String(log.service_name.clone()),
    );
    if !log.service_instance_name.is_empty() {
        resource.insert(
            "service.instance.id".into(),
            AttrValue::String(log.service_instance_name.clone()),
        );
    }
    if !log.endpoint_name.is_empty() {
        resource.insert(
            "sw.endpoint".into(),
            AttrValue::String(log.endpoint_name.clone()),
        );
    }
    let severity = level
        .as_deref()
        .and_then(|l| l.parse::<Severity>().ok())
        .unwrap_or(Severity::Info);
    let trace = log.trace_id.to_ascii_lowercase();
    let trace_id = if is_lower_hex(&trace, 32) {
        Some(trace)
    } else {
        if !log.trace_id.is_empty() {
            attributes.insert(
                "sw.trace_id".into(),
                AttrValue::String(log.trace_id.clone()),
            );
        }
        None
    };
    LogRecord {
        timestamp: from_epoch_ms(log.timestamp),
        observed_timestamp: None,
        severity,
        severity_text: level.unwrap_or_else(|| "INFO".to_owned()),
        body: log.content.clone(),
        attributes,
        resource,
        trace_id,
        span_id,
        trace_flags: None,
    }
}

/// [`LogExporter`] that posts batches to OAP's native log API (`POST /v3/logs`, JSON array,
/// port 12800). Native logs carry the trace context, register the service and are queryable a
/// few seconds later. The body is sent uncompressed (gzip is not verified on OAP's HTTP
/// receiver), whatever `ExportOptions::gzip` says.
#[derive(Debug)]
pub struct SkyWalkingLogExporter {
    api: Arc<Api>,
    service_name: String,
    owns_transport: bool,
    shutdown: AtomicBool,
}

impl SkyWalkingLogExporter {
    pub(crate) fn new(api: Arc<Api>, service_name: Option<String>, owns_transport: bool) -> Self {
        SkyWalkingLogExporter {
            api,
            service_name: service_name.unwrap_or_else(|| DEFAULT_LOG_SERVICE_NAME.to_owned()),
            owns_transport,
            shutdown: AtomicBool::new(false),
        }
    }

    /// Service name used for records without `service.name`.
    pub fn service_name(&self) -> &str {
        &self.service_name
    }
}

#[async_trait]
impl LogExporter for SkyWalkingLogExporter {
    async fn export(&self, records: &[LogRecord], _opts: ExportOptions) -> Result<ExportResult> {
        if records.is_empty() {
            return Ok(ExportResult::accepted(0));
        }
        let body: Vec<Value> = records
            .iter()
            .map(|r| record_to_log_data(r, &self.service_name))
            .collect();
        self.api
            .transport
            .post("/v3/logs")
            .operation("logging.export")
            .json(&body)
            .send()
            .await?;
        Ok(ExportResult::accepted(records.len()))
    }

    async fn shutdown(&self) -> Result<()> {
        if !self.shutdown.swap(true, Ordering::SeqCst) && self.owns_transport {
            self.api.transport.close();
        }
        Ok(())
    }
}

/// Parsed form of a log search query (see [`parse_log_query`]).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ParsedLogQuery {
    /// `service=`.
    pub service: Option<String>,
    /// `instance=`.
    pub instance: Option<String>,
    /// `endpoint=`.
    pub endpoint: Option<String>,
    /// `trace_id=` / `traceId=`.
    pub trace_id: Option<String>,
    /// Server-side tag filters (allow-listed keys only).
    pub tags: BTreeMap<String, String>,
    /// Free-text terms, matched client side.
    pub text: Vec<String>,
}

static TOKEN: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"([A-Za-z_][\w.-]*)=(?:"([^"]*)"|(\S+))|"([^"]*)"|(\S+)"#).expect("valid regex")
});

/// Parses the log query syntax: space-separated `key=value` terms (`service=`, `instance=`,
/// `endpoint=`, `trace_id=`/`traceId=` map to the GraphQL condition; any other key is a server
/// tag filter and must be in `SW_SEARCHABLE_LOGS_TAG_KEYS`) plus free-text terms (`"quoted
/// phrases"` allowed). Every free-text term must occur (case-insensitive) in the content or in
/// a tag value.
pub fn parse_log_query(query: &str) -> ParsedLogQuery {
    let mut out = ParsedLogQuery::default();
    for m in TOKEN.captures_iter(query) {
        if let Some(key) = m.get(1) {
            let value = m
                .get(2)
                .or_else(|| m.get(3))
                .map(|v| v.as_str().to_owned())
                .unwrap_or_default();
            match key.as_str() {
                "service" => out.service = Some(value),
                "instance" => out.instance = Some(value),
                "endpoint" => out.endpoint = Some(value),
                "trace_id" | "traceId" => out.trace_id = Some(value),
                other => {
                    out.tags.insert(other.to_owned(), value);
                }
            }
        } else {
            let text = m
                .get(4)
                .or_else(|| m.get(5))
                .map(|v| v.as_str())
                .unwrap_or_default();
            if !text.is_empty() {
                out.text.push(text.to_owned());
            }
        }
    }
    out
}

/// True when every term occurs (case-insensitive) in the log content or one of its tag values.
pub fn matches_text(log: &NativeLog, terms: &[String]) -> bool {
    if terms.is_empty() {
        return true;
    }
    let mut haystack = log.content.to_lowercase();
    for t in &log.tags {
        haystack.push('\n');
        haystack.push_str(&t.value.to_lowercase());
    }
    terms.iter().all(|t| haystack.contains(&t.to_lowercase()))
}

fn decode_cursor(cursor: &str) -> Result<(usize, usize, usize)> {
    let bad = || Error::validation("logging.search: invalid cursor");
    let parts: Vec<&str> = cursor.split(':').collect();
    if parts.len() != 3
        || parts
            .iter()
            .any(|p| p.is_empty() || !p.bytes().all(|b| b.is_ascii_digit()))
    {
        return Err(bad());
    }
    let n = |s: &str| s.parse::<usize>().map_err(|_| bad());
    let (page, offset, size) = (n(parts[0])?, n(parts[1])?, n(parts[2])?);
    if page < 1 || !(1..=10_000).contains(&size) {
        return Err(bad());
    }
    Ok((page, offset, size))
}

/// [`LogQueryClient`] for SkyWalking (GraphQL `queryLogs`). Free-text terms are filtered client
/// side: `search` reads raw pages of 1000 logs (newest first by default) and keeps the matching
/// ones until `limit` matches or `max_scan_pages` pages; `next_cursor` (`"page:offset:size"`)
/// continues where it stopped. Without free text the page size is `limit` and nothing is
/// filtered except the exact time window. Default window: the last hour. `tail` polls `search`.
/// Obtain it from [`SkyWalkingClient::logs`](crate::SkyWalkingClient::logs). Cheap to clone.
#[derive(Debug, Clone)]
pub struct SkyWalkingLogQueryClient {
    query: SkyWalkingQueryClient,
    max_scan_pages: usize,
}

impl SkyWalkingLogQueryClient {
    pub(crate) fn new(query: SkyWalkingQueryClient) -> Self {
        SkyWalkingLogQueryClient {
            query,
            max_scan_pages: DEFAULT_MAX_SCAN_PAGES,
        }
    }

    /// The same client with another raw-page budget per `search` call (default 10, ≥ 1).
    pub fn with_max_scan_pages(&self, pages: usize) -> Result<Self> {
        if pages < 1 {
            return Err(Error::validation(
                "logging: maxScanPages must be a positive integer",
            ));
        }
        Ok(SkyWalkingLogQueryClient {
            query: self.query.clone(),
            max_scan_pages: pages,
        })
    }
}

#[async_trait]
impl LogQueryClient for SkyWalkingLogQueryClient {
    async fn search(&self, query: &str, opts: SearchOptions) -> Result<LogSearchResult> {
        let operation = "logging.search";
        let limit = opts.limit;
        if !(1..=10_000).contains(&limit) {
            return Err(Error::validation(format!(
                "{operation}: limit must be 1..10000"
            )));
        }
        let parsed = parse_log_query(query);
        let end_ms = epoch_ms(opts.end.unwrap_or_else(SystemTime::now));
        let start_ms = opts
            .start
            .map(epoch_ms)
            .unwrap_or(end_ms - DEFAULT_RANGE.as_millis() as i64);
        if start_ms > end_ms {
            return Err(Error::validation(format!(
                "{operation}: invalid time window"
            )));
        }
        let (mut page, mut offset, size) = match &opts.cursor {
            Some(c) => decode_cursor(c)?,
            None => (
                1,
                0,
                if parsed.text.is_empty() {
                    limit
                } else {
                    LOG_SCAN_PAGE_SIZE
                },
            ),
        };
        let base = QueryLogsOptions {
            service: parsed.service.clone(),
            instance: parsed.instance.clone(),
            endpoint: parsed.endpoint.clone(),
            trace_id: parsed.trace_id.clone(),
            tags: parsed.tags.clone(),
            range: Some(TimeRange {
                start: Some(from_epoch_ms(start_ms)),
                end: Some(from_epoch_ms(end_ms)),
                step: Some(Step::Minute),
            }),
            order: match opts.order {
                SortOrder::Asc => LogOrder::Asc,
                SortOrder::Desc => LogOrder::Des,
            },
            ..Default::default()
        };
        let mut records = Vec::new();
        for _ in 0..self.max_scan_pages {
            let mut q = base.clone();
            q.paging = Paging::new(page, size);
            let logs = self.query.query_logs(&q).await?.logs;
            for (i, log) in logs.iter().enumerate().skip(offset) {
                if log.timestamp < start_ms
                    || log.timestamp > end_ms
                    || !matches_text(log, &parsed.text)
                {
                    continue;
                }
                records.push(log_to_record(log));
                if records.len() >= limit {
                    let next_cursor = if i + 1 < logs.len() {
                        Some(format!("{page}:{}:{size}", i + 1))
                    } else if logs.len() >= size {
                        Some(format!("{}:0:{size}", page + 1))
                    } else {
                        None
                    };
                    return Ok(LogSearchResult {
                        records,
                        next_cursor,
                        total: None,
                    });
                }
            }
            if logs.len() < size {
                return Ok(LogSearchResult {
                    records,
                    next_cursor: None,
                    total: None,
                });
            }
            page += 1;
            offset = 0;
        }
        Ok(LogSearchResult {
            records,
            next_cursor: Some(format!("{page}:0:{size}")),
            total: None,
        })
    }

    /// Polls `search` oldest-first (see [`poll_tail`]); SkyWalking has no streaming API.
    async fn tail(
        &self,
        query: &str,
        on_record: RecordCallback,
        opts: TailOptions,
    ) -> Result<TailHandle> {
        Ok(poll_tail(Arc::new(self.clone()), query, on_record, opts))
    }

    /// Service names known to OAP (SkyWalking has no streams; the service is the equivalent).
    async fn list_streams(&self) -> Result<Vec<String>> {
        let mut names: Vec<String> = self
            .query
            .list_services(None, None)
            .await?
            .into_iter()
            .filter(|s| s.normal)
            .map(|s| s.name)
            .collect();
        names.sort();
        names.dedup();
        Ok(names)
    }

    /// Log tag keys seen in the last hour (`queryLogTagAutocompleteKeys`).
    async fn list_labels(&self) -> Result<Vec<String>> {
        self.query.list_log_tag_keys(None).await
    }
}
