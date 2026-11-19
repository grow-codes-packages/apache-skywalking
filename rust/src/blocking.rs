// Service code (Apache SkyWalking): blocking wrapper (feature `blocking`).
//! Blocking wrapper (feature `blocking`). Each client owns a one-worker multi-thread tokio
//! runtime, so background work (log batching, span export) keeps running between calls.
//!
//! Do not use it from inside an async runtime (calls would panic); use the async client there.

use std::future::Future;
use std::time::Duration;

use serde_json::Value;

use crate::api::{GraphQLResponse, SkyWalkingCapabilities, TimeInfo};
use crate::core::{Error, HealthStatus, Result};
use crate::logging::{
    BatchingLogClient, LogQueryClient, LogRecord, LogSearchResult, SearchOptions, WaitOptions,
};
use crate::native::{NativeTrace, Topology};
use crate::query::{
    QueryLogsOptions, QueryLogsResult, QueryTracesOptions, QueryTracesResult, SkyWalkingEndpoint,
    SkyWalkingInstance, SkyWalkingService, TopologyOptions,
};
use crate::tracing::{
    DependencyLink, DependencyOptions, Trace, TraceQueryClient, TraceSearchQuery, TraceSummary,
    TracingConfig, TracingHandle, WaitForTraceOptions,
};
use crate::{
    SkyWalkingClient as AsyncClient, SkyWalkingConfig, SkyWalkingLogClientOptions, TimeRange,
};

/// Blocking client for Apache SkyWalking with the same methods as the async client.
#[derive(Debug)]
pub struct SkyWalkingClient {
    inner: AsyncClient,
    runtime: tokio::runtime::Runtime,
}

impl SkyWalkingClient {
    /// Creates a client from a configuration.
    pub fn new(config: SkyWalkingConfig) -> Result<Self> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(1)
            .thread_name("grow-skywalking")
            .enable_all()
            .build()
            .map_err(|e| Error::config("cannot start the tokio runtime").with_source(e))?;
        let inner = {
            let _guard = runtime.enter();
            AsyncClient::new(config)?
        };
        Ok(SkyWalkingClient { inner, runtime })
    }

    /// Creates a client from the process environment.
    pub fn from_env() -> Result<Self> {
        Self::new(SkyWalkingConfig::from_env()?)
    }

    /// The wrapped async client.
    pub fn async_client(&self) -> &AsyncClient {
        &self.inner
    }

    /// The configuration in use.
    pub fn config(&self) -> &SkyWalkingConfig {
        self.inner.config()
    }

    /// Runs a future of the async API to completion on this client's runtime.
    pub fn block_on<F: Future>(&self, future: F) -> F::Output {
        self.runtime.block_on(future)
    }

    /// See [`AsyncClient::health`].
    pub fn health(&self) -> Result<HealthStatus> {
        self.block_on(self.inner.health())
    }

    /// See [`AsyncClient::ping`].
    pub fn ping(&self) -> Result<Duration> {
        self.block_on(self.inner.ping())
    }

    /// See [`AsyncClient::server_version`].
    pub fn server_version(&self) -> Result<String> {
        self.block_on(self.inner.server_version())
    }

    /// See [`AsyncClient::graphql`].
    pub fn graphql(&self, query: &str, variables: Value) -> Result<GraphQLResponse> {
        self.block_on(self.inner.graphql(query, variables))
    }

    /// The GraphQL query API (blocking).
    pub fn query(&self) -> QueryClient<'_> {
        QueryClient { client: self }
    }

    /// The trace query client (blocking).
    pub fn traces(&self) -> TraceClient<'_> {
        TraceClient { client: self }
    }

    /// The log query client (blocking).
    pub fn logs(&self) -> LogsClient<'_> {
        LogsClient { client: self }
    }

    /// See [`AsyncClient::log_client`]; the batching runs on this client's runtime. Flush it
    /// with `client.block_on(log_client.flush(None))` or rely on [`close`](Self::close).
    pub fn log_client(&self, options: SkyWalkingLogClientOptions) -> Result<BatchingLogClient> {
        let _guard = self.runtime.enter();
        self.inner.log_client(options)
    }

    /// See [`AsyncClient::init_tracing`]; spans are exported from this client's runtime.
    pub fn init_tracing(&self, config: TracingConfig) -> Result<TracingHandle> {
        let _guard = self.runtime.enter();
        self.inner.init_tracing(config)
    }

    /// See [`AsyncClient::close`]. Idempotent.
    pub fn close(&self) {
        self.block_on(self.inner.close());
    }
}

impl Drop for SkyWalkingClient {
    fn drop(&mut self) {
        self.inner.transport().close();
    }
}

/// Blocking view of [`SkyWalkingQueryClient`](crate::SkyWalkingQueryClient).
#[derive(Debug, Clone, Copy)]
pub struct QueryClient<'a> {
    client: &'a SkyWalkingClient,
}

