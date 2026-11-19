// Service code (Apache SkyWalking): the shared OAP access layer.
//! GraphQL envelope types, GraphQL error mapping and the shared OAP access (GraphQL, Zipkin
//! query API) used by every module of the client.

use std::sync::Mutex;
use std::time::SystemTime;

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::config::SkyWalkingConfig;
use crate::core::redact::truncate;
use crate::core::transport::MAX_VENDOR_MESSAGE;
use crate::core::{Error, HttpTransport, Response, Result};
use crate::duration::{
    parse_timezone, resolve_range, to_graphql_duration, GraphQLDuration, TimeRange,
};

/// One entry of a GraphQL `errors` array.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct GraphQLErrorInfo {
    /// Error text.
    pub message: String,
    /// Field path the error belongs to (resolver errors); empty otherwise.
    pub path: Vec<Value>,
    /// graphql-java classification (`ValidationError`, `DataFetchingException`, …).
    pub classification: Option<String>,
}

/// Result of a raw GraphQL call: partial `data` is possible next to `errors`.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct GraphQLResponse {
    /// `data` object (`None` when absent or not an object).
    pub data: Option<Value>,
    /// `errors` array (empty when none).
    pub errors: Vec<GraphQLErrorInfo>,
}

impl GraphQLResponse {
    /// `data[field]`, when present and not `null`.
    pub fn field(&self, field: &str) -> Option<&Value> {
        self.data
            .as_ref()
            .and_then(|d| d.get(field))
            .filter(|v| !v.is_null())
    }
}

/// Server features probed once per client (GraphQL `hasQueryTracesV2Support`,
/// `supportQueryLogsByKeywords`).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct SkyWalkingCapabilities {
    /// `queryTraces` (V2 trace query) is available.
    pub query_traces_v2: bool,
    /// The log store honours `keywordsOfContent` (false on BanyanDB).
    pub logs_by_keywords: bool,
}

/// OAP clock and timezone (`getTimeInfo`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TimeInfo {
    /// Timezone offset as reported, e.g. `+0000`.
    pub timezone: String,
    /// Server time, epoch milliseconds.
    pub current_timestamp: i64,
}

/// Decodes the `errors` array of a GraphQL response body.
pub fn graphql_errors(body: &Value) -> Vec<GraphQLErrorInfo> {
    body.get("errors")
        .and_then(Value::as_array)
        .map(|errors| {
            errors
                .iter()
                .filter(|e| e.is_object())
                .map(|e| GraphQLErrorInfo {
                    message: e
                        .get("message")
                        .and_then(Value::as_str)
                        .unwrap_or("")
                        .to_owned(),
                    path: e
                        .get("path")
                        .and_then(Value::as_array)
                        .cloned()
                        .unwrap_or_default(),
                    classification: e
                        .get("extensions")
                        .and_then(|x| x.get("classification"))
                        .and_then(Value::as_str)
                        .map(str::to_owned),
                })
                .collect()
        })
        .unwrap_or_default()
}

/// Maps GraphQL errors to an SDK error: `UnsupportedOperationException` → `UNSUPPORTED`,
/// everything else → `VALIDATION` (bad input caught by the resolver). The message is passed
/// through `redact` and truncated to 300 characters.
pub fn graphql_error(
    operation: &str,
    errors: &[GraphQLErrorInfo],
    redact: impl Fn(&str) -> String,
) -> Error {
    let raw = errors
        .iter()
        .map(|e| e.message.as_str())
        .find(|m| !m.is_empty())
        .unwrap_or("GraphQL error");
    let collapsed = raw.split_whitespace().collect::<Vec<_>>().join(" ");
    let text = truncate(&redact(&collapsed), MAX_VENDOR_MESSAGE);
    if raw.contains("UnsupportedOperationException") {
        Error::unsupported(format!("{operation}: {text}"))
    } else {
        Error::validation(format!("{operation}: {text}"))
    }
}

fn is_mutation(query: &str) -> bool {
    let t = query.trim_start();
    t.starts_with("mutation")
        && !t[8..]
            .chars()
            .next()
            .is_some_and(|c| c.is_alphanumeric() || c == '_')
}

/// Shared OAP access of one client.
#[derive(Debug)]
pub(crate) struct Api {
    pub(crate) transport: HttpTransport,
    pub(crate) config: SkyWalkingConfig,
    offset: Mutex<Option<i32>>,
    capabilities: Mutex<Option<SkyWalkingCapabilities>>,
}

impl Api {
    pub(crate) fn new(config: SkyWalkingConfig) -> Result<Self> {
        let transport = HttpTransport::new(config.common())?;
        for secret in config.known_secrets() {
            transport.add_known_secret(&secret);
        }
        Ok(Api {
            transport,
            config,
            offset: Mutex::new(None),
            capabilities: Mutex::new(None),
        })
    }

