// Service code (Apache SkyWalking): `SkyWalkingConfig` and its builder.
//! `SkyWalkingConfig`: common settings (SPEC §2.2) plus the OAP endpoints — GraphQL/REST on
//! port 12800, the OTLP gRPC receiver on 11800 and the Zipkin query API on 9412 — the optional
//! gRPC token and the trace store read by [`SkyWalkingTraceQueryClient`](crate::SkyWalkingTraceQueryClient).

use std::collections::{BTreeMap, HashMap};
use std::fmt;
use std::path::PathBuf;
use std::str::FromStr;
use std::sync::Arc;
use std::time::Duration;

use crate::core::redact::redact_url;
use crate::core::{
    CommonConfig, CommonConfigBuilder, EnvReader, Error, Hooks, Result, RetryPolicy, Secret,
    TlsConfig, REDACTED,
};

/// REST/GraphQL endpoint used when neither the builder nor the environment sets one.
pub const DEFAULT_URL: &str = "http://127.0.0.1:12800";
/// OAP gRPC port (OTLP receiver).
pub const OTLP_GRPC_PORT: u16 = 11800;
/// OAP Zipkin query port (`SW_QUERY_ZIPKIN`).
pub const ZIPKIN_QUERY_PORT: u16 = 9412;
/// Default GraphQL path below the URL.
pub const DEFAULT_GRAPHQL_PATH: &str = "/graphql";

/// Vendor variables checked (in order) after `GROW_APACHE_SKYWALKING_URL`.
pub const VENDOR_URL_VARS: &[&str] = &["GROW_SKYWALKING_URL", "SW_GRAPHQL_URL", "SW_OAP_ADDRESS"];
/// Vendor variables checked (in order) after `GROW_APACHE_SKYWALKING_OTLP_ENDPOINT`.
pub const VENDOR_OTLP_ENDPOINT_VARS: &[&str] = &[
    "GROW_SKYWALKING_OTLP_ENDPOINT",
    "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
    "OTEL_EXPORTER_OTLP_ENDPOINT",
    "SW_AGENT_COLLECTOR_BACKEND_SERVICES",
];
/// Vendor variables checked (in order) after `GROW_APACHE_SKYWALKING_OTLP_PROTOCOL`.
pub const VENDOR_OTLP_PROTOCOL_VARS: &[&str] = &[
    "GROW_SKYWALKING_OTLP_PROTOCOL",
    "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL",
    "OTEL_EXPORTER_OTLP_PROTOCOL",
];
/// Vendor variables checked (in order) after `GROW_APACHE_SKYWALKING_OTLP_HEADERS`.
pub const VENDOR_OTLP_HEADERS_VARS: &[&str] = &[
    "OTEL_EXPORTER_OTLP_TRACES_HEADERS",
    "OTEL_EXPORTER_OTLP_HEADERS",
];
/// Vendor variables checked (in order) after `GROW_APACHE_SKYWALKING_AUTHENTICATION`.
pub const VENDOR_AUTHENTICATION_VARS: &[&str] = &[
    "GROW_SKYWALKING_AUTHENTICATION",
    "SW_AUTHENTICATION",
    "SW_AGENT_AUTHENTICATION",
];
/// Vendor variables checked (in order) after `GROW_APACHE_SKYWALKING_ZIPKIN_URL`.
pub const VENDOR_ZIPKIN_URL_VARS: &[&str] = &["GROW_SKYWALKING_ZIPKIN_URL", "SW_ZIPKIN_ADDRESS"];
/// Vendor variables checked after `GROW_APACHE_SKYWALKING_TRACE_BACKEND`.
pub const VENDOR_TRACE_BACKEND_VARS: &[&str] = &["GROW_SKYWALKING_TRACE_BACKEND"];

/// Short-prefix aliases (`GROW_SKYWALKING_<NAME>`) of the common settings.
const COMMON_ALIASES: &[&str] = &[
    "CA_CERT",
    "CLIENT_CERT",
    "CLIENT_KEY",
    "INSECURE_SKIP_VERIFY",
    "PROXY",
];

