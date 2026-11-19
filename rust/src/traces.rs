// Service code (Apache SkyWalking): `SkyWalkingTraceQueryClient` (SPEC §5 TraceQueryClient).
//! `SkyWalkingTraceQueryClient` — the SPEC §5 `TraceQueryClient` over OAP's two trace stores:
//! the Zipkin model (spans sent over OTLP, read via `:9412/zipkin`, needs `SW_QUERY_ZIPKIN`) and
//! the native model (SkyWalking agent segments, read via GraphQL).

use std::collections::BTreeSet;
use std::sync::Arc;
use std::time::{Duration, SystemTime};

use async_trait::async_trait;

use crate::api::Api;
use crate::config::TraceBackend;
use crate::core::{Error, Result};
use crate::duration::{epoch_ms, from_epoch_ms, TimeRange, DEFAULT_RANGE};
use crate::native::{native_trace_to_trace, topology_to_dependencies};
use crate::query::{Paging, QueryTracesOptions, SkyWalkingQueryClient, TopologyOptions};
use crate::tracing::{
    normalize_trace_id_or_native, wait_for_trace_with, DependencyLink, DependencyOptions, Trace,
    TraceQueryClient, TraceSearchQuery, TraceSummary, WaitForTraceOptions,
};
use crate::zipkin::{
    is_hex_trace_id, strings_from_body, summarize, zipkin_spans_from_body, zipkin_trace_id,
    zipkin_trace_to_trace, zipkin_traces_from_body,
};

/// Default `limit` of `search_traces` (SPEC §5).
pub const DEFAULT_TRACE_SEARCH_LIMIT: usize = 20;

fn unique_sorted(values: impl IntoIterator<Item = String>) -> Vec<String> {
    values
        .into_iter()
        .filter(|v| !v.is_empty())
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect()
}

/// [`TraceQueryClient`] for SkyWalking. The backend comes from
/// [`SkyWalkingConfig::trace_backend`](crate::SkyWalkingConfig::trace_backend) (or
/// [`with_backend`](Self::with_backend)):
///
/// | method | `zipkin` | `native` | `auto` (default) |
/// | - | - | - | - |
/// | `get_trace` | Zipkin | GraphQL | hex id → Zipkin, then GraphQL; other ids → GraphQL |
/// | `search_traces` | Zipkin | GraphQL `queryTraces` | Zipkin |
/// | `list_services`, `list_operations` | Zipkin | GraphQL | union of both |
/// | `get_dependencies` | `UNSUPPORTED` | topology | topology |
///
/// Native traces keep their opaque ids (`<hex>.<n>.<ms>`); native span ids are
/// `"<segmentId>-<spanId>"`. Obtain it from
/// [`SkyWalkingClient::traces`](crate::SkyWalkingClient::traces). Cheap to clone.
#[derive(Debug, Clone)]
pub struct SkyWalkingTraceQueryClient {
    api: Arc<Api>,
    query: SkyWalkingQueryClient,
    backend: TraceBackend,
}

impl SkyWalkingTraceQueryClient {
    pub(crate) fn new(api: Arc<Api>, backend: TraceBackend) -> Self {
        SkyWalkingTraceQueryClient {
            query: SkyWalkingQueryClient::new(Arc::clone(&api)),
            api,
            backend,
        }
    }

    /// The configured backend.
    pub fn backend(&self) -> TraceBackend {
        self.backend
    }

    /// The same client reading another backend.
    pub fn with_backend(&self, backend: TraceBackend) -> Self {
        SkyWalkingTraceQueryClient {
            backend,
            ..self.clone()
        }
    }

    async fn zipkin_trace(&self, trace_id: &str) -> Result<Option<Trace>> {
        let operation = "traces.getTrace";
        let id = zipkin_trace_id(trace_id, operation)?;
        let resp = self
            .api
            .zipkin_get(&format!("/api/v2/trace/{id}"), operation, &[], true)
            .await?;
        if resp.status() == 404 {
            return Ok(None);
        }
        let body: serde_json::Value = resp.json()?;
        Ok(zipkin_trace_to_trace(&zipkin_spans_from_body(&body)))
    }