impl QueryClient<'_> {
    fn q(&self) -> &crate::SkyWalkingQueryClient {
        self.client.inner.query()
    }
    /// See [`crate::SkyWalkingQueryClient::graphql`].
    pub fn graphql(&self, query: &str, variables: Value) -> Result<GraphQLResponse> {
        self.client.block_on(self.q().graphql(query, variables))
    }
    /// See [`crate::SkyWalkingQueryClient::time_info`].
    pub fn time_info(&self) -> Result<TimeInfo> {
        self.client.block_on(self.q().time_info())
    }
    /// See [`crate::SkyWalkingQueryClient::capabilities`].
    pub fn capabilities(&self) -> Result<SkyWalkingCapabilities> {
        self.client.block_on(self.q().capabilities())
    }
    /// See [`crate::SkyWalkingQueryClient::list_services`].
    pub fn list_services(
        &self,
        layer: Option<&str>,
        range: Option<TimeRange>,
    ) -> Result<Vec<SkyWalkingService>> {
        self.client.block_on(self.q().list_services(layer, range))
    }
    /// See [`crate::SkyWalkingQueryClient::find_service`].
    pub fn find_service(&self, name: &str) -> Result<Option<SkyWalkingService>> {
        self.client.block_on(self.q().find_service(name))
    }
    /// See [`crate::SkyWalkingQueryClient::list_endpoints`].
    pub fn list_endpoints(
        &self,
        service: &str,
        keyword: Option<&str>,
        limit: Option<usize>,
    ) -> Result<Vec<SkyWalkingEndpoint>> {
        self.client
            .block_on(self.q().list_endpoints(service, keyword, limit))
    }
    /// See [`crate::SkyWalkingQueryClient::list_instances`].
    pub fn list_instances(
        &self,
        service: &str,
        range: Option<TimeRange>,
    ) -> Result<Vec<SkyWalkingInstance>> {
        self.client
            .block_on(self.q().list_instances(service, range))
    }
    /// See [`crate::SkyWalkingQueryClient::query_traces`].
    pub fn query_traces(&self, opts: &QueryTracesOptions) -> Result<QueryTracesResult> {
        self.client.block_on(self.q().query_traces(opts))
    }
    /// See [`crate::SkyWalkingQueryClient::get_trace`].
    pub fn get_trace(&self, trace_id: &str) -> Result<Option<NativeTrace>> {
        self.client.block_on(self.q().get_trace(trace_id))
    }
    /// See [`crate::SkyWalkingQueryClient::query_logs`].
    pub fn query_logs(&self, opts: &QueryLogsOptions) -> Result<QueryLogsResult> {
        self.client.block_on(self.q().query_logs(opts))
    }
    /// See [`crate::SkyWalkingQueryClient::list_log_tag_keys`].
    pub fn list_log_tag_keys(&self, range: Option<TimeRange>) -> Result<Vec<String>> {
        self.client.block_on(self.q().list_log_tag_keys(range))
    }
    /// See [`crate::SkyWalkingQueryClient::topology`].
    pub fn topology(&self, range: Option<TimeRange>, opts: &TopologyOptions) -> Result<Topology> {
        self.client.block_on(self.q().topology(range, opts))
    }
}

/// Blocking view of [`SkyWalkingTraceQueryClient`](crate::SkyWalkingTraceQueryClient).
#[derive(Debug, Clone, Copy)]
pub struct TraceClient<'a> {
    client: &'a SkyWalkingClient,
}

impl TraceClient<'_> {
    fn t(&self) -> &crate::SkyWalkingTraceQueryClient {
        self.client.inner.traces()
    }
    /// See [`TraceQueryClient::get_trace`].
    pub fn get_trace(&self, trace_id: &str) -> Result<Option<Trace>> {
        self.client.block_on(self.t().get_trace(trace_id))
    }
    /// See [`TraceQueryClient::search_traces`].
    pub fn search_traces(&self, query: &TraceSearchQuery) -> Result<Vec<TraceSummary>> {
        self.client.block_on(self.t().search_traces(query))
    }
    /// See [`TraceQueryClient::list_services`].
    pub fn list_services(&self) -> Result<Vec<String>> {
        self.client.block_on(self.t().list_services())
    }
    /// See [`TraceQueryClient::list_operations`].
    pub fn list_operations(&self, service: &str) -> Result<Vec<String>> {
        self.client.block_on(self.t().list_operations(service))
    }
    /// See [`TraceQueryClient::get_dependencies`].
    pub fn get_dependencies(&self, opts: DependencyOptions) -> Result<Vec<DependencyLink>> {
        self.client.block_on(self.t().get_dependencies(opts))
    }
    /// See [`TraceQueryClient::wait_for_trace`].
    pub fn wait_for_trace(&self, trace_id: &str, opts: WaitForTraceOptions) -> Result<Trace> {
        self.client
            .block_on(self.t().wait_for_trace(trace_id, opts))
    }
}

/// Blocking view of [`SkyWalkingLogQueryClient`](crate::SkyWalkingLogQueryClient).
#[derive(Debug, Clone, Copy)]
pub struct LogsClient<'a> {
    client: &'a SkyWalkingClient,
}

impl LogsClient<'_> {
    fn l(&self) -> &crate::SkyWalkingLogQueryClient {
        self.client.inner.logs()
    }
    /// See [`LogQueryClient::search`].
    pub fn search(&self, query: &str, opts: SearchOptions) -> Result<LogSearchResult> {
        self.client.block_on(self.l().search(query, opts))
    }
    /// See [`LogQueryClient::wait_for_log`].
    pub fn wait_for_log(&self, query: &str, opts: WaitOptions) -> Result<LogRecord> {
        self.client.block_on(self.l().wait_for_log(query, opts))
    }
    /// See [`LogQueryClient::list_streams`].
    pub fn list_streams(&self) -> Result<Vec<String>> {
        self.client.block_on(self.l().list_streams())
    }
    /// See [`LogQueryClient::list_labels`].
    pub fn list_labels(&self) -> Result<Vec<String>> {
        self.client.block_on(self.l().list_labels())
    }
}