/// Which store the trace query client reads.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum TraceBackend {
    /// Hex (OTLP) ids via the Zipkin query API first, then native; other ids native (default).
    #[default]
    Auto,
    /// OAP's Zipkin query API (spans sent over OTLP).
    Zipkin,
    /// GraphQL (SkyWalking agent segments).
    Native,
}

impl TraceBackend {
    /// Wire value: `auto`, `zipkin` or `native`.
    pub fn as_str(self) -> &'static str {
        match self {
            TraceBackend::Auto => "auto",
            TraceBackend::Zipkin => "zipkin",
            TraceBackend::Native => "native",
        }
    }
}

impl FromStr for TraceBackend {
    type Err = Error;
    fn from_str(s: &str) -> Result<Self> {
        match s.trim().to_ascii_lowercase().as_str() {
            "auto" => Ok(TraceBackend::Auto),
            "zipkin" => Ok(TraceBackend::Zipkin),
            "native" => Ok(TraceBackend::Native),
            _ => Err(Error::config(
                "invalid configuration: traceBackend must be auto, zipkin or native",
            )),
        }
    }
}

/// OTLP protocol of the trace exporter. OAP 10.4 only serves `grpc`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum OtlpProtocol {
    /// OTLP over gRPC (the OAP receiver; default).
    #[default]
    Grpc,
    /// OTLP over HTTP/protobuf (only for an OpenTelemetry Collector in front of OAP).
    HttpProtobuf,
}

impl OtlpProtocol {
    /// Wire value: `grpc` or `http/protobuf`.
    pub fn as_str(self) -> &'static str {
        match self {
            OtlpProtocol::Grpc => "grpc",
            OtlpProtocol::HttpProtobuf => "http/protobuf",
        }
    }
}

impl FromStr for OtlpProtocol {
    type Err = Error;
    fn from_str(s: &str) -> Result<Self> {
        match s.trim() {
            "grpc" => Ok(OtlpProtocol::Grpc),
            "http/protobuf" => Ok(OtlpProtocol::HttpProtobuf),
            _ => Err(Error::config(
                "invalid configuration: otlpProtocol must be grpc or http/protobuf",
            )),
        }
    }
}

fn strip_slash(url: &str) -> &str {
    url.trim_end_matches('/')
}

/// Strips a trailing `/graphql` (with or without slash, any case) from a GraphQL URL.
pub fn base_from_graphql_url(url: &str) -> String {
    let base = strip_slash(url.trim());
    let cut = if base.to_ascii_lowercase().ends_with("/graphql") {
        &base[..base.len() - "/graphql".len()]
    } else {
        base
    };
    strip_slash(cut).to_owned()
}

/// `host:port[,host:port]` (SkyWalking agent collector address) → `http://host:port`; values
/// that already carry a scheme are returned as is.
pub fn collector_to_url(value: &str) -> String {
    let first = value.split(',').next().unwrap_or("").trim();
    if has_scheme(first) {
        first.to_owned()
    } else {
        format!("http://{first}")
    }
}

fn has_scheme(value: &str) -> bool {
    match value.find("://") {
        Some(i) if i > 0 => {
            let scheme = &value[..i];
            scheme
                .chars()
                .next()
                .is_some_and(|c| c.is_ascii_alphabetic())
                && scheme
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || matches!(c, '+' | '.' | '-'))
        }
        _ => false,
    }
}

/// A Zipkin address with or without the `/zipkin` path → `<address>/zipkin`.
pub fn zipkin_query_url(value: &str) -> String {
    let base = strip_slash(value.trim());
    if base.to_ascii_lowercase().ends_with("/zipkin") {
        base.to_owned()
    } else {
        format!("{base}/zipkin")
    }
}

fn percent_decode(raw: &str) -> Option<String> {
    let bytes = raw.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            let hex = raw.get(i + 1..i + 3)?;
            out.push(u8::from_str_radix(hex, 16).ok()?);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}

