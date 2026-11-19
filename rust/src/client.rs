// Service code (Apache SkyWalking): `SkyWalkingClient`, entry point of the SDK (SPEC §2.4).
//! `SkyWalkingClient`: health/ping/version, the GraphQL query API, the trace and log query
//! clients, the native log client and tracing setup for Apache SkyWalking OAP.

use std::sync::Arc;
use std::time::{Duration, Instant};

use futures::future::BoxFuture;
use serde_json::Value;

use crate::api::{Api, GraphQLResponse};
use crate::config::SkyWalkingConfig;
use crate::core::lifecycle::CloseGroup;
use crate::core::{Error, HealthStatus, HttpTransport, Result};
use crate::logging::{AttrValue, Attributes, BatchOptions, BatchingLogClient, LogClient};
use crate::logs::{SkyWalkingLogExporter, SkyWalkingLogQueryClient};
use crate::query::SkyWalkingQueryClient;
use crate::setup::init_tracing_with_config;
use crate::traces::SkyWalkingTraceQueryClient;
use crate::tracing::{TracingConfig, TracingHandle};

/// Options of [`SkyWalkingClient::log_client`] / [`create_log_client`].
#[derive(Clone, Default)]
pub struct SkyWalkingLogClientOptions {
    /// `service.name` of the records (resource). Default `unknown_service`.
    pub service_name: Option<String>,
    /// `service.instance.id` of the records (resource).
    pub service_instance: Option<String>,
    /// Batch settings.
    pub batch: BatchOptions,
    /// Extra resource attributes.
    pub resource: Attributes,
}

impl std::fmt::Debug for SkyWalkingLogClientOptions {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SkyWalkingLogClientOptions")
            .field("service_name", &self.service_name)
            .field("service_instance", &self.service_instance)
            .field("batch", &self.batch)
            .field("resource", &self.resource)
            .finish()
    }
}

impl SkyWalkingLogClientOptions {
    /// Options for a service name.
    pub fn service(name: impl Into<String>) -> Self {
        SkyWalkingLogClientOptions {
            service_name: Some(name.into()),
            ..Default::default()
        }
    }
    /// Sets the instance name.
    pub fn instance(mut self, instance: impl Into<String>) -> Self {
        self.service_instance = Some(instance.into());
        self
    }
    /// Sets the batch options.
    pub fn batch(mut self, batch: BatchOptions) -> Self {
        self.batch = batch;
        self
    }
    /// Adds a resource attribute.
    pub fn resource_attribute(
        mut self,
        key: impl Into<String>,
        value: impl Into<AttrValue>,
    ) -> Self {
        self.resource.insert(key.into(), value.into());
        self
    }

    fn build(self, exporter: SkyWalkingLogExporter) -> Result<BatchingLogClient> {
        let mut resource = self.resource;
        if let Some(name) = &self.service_name {
            resource.insert("service.name".into(), name.clone().into());
        }
        if let Some(instance) = self.service_instance {
            resource.insert("service.instance.id".into(), instance.into());
        }
        BatchingLogClient::builder(Arc::new(exporter))
            .batch_options(self.batch)
            .resource(resource)
            .build()
    }
}

/// Standalone [`BatchingLogClient`] that sends native logs to `POST /v3/logs` (owns its
/// connections; `close()` flushes and releases them). Must be called inside a tokio runtime.
pub fn create_log_client(
    config: SkyWalkingConfig,
    options: SkyWalkingLogClientOptions,
) -> Result<BatchingLogClient> {
    let api = Arc::new(Api::new(config)?);
    let exporter = SkyWalkingLogExporter::new(api, options.service_name.clone(), true);
    options.build(exporter)
}

/// Async client for Apache SkyWalking OAP. Cheap to clone (clones share one connection pool).
#[derive(Debug, Clone)]
pub struct SkyWalkingClient {
    inner: Arc<Inner>,
}

#[derive(Debug)]
struct Inner {
    api: Arc<Api>,
    query: SkyWalkingQueryClient,
    traces: SkyWalkingTraceQueryClient,
    logs: SkyWalkingLogQueryClient,
    on_close: CloseGroup,
}

impl SkyWalkingClient {
    /// Creates a client from a configuration.
    pub fn new(config: SkyWalkingConfig) -> Result<Self> {
        config.validate()?;
        let backend = config.trace_backend();
        let api = Arc::new(Api::new(config)?);
        let query = SkyWalkingQueryClient::new(Arc::clone(&api));
        Ok(SkyWalkingClient {
            inner: Arc::new(Inner {
                traces: SkyWalkingTraceQueryClient::new(Arc::clone(&api), backend),
                logs: SkyWalkingLogQueryClient::new(query.clone()),
                query,
                api,
                on_close: CloseGroup::new(),
            }),
        })
    }

    /// Creates a client from `GROW_APACHE_SKYWALKING_*` (and vendor) environment variables.
    pub fn from_env() -> Result<Self> {
        Self::new(SkyWalkingConfig::from_env()?)
    }

    /// The configuration in use.
    pub fn config(&self) -> &SkyWalkingConfig {
        &self.inner.api.config
    }

    /// The HTTP transport (for service modules).
    pub fn transport(&self) -> &HttpTransport {
        &self.inner.api.transport
    }