    /// Raw GraphQL: `POST <url>/graphql`. HTTP errors fail (400 → `VALIDATION`); resolver errors
    /// come back in `errors` with HTTP 200. Queries are retried like idempotent requests,
    /// mutations are not.
    pub(crate) async fn graphql(
        &self,
        query: &str,
        variables: Value,
        operation: &str,
    ) -> Result<GraphQLResponse> {
        if query.trim().is_empty() {
            return Err(Error::validation(format!("{operation}: query is required")));
        }
        let variables = if variables.is_null() {
            json!({})
        } else {
            variables
        };
        let resp = self
            .transport
            .post(self.config.graphql_path())
            .operation(operation)
            .idempotent(!is_mutation(query))
            .json(&json!({ "query": query, "variables": variables }))
            .send()
            .await?;
        let body: Value = resp.json().map_err(|_| {
            Error::validation(format!("{operation}: the response is not GraphQL JSON"))
        })?;
        Ok(GraphQLResponse {
            data: body.get("data").filter(|d| d.is_object()).cloned(),
            errors: graphql_errors(&body),
        })
    }

    /// Runs a query and returns `data[field]` (`Null` when absent); fails on any error.
    pub(crate) async fn field(
        &self,
        query: &str,
        variables: Value,
        field: &str,
        operation: &str,
    ) -> Result<Value> {
        let res = self.graphql(query, variables, operation).await?;
        if !res.errors.is_empty() {
            return Err(graphql_error(operation, &res.errors, |t| {
                self.transport.redact(t)
            }));
        }
        Ok(res.field(field).cloned().unwrap_or(Value::Null))
    }

    /// `getTimeInfo`; also caches the timezone offset.
    pub(crate) async fn time_info(&self, operation: &str) -> Result<TimeInfo> {
        let info = self
            .field(
                "{getTimeInfo{timezone currentTimestamp}}",
                Value::Null,
                "getTimeInfo",
                operation,
            )
            .await?;
        let timezone = info
            .get("timezone")
            .and_then(Value::as_str)
            .unwrap_or("+0000")
            .to_owned();
        let current_timestamp = info
            .get("currentTimestamp")
            .and_then(Value::as_i64)
            .unwrap_or_else(|| crate::duration::epoch_ms(SystemTime::now()));
        let mut offset = self.offset.lock().unwrap_or_else(|e| e.into_inner());
        if offset.is_none() {
            *offset = Some(parse_timezone(&timezone));
        }
        Ok(TimeInfo {
            timezone,
            current_timestamp,
        })
    }

    /// OAP timezone offset in minutes (fetched once and cached).
    pub(crate) async fn offset_minutes(&self, operation: &str) -> Result<i32> {
        if let Some(v) = *self.offset.lock().unwrap_or_else(|e| e.into_inner()) {
            return Ok(v);
        }
        let info = self.time_info(operation).await?;
        Ok(parse_timezone(&info.timezone))
    }

    /// GraphQL `Duration` for a window, in the OAP timezone.
    pub(crate) async fn duration(
        &self,
        range: &TimeRange,
        operation: &str,
    ) -> Result<GraphQLDuration> {
        let resolved = resolve_range(range, SystemTime::now(), operation)?;
        Ok(to_graphql_duration(
            &resolved,
            self.offset_minutes(operation).await?,
        ))
    }

    /// Server feature probe (cached after the first success).
    pub(crate) async fn capabilities(&self, operation: &str) -> Result<SkyWalkingCapabilities> {
        if let Some(c) = *self.capabilities.lock().unwrap_or_else(|e| e.into_inner()) {
            return Ok(c);
        }
        let res = self
            .graphql(
                "{hasQueryTracesV2Support supportQueryLogsByKeywords}",
                Value::Null,
                operation,
            )
            .await?;
        let caps = SkyWalkingCapabilities {
            query_traces_v2: res.field("hasQueryTracesV2Support") == Some(&Value::Bool(true)),
            logs_by_keywords: res.field("supportQueryLogsByKeywords") == Some(&Value::Bool(true)),
        };
        *self.capabilities.lock().unwrap_or_else(|e| e.into_inner()) = Some(caps);
        Ok(caps)
    }

    /// GET on the Zipkin query API (`<zipkinUrl><path>`); `ok_404` turns 404 into a response.
    pub(crate) async fn zipkin_get(
        &self,
        path: &str,
        operation: &str,
        query: &[(&str, String)],
        ok_404: bool,
    ) -> Result<Response> {
        let mut req = self
            .transport
            .get(&format!("{}{path}", self.config.zipkin_url()))
            .operation(operation);
        for (k, v) in query {
            req = req.query(*k, v.clone());
        }
        if ok_404 {
            req = req.ok_status(404);
        }
        req.send().await
    }
}

#[cfg(test)]
mod tests {
    use super::is_mutation;

    #[test]
    fn mutation_detection() {
        assert!(is_mutation("mutation { x }"));
        assert!(is_mutation("  mutation($a:Int){x}"));
        assert!(!is_mutation("mutations"));
        assert!(!is_mutation("query{version}"));
        assert!(!is_mutation("{version}"));
    }
}