/// Parses OTEL-style headers `k1=v1,k2=v2` (values percent-decoded, keys lower-cased).
/// `CONFIG` naming `source` (never the value) when an entry is malformed.
pub fn parse_otlp_headers(raw: &str, source: &str) -> Result<BTreeMap<String, String>> {
    let mut out = BTreeMap::new();
    for part in raw.split(',') {
        if part.trim().is_empty() {
            continue;
        }
        let (key, value) = match part.split_once('=') {
            Some((k, v)) if !k.trim().is_empty() => (k.trim(), v.trim()),
            _ => {
                return Err(Error::config(format!(
                    "invalid value in {source}: expected key=value pairs"
                )))
            }
        };
        let value = percent_decode(value).ok_or_else(|| {
            Error::config(format!("invalid value in {source}: bad percent-encoding"))
        })?;
        out.insert(key.to_ascii_lowercase(), value);
    }
    Ok(out)
}

fn host_url(base: &url::Url, port: u16, path: &str) -> String {
    let host = match base.host() {
        Some(url::Host::Ipv6(ip)) => format!("[{ip}]"),
        Some(h) => h.to_string(),
        None => "127.0.0.1".to_owned(),
    };
    format!("{}://{host}:{port}{path}", base.scheme())
}

fn check_http_url(value: &str, field: &str) -> Result<()> {
    match url::Url::parse(value) {
        Ok(u) if matches!(u.scheme(), "http" | "https") => Ok(()),
        _ => Err(Error::config(format!(
            "invalid configuration: {field} must be an http(s) URL"
        ))),
    }
}

/// Header names whose values are credentials (redacted in `Debug`, error messages and logs).
fn is_sensitive(name: &str) -> bool {
    crate::core::redact::is_sensitive_header(name) || name.eq_ignore_ascii_case("authentication")
}

/// Immutable client configuration; build it with [`SkyWalkingConfig::builder`] or
/// [`SkyWalkingConfig::from_env`]. `Debug` output redacts every credential.
#[derive(Clone)]
pub struct SkyWalkingConfig {
    common: CommonConfig,
    graphql_path: String,
    otlp_endpoint: String,
    otlp_protocol: OtlpProtocol,
    otlp_headers: BTreeMap<String, String>,
    zipkin_url: String,
    authentication: Option<Secret>,
    trace_backend: TraceBackend,
}

impl fmt::Debug for SkyWalkingConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let headers: BTreeMap<&str, &str> = self
            .otlp_headers
            .keys()
            .map(|k| (k.as_str(), REDACTED))
            .collect();
        f.debug_struct("SkyWalkingConfig")
            .field("common", &self.common)
            .field("graphql_path", &self.graphql_path)
            .field("otlp_endpoint", &redact_url(&self.otlp_endpoint))
            .field("otlp_protocol", &self.otlp_protocol.as_str())
            .field("otlp_headers", &headers)
            .field("zipkin_url", &redact_url(&self.zipkin_url))
            .field(
                "authentication",
                &self.authentication.as_ref().map(|_| REDACTED),
            )
            .field("trace_backend", &self.trace_backend.as_str())
            .finish()
    }
}

impl SkyWalkingConfig {
    /// A builder with nothing set.
    pub fn builder() -> SkyWalkingConfigBuilder {
        SkyWalkingConfigBuilder::default()
    }

    /// Configuration from the process environment (explicit > `GROW_APACHE_SKYWALKING_*` >
    /// vendor variables > defaults). `SW_GRAPHQL_URL` loses a trailing `/graphql`,
    /// `SW_AGENT_COLLECTOR_BACKEND_SERVICES` (`host:port`) becomes `http://host:port` and
    /// `SW_ZIPKIN_ADDRESS` gains `/zipkin`.
    pub fn from_env() -> Result<Self> {
        Self::builder().build_from_env()
    }

    /// Settings shared by every Grow Codes SDK.
    pub fn common(&self) -> &CommonConfig {
        &self.common
    }

    /// REST/GraphQL base URL (port 12800).
    pub fn url(&self) -> &url::Url {
        self.common.url()
    }