    async fn native_trace(&self, trace_id: &str) -> Result<Option<Trace>> {
        Ok(self
            .query
            .get_trace(trace_id)
            .await?
            .and_then(|t| native_trace_to_trace(&t)))
    }

    async fn zipkin_strings(
        &self,
        path: &str,
        operation: &str,
        query: &[(&str, String)],
    ) -> Result<Vec<String>> {
        let resp = self.api.zipkin_get(path, operation, query, false).await?;
        Ok(strings_from_body(&resp.json()?))
    }
}

#[async_trait]
impl TraceQueryClient for SkyWalkingTraceQueryClient {
    /// One trace in the SPEC model, `None` when it does not exist. The Zipkin backend rejects
    /// non-hex ids with `VALIDATION`.
    async fn get_trace(&self, trace_id: &str) -> Result<Option<Trace>> {
        if trace_id.trim().is_empty() {
            return Err(Error::validation("traces.getTrace: traceId is required"));
        }
        match self.backend {
            TraceBackend::Zipkin => self.zipkin_trace(trace_id).await,
            TraceBackend::Native => self.native_trace(trace_id.trim()).await,
            TraceBackend::Auto if !is_hex_trace_id(trace_id) => {
                self.native_trace(trace_id.trim()).await
            }
            TraceBackend::Auto => match self.zipkin_trace(trace_id).await? {
                Some(t) => Ok(Some(t)),
                None => {
                    self.native_trace(&trace_id.trim().to_ascii_lowercase())
                        .await
                }
            },
        }
    }

    /// SPEC search. Zipkin: `service` → `serviceName`, `operation` → `spanName` (lower-cased
    /// like OAP stores names), `tags` → `annotationQuery` (`k=v and …`; keys must be in
    /// `SW_ZIPKIN_SEARCHABLE_TAG_KEYS`), durations in µs, `endTs` = `end` (default now),
    /// `lookback` = `end - start`. Native: `queryTraces` with `operation` as endpoint name and
    /// durations in ms (default window the last hour).
    async fn search_traces(&self, query: &TraceSearchQuery) -> Result<Vec<TraceSummary>> {
        let operation = "traces.searchTraces";
        if query.limit == 0 {
            return Err(Error::validation(format!(
                "{operation}: limit must be a positive integer"
            )));
        }
        let end_ms = epoch_ms(query.end.unwrap_or_else(SystemTime::now));
        let start_ms = query.start.map(epoch_ms);
        if end_ms <= 0 || start_ms.is_some_and(|s| s <= 0) {
            return Err(Error::validation(format!(
                "{operation}: start and end must be valid times"
            )));
        }
        if start_ms.is_some_and(|s| s > end_ms) {
            return Err(Error::validation(format!(
                "{operation}: start must not be after end"
            )));
        }
        if self.backend == TraceBackend::Native {
            if query.operation.is_some() && query.service.is_none() {
                return Err(Error::validation(format!(
                    "{operation}: operation needs service on the native backend"
                )));
            }
            let start = start_ms.unwrap_or(end_ms - DEFAULT_RANGE.as_millis() as i64);
            let opts = QueryTracesOptions {
                service: query.service.clone(),
                endpoint: query.operation.clone(),
                tags: query.tags.clone(),
                min_duration_ms: query.min_duration.map(|d| d.as_millis() as u64),
                max_duration_ms: query
                    .max_duration
                    .map(|d| d.as_micros().div_ceil(1000) as u64),
                range: Some(TimeRange {
                    start: Some(from_epoch_ms(start)),
                    end: Some(from_epoch_ms(end_ms)),
                    step: None,
                }),
                paging: Paging::new(1, query.limit),
                ..Default::default()
            };
            let res = self.query.query_traces(&opts).await?;
            return Ok(res
                .traces
                .iter()
                .filter_map(native_trace_to_trace)
                .map(|t| summarize(&t))
                .collect());
        }
        let terms: Vec<String> = query.tags.iter().map(|(k, v)| format!("{k}={v}")).collect();
        if terms.iter().any(|t| t.contains(" and ")) {
            return Err(Error::validation(format!(
                "{operation}: tags must not contain \" and \""
            )));
        }
        let mut params: Vec<(&str, String)> = Vec::new();
        if let Some(s) = &query.service {
            params.push(("serviceName", s.clone()));
        }
        if let Some(o) = &query.operation {
            params.push(("spanName", o.to_lowercase()));
        }
        if !terms.is_empty() {
            params.push(("annotationQuery", terms.join(" and ")));
        }
        if let Some(d) = query.min_duration {
            params.push(("minDuration", d.as_micros().to_string()));
        }
        if let Some(d) = query.max_duration {
            params.push(("maxDuration", d.as_micros().to_string()));
        }
        params.push(("endTs", end_ms.to_string()));
        if let Some(s) = start_ms {
            params.push(("lookback", (end_ms - s).max(1).to_string()));
        }
        params.push(("limit", query.limit.to_string()));
        let resp = self
            .api
            .zipkin_get("/api/v2/traces", operation, &params, false)
            .await?;
        Ok(zipkin_traces_from_body(&resp.json()?)
            .iter()
            .filter_map(|t| zipkin_trace_to_trace(t))
            .map(|t| summarize(&t))
            .collect())
    }