    /// Registers work that [`SkyWalkingClient::close`] runs (flush, stop watchers, …).
    pub fn on_close<F>(&self, f: F)
    where
        F: FnOnce() -> BoxFuture<'static, ()> + Send + 'static,
    {
        self.inner.on_close.register(f);
    }

    /// The GraphQL query API: services, endpoints, instances, native traces, logs, topology.
    pub fn query(&self) -> &SkyWalkingQueryClient {
        &self.inner.query
    }

    /// SPEC `TraceQueryClient` (Zipkin store for OTLP spans, native store for agents).
    pub fn traces(&self) -> &SkyWalkingTraceQueryClient {
        &self.inner.traces
    }

    /// SPEC `LogQueryClient`.
    pub fn logs(&self) -> &SkyWalkingLogQueryClient {
        &self.inner.logs
    }

    /// Liveness: `GET /healthcheck` (OAP health checker, `SW_HEALTH_CHECKER=default`): 200 →
    /// ok, 503 → unhealthy. Without the health checker (404) falls back to GraphQL
    /// `checkHealth` (score 0 = ok). Never fails for an unhealthy or unreachable service (only
    /// `CANCELLED` and `CONFIG`).
    pub async fn health(&self) -> Result<HealthStatus> {
        let resp = self
            .transport()
            .get("/healthcheck")
            .operation("health")
            .ok_status(404)
            .ok_status(503)
            .send()
            .await;
        let resp = match resp {
            Ok(r) => r,
            Err(e) => return HealthStatus::from_error(e),
        };
        match resp.status() {
            503 => Ok(HealthStatus::unhealthy("unhealthy").with_detail("httpStatus", "503")),
            404 => {
                let check = match self
                    .inner
                    .api
                    .field(
                        "{checkHealth{score details}}",
                        Value::Null,
                        "checkHealth",
                        "health",
                    )
                    .await
                {
                    Ok(v) => v,
                    Err(e) => return HealthStatus::from_error(e),
                };
                let score = check.get("score").and_then(Value::as_i64).unwrap_or(-1);
                let details = check
                    .get("details")
                    .and_then(Value::as_str)
                    .unwrap_or_default();
                if score == 0 {
                    return Ok(HealthStatus::healthy("ok"));
                }
                let status =
                    HealthStatus::unhealthy("unhealthy").with_detail("score", score.to_string());
                Ok(if details.is_empty() {
                    status
                } else {
                    status.with_detail("details", details)
                })
            }
            _ => Ok(HealthStatus::healthy("ok")),
        }
    }

    /// Timed GraphQL round trip (`getTimeInfo`; also caches the OAP timezone). OAP has no
    /// query authentication: `AUTHENTICATION` only comes from a gateway in front of it.
    pub async fn ping(&self) -> Result<Duration> {
        let started = Instant::now();
        self.inner.api.time_info("ping").await?;
        Ok(started.elapsed())
    }

    /// OAP version from GraphQL `{version}` (e.g. `10.4.0-b272da3`); `UNSUPPORTED` when the
    /// service reports none.
    pub async fn server_version(&self) -> Result<String> {
        let v = self
            .inner
            .api
            .field("{version}", Value::Null, "version", "serverVersion")
            .await?;
        v.as_str()
            .filter(|s| !s.is_empty())
            .map(str::to_owned)
            .ok_or_else(|| {
                Error::unsupported("serverVersion: the service does not report a version")
            })
    }

    /// Raw GraphQL (same as `client.query().graphql`).
    pub async fn graphql(&self, query: &str, variables: Value) -> Result<GraphQLResponse> {
        self.inner.query.graphql(query, variables).await
    }

    /// A [`BatchingLogClient`] that sends native logs (`POST /v3/logs`) over this client's
    /// connections; [`close`](Self::close) flushes and closes it. Must be called inside a tokio
    /// runtime.
    pub fn log_client(&self, options: SkyWalkingLogClientOptions) -> Result<BatchingLogClient> {
        let exporter = SkyWalkingLogExporter::new(
            Arc::clone(&self.inner.api),
            options.service_name.clone(),
            false,
        );
        let client = options.build(exporter)?;
        let owned = client.clone();
        self.on_close(move || {
            Box::pin(async move {
                let _ = owned.close().await;
            })
        });
        Ok(client)
    }

    /// [`init_tracing_with_config`] with this client's config (OTLP endpoint, protocol, token);
    /// [`close`](Self::close) shuts the handle down. Must be called inside a tokio runtime.
    pub fn init_tracing(&self, config: TracingConfig) -> Result<TracingHandle> {
        let handle = init_tracing_with_config(config, self.config())?;
        let owned = handle.clone();
        self.on_close(move || {
            Box::pin(async move {
                let _ = owned.shutdown().await;
            })
        });
        Ok(handle)
    }

    /// Flushes and closes log clients and tracing handles created here, then releases the
    /// connections. Idempotent.
    pub async fn close(&self) {
        self.inner.on_close.close().await;
        self.inner.api.transport.close();
    }

    /// Whether [`SkyWalkingClient::close`] was called.
    pub fn is_closed(&self) -> bool {
        self.inner.api.transport.is_closed()
    }
}