    /// GraphQL path below [`url`](Self::url) (default `/graphql`).
    pub fn graphql_path(&self) -> &str {
        &self.graphql_path
    }

    /// Absolute GraphQL URL.
    pub fn graphql_url(&self) -> String {
        format!("{}{}", strip_slash(self.url().as_str()), self.graphql_path)
    }

    /// OTLP endpoint for traces (default `http://<url host>:11800`).
    pub fn otlp_endpoint(&self) -> &str {
        &self.otlp_endpoint
    }

    /// OTLP protocol (default `grpc`).
    pub fn otlp_protocol(&self) -> OtlpProtocol {
        self.otlp_protocol
    }

    /// Extra OTLP exporter headers (gRPC metadata), keys lower-case.
    pub fn otlp_headers(&self) -> &BTreeMap<String, String> {
        &self.otlp_headers
    }

    /// Zipkin query API base, ending with `/zipkin` (default `http://<url host>:9412/zipkin`).
    pub fn zipkin_url(&self) -> &str {
        &self.zipkin_url
    }

    /// OAP gRPC token (`SW_AUTHENTICATION`), when configured.
    pub fn authentication(&self) -> Option<&Secret> {
        self.authentication.as_ref()
    }

    /// Trace store read by the trace query client (default `auto`).
    pub fn trace_backend(&self) -> TraceBackend {
        self.trace_backend
    }

    /// Credential values to redact from error messages and logs.
    pub fn known_secrets(&self) -> Vec<Secret> {
        let mut out: Vec<Secret> = self.authentication.iter().cloned().collect();
        out.extend(
            self.otlp_headers
                .iter()
                .filter(|(k, _)| is_sensitive(k))
                .map(|(_, v)| Secret::new(v.clone())),
        );
        out
    }

    /// Checks the service rules (common rules are checked when building); `CONFIG` otherwise.
    pub fn validate(&self) -> Result<()> {
        check_http_url(self.url().as_str(), "url")?;
        if !self.graphql_path.starts_with('/') {
            return Err(Error::config(
                "invalid configuration: graphqlPath must start with /",
            ));
        }
        check_http_url(&self.otlp_endpoint, "otlpEndpoint")?;
        check_http_url(&self.zipkin_url, "zipkinUrl")?;
        if self
            .authentication
            .as_ref()
            .is_some_and(|a| a.expose_secret().trim().is_empty())
        {
            return Err(Error::config(
                "invalid configuration: authentication must not be blank",
            ));
        }
        Ok(())
    }
}

/// Builder for [`SkyWalkingConfig`]. Values set here win over the environment.
#[derive(Debug, Clone, Default)]
pub struct SkyWalkingConfigBuilder {
    common: CommonConfigBuilder,
    url: Option<String>,
    graphql_path: Option<String>,
    otlp_endpoint: Option<String>,
    otlp_protocol: Option<OtlpProtocol>,
    otlp_headers: Option<BTreeMap<String, String>>,
    zipkin_url: Option<String>,
    authentication: Option<Secret>,
    trace_backend: Option<TraceBackend>,
}