    /// Service names (sorted, unique). New services appear about 30–35 s after their first
    /// data (OAP flushes its name index periodically).
    async fn list_services(&self) -> Result<Vec<String>> {
        let zipkin = async {
            if self.backend == TraceBackend::Native {
                return Ok(Vec::new());
            }
            self.zipkin_strings("/api/v2/services", "traces.listServices", &[])
                .await
        };
        let native = async {
            if self.backend == TraceBackend::Zipkin {
                return Ok(Vec::new());
            }
            Ok::<_, Error>(
                self.query
                    .list_services(None, None)
                    .await?
                    .into_iter()
                    .filter(|s| s.normal)
                    .map(|s| s.name)
                    .collect(),
            )
        };
        let (z, n) = futures::future::try_join(zipkin, native).await?;
        Ok(unique_sorted(z.into_iter().chain(n)))
    }

    /// Operation names of a service (Zipkin span names and/or native endpoint names), sorted.
    async fn list_operations(&self, service: &str) -> Result<Vec<String>> {
        let operation = "traces.listOperations";
        if service.trim().is_empty() {
            return Err(Error::validation(format!(
                "{operation}: service is required"
            )));
        }
        let zipkin = async {
            if self.backend == TraceBackend::Native {
                return Ok(Vec::new());
            }
            self.zipkin_strings(
                "/api/v2/spans",
                operation,
                &[("serviceName", service.to_owned())],
            )
            .await
        };
        let native = async {
            if self.backend == TraceBackend::Zipkin {
                return Ok(Vec::new());
            }
            Ok::<_, Error>(
                self.query
                    .list_endpoints(service, None, None)
                    .await?
                    .into_iter()
                    .map(|e| e.name)
                    .collect(),
            )
        };
        let (z, n) = futures::future::try_join(zipkin, native).await?;
        Ok(unique_sorted(z.into_iter().chain(n)))
    }

    /// Service dependencies from the native topology (`call_count` is 0: the topology carries
    /// no counts). OAP 10.4 does not implement Zipkin `/dependencies`, so the `zipkin` backend
    /// fails with `UNSUPPORTED`; OTLP spans create no topology edges.
    async fn get_dependencies(&self, opts: DependencyOptions) -> Result<Vec<DependencyLink>> {
        if self.backend == TraceBackend::Zipkin {
            return Err(Error::unsupported(
                "traces.getDependencies: OAP does not implement the Zipkin dependencies API",
            ));
        }
        let range = TimeRange {
            start: opts.start,
            end: opts.end,
            step: None,
        };
        let topology = self
            .query
            .topology(Some(range), &TopologyOptions::default())
            .await?;
        Ok(topology_to_dependencies(&topology))
    }

    /// Polls `get_trace` until the trace has `min_spans` spans (`TIMEOUT` otherwise). Native
    /// ids are used verbatim; hex ids are normalized.
    async fn wait_for_trace(&self, trace_id: &str, opts: WaitForTraceOptions) -> Result<Trace> {
        let opts = WaitForTraceOptions {
            interval: opts.interval.max(Duration::from_millis(1)),
            ..opts
        };
        wait_for_trace_with(self, trace_id, opts, normalize_trace_id_or_native).await
    }
}