impl SkyWalkingConfigBuilder {
    /// REST/GraphQL base URL (default [`DEFAULT_URL`]); a trailing `/graphql` is stripped.
    pub fn url(mut self, url: impl Into<String>) -> Self {
        self.url = Some(url.into());
        self
    }
    /// GraphQL path below the URL (default `/graphql`).
    pub fn graphql_path(mut self, path: impl Into<String>) -> Self {
        self.graphql_path = Some(path.into());
        self
    }
    /// OTLP endpoint for `init_tracing` (default `http://<url host>:11800`).
    pub fn otlp_endpoint(mut self, endpoint: impl Into<String>) -> Self {
        self.otlp_endpoint = Some(endpoint.into());
        self
    }
    /// OTLP protocol (default `grpc`; `http/protobuf` only for a collector endpoint).
    pub fn otlp_protocol(mut self, protocol: OtlpProtocol) -> Self {
        self.otlp_protocol = Some(protocol);
        self
    }
    /// Adds an OTLP exporter header (gRPC metadata); the name is lower-cased.
    pub fn otlp_header(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        self.otlp_headers
            .get_or_insert_with(BTreeMap::new)
            .insert(name.into().to_ascii_lowercase(), value.into());
        self
    }
    /// Zipkin query API base (default `http://<url host>:9412/zipkin`).
    pub fn zipkin_url(mut self, url: impl Into<String>) -> Self {
        self.zipkin_url = Some(url.into());
        self
    }
    /// OAP gRPC token, sent as the `authentication` metadata on OTLP exports.
    pub fn authentication(mut self, token: impl Into<Secret>) -> Self {
        self.authentication = Some(token.into());
        self
    }
    /// Trace store read by the trace query client (default `auto`).
    pub fn trace_backend(mut self, backend: TraceBackend) -> Self {
        self.trace_backend = Some(backend);
        self
    }
    /// Connect timeout (default 10 s).
    pub fn connect_timeout(mut self, timeout: Duration) -> Self {
        self.common = self.common.connect_timeout(timeout);
        self
    }
    /// Per-attempt timeout (default 30 s).
    pub fn request_timeout(mut self, timeout: Duration) -> Self {
        self.common = self.common.request_timeout(timeout);
        self
    }
    /// Whole-call timeout including retries (default 120 s).
    pub fn total_timeout(mut self, timeout: Duration) -> Self {
        self.common = self.common.total_timeout(timeout);
        self
    }
    /// Retry policy.
    pub fn retry(mut self, retry: RetryPolicy) -> Self {
        self.common = self.common.retry(retry);
        self
    }
    /// Maximum retries (default 3).
    pub fn max_retries(mut self, max_retries: u32) -> Self {
        self.common = self.common.max_retries(max_retries);
        self
    }
    /// TLS settings.
    pub fn tls(mut self, tls: TlsConfig) -> Self {
        self.common = self.common.tls(tls);
        self
    }
    /// Extra trusted CA certificates (PEM file).
    pub fn ca_cert_path(mut self, path: impl Into<PathBuf>) -> Self {
        self.common = self.common.ca_cert_path(path);
        self
    }
    /// Disables TLS verification (tests only).
    pub fn insecure_skip_verify(mut self, skip: bool) -> Self {
        self.common = self.common.insecure_skip_verify(skip);
        self
    }
    /// Proxy URL (environment proxies are ignored).
    pub fn proxy(mut self, proxy: impl Into<String>) -> Self {
        self.common = self.common.proxy(proxy);
        self
    }
    /// Extra request header (REST/GraphQL, e.g. for a gateway in front of OAP).
    pub fn header(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        self.common = self.common.header(name, value);
        self
    }
    /// Text appended to the User-Agent.
    pub fn user_agent_suffix(mut self, suffix: impl Into<String>) -> Self {
        self.common = self.common.user_agent_suffix(suffix);
        self
    }
    /// Request hooks.
    pub fn hooks(mut self, hooks: Arc<dyn Hooks>) -> Self {
        self.common = self.common.hooks(hooks);
        self
    }
    /// Edits any other common setting.
    pub fn common(mut self, f: impl FnOnce(CommonConfigBuilder) -> CommonConfigBuilder) -> Self {
        self.common = f(self.common);
        self
    }

    /// Builds without reading the environment; `CONFIG` when a setting is invalid.
    pub fn build(self) -> Result<SkyWalkingConfig> {
        let mut common = self.common;
        if let Some(url) = &self.url {
            common = common.url(base_from_graphql_url(url));
        }
        let common = common.build(DEFAULT_URL)?;
        let base = common.url().clone();
        let config = SkyWalkingConfig {
            graphql_path: self
                .graphql_path
                .unwrap_or_else(|| DEFAULT_GRAPHQL_PATH.to_owned()),
            otlp_endpoint: strip_slash(
                &self
                    .otlp_endpoint
                    .unwrap_or_else(|| host_url(&base, OTLP_GRPC_PORT, "")),
            )
            .to_owned(),
            otlp_protocol: self.otlp_protocol.unwrap_or_default(),
            otlp_headers: self.otlp_headers.unwrap_or_default(),
            zipkin_url: strip_slash(
                &self
                    .zipkin_url
                    .unwrap_or_else(|| host_url(&base, ZIPKIN_QUERY_PORT, "/zipkin")),
            )
            .to_owned(),
            authentication: self.authentication,
            trace_backend: self.trace_backend.unwrap_or_default(),
            common,
        };
        config.validate()?;
        Ok(config)
    }

    /// Fills unset values from the process environment, then builds.
    pub fn build_from_env(self) -> Result<SkyWalkingConfig> {
        self.build_with_env(&EnvReader::new())
    }

    /// Fills unset values from `env`, then builds (precedence: explicit >
    /// `GROW_APACHE_SKYWALKING_<NAME>` > vendor variables > defaults).
    pub fn build_with_env(mut self, env: &EnvReader) -> Result<SkyWalkingConfig> {
        if self.url.is_none() {
            self.url = env.string("URL", VENDOR_URL_VARS);
        }
        let mut aliased: HashMap<String, String> = HashMap::new();
        for name in [
            "CONNECT_TIMEOUT",
            "REQUEST_TIMEOUT",
            "TOTAL_TIMEOUT",
            "MAX_RETRIES",
        ] {
            if let Some((_, v)) = env.lookup(name, &[]) {
                aliased.insert(format!("{}{name}", env.prefix()), v);
            }
        }
        for name in COMMON_ALIASES {
            let alias = format!("GROW_SKYWALKING_{name}");
            if let Some((_, v)) = env.lookup(name, &[alias.as_str()]) {
                aliased.insert(format!("{}{name}", env.prefix()), v);
            }
        }
        let common_env = EnvReader::from_map(aliased).with_prefix(env.prefix());
        self.common = self.common.apply_env(&common_env, &[])?;
        if self.graphql_path.is_none() {
            self.graphql_path = env.string("GRAPHQL_PATH", &[]);
        }
        if self.otlp_endpoint.is_none() {
            self.otlp_endpoint =
                env.lookup("OTLP_ENDPOINT", VENDOR_OTLP_ENDPOINT_VARS)
                    .map(|(name, value)| {
                        if name == "SW_AGENT_COLLECTOR_BACKEND_SERVICES" {
                            collector_to_url(&value)
                        } else {
                            value
                        }
                    });
        }
        if self.otlp_protocol.is_none() {
            if let Some((name, value)) = env.lookup("OTLP_PROTOCOL", VENDOR_OTLP_PROTOCOL_VARS) {
                self.otlp_protocol = Some(value.parse().map_err(|_| {
                    Error::config(format!(
                        "invalid value in environment variable {name}: expected grpc or http/protobuf"
                    ))
                })?);
            }
        }
        if self.otlp_headers.is_none() {
            if let Some((name, value)) = env.lookup("OTLP_HEADERS", VENDOR_OTLP_HEADERS_VARS) {
                self.otlp_headers = Some(parse_otlp_headers(
                    &value,
                    &format!("environment variable {name}"),
                )?);
            }
        }
        if self.authentication.is_none() {
            self.authentication = env.secret("AUTHENTICATION", VENDOR_AUTHENTICATION_VARS);
        }
        if self.zipkin_url.is_none() {
            self.zipkin_url = env
                .string("ZIPKIN_URL", VENDOR_ZIPKIN_URL_VARS)
                .map(|v| zipkin_query_url(&v));
        }
        if self.trace_backend.is_none() {
            if let Some((name, value)) = env.lookup("TRACE_BACKEND", VENDOR_TRACE_BACKEND_VARS) {
                self.trace_backend = Some(value.parse().map_err(|_| {
                    Error::config(format!(
                        "invalid value in environment variable {name}: expected auto, zipkin or native"
                    ))
                })?);
            }
        }
        self.build()
    }
}
