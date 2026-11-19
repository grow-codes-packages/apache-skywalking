package skywalking

import (
	"fmt"
	"net"
	"net/url"
	"sort"
	"strings"
)

// DefaultURL is the default base URL of the SkyWalking OAP REST/GraphQL port (12800).
const DefaultURL = "http://127.0.0.1:12800"

// Defaults derived from the OAP host when the matching Config field is empty.
const (
	// DefaultGraphQLPath is the GraphQL query path below URL.
	DefaultGraphQLPath = "/graphql"
	// DefaultOTLPPort is the OAP gRPC port that serves OTLP traces, logs and metrics.
	DefaultOTLPPort = "11800"
	// DefaultZipkinPort is the OAP Zipkin query port (SW_QUERY_ZIPKIN).
	DefaultZipkinPort = "9412"
	// DefaultZipkinPath is the path of the Zipkin query API on DefaultZipkinPort.
	DefaultZipkinPath = "/zipkin"
)

// TraceBackend selects where Traces reads spans from.
type TraceBackend string

// Trace backends. The empty value means TraceBackendAuto.
const (
	// TraceBackendAuto reads 16/32-hex trace ids (OpenTelemetry/OTLP) from the Zipkin
	// query API first and everything else (native SkyWalking ids) from GraphQL;
	// listings merge both stores.
	TraceBackendAuto TraceBackend = "auto"
	// TraceBackendZipkin reads only the Zipkin query API, where OAP stores OTLP spans.
	TraceBackendZipkin TraceBackend = "zipkin"
	// TraceBackendNative reads only native SkyWalking traces (agents, /v3/segments)
	// through GraphQL.
	TraceBackendNative TraceBackend = "native"
)

// shortPrefix is the ticket's short environment prefix, accepted as a vendor alias
// after GROW_APACHE_SKYWALKING_*.
const shortPrefix = "GROW_SKYWALKING_"

// Vendor environment variables, consulted after GROW_APACHE_SKYWALKING_<NAME> and
// GROW_SKYWALKING_<NAME> (in this order).
var (
	envGraphQLURL     = []string{"SW_GRAPHQL_URL"}
	envOAPAddress     = []string{"SW_OAP_ADDRESS"}
	envOTLPEndpoint   = []string{"OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "OTEL_EXPORTER_OTLP_ENDPOINT"}
	envCollector      = []string{"SW_AGENT_COLLECTOR_BACKEND_SERVICES"}
	envOTLPProtocol   = []string{"OTEL_EXPORTER_OTLP_TRACES_PROTOCOL", "OTEL_EXPORTER_OTLP_PROTOCOL"}
	envOTLPHeaders    = []string{"OTEL_EXPORTER_OTLP_TRACES_HEADERS", "OTEL_EXPORTER_OTLP_HEADERS"}
	envAuthentication = []string{"SW_AUTHENTICATION", "SW_AGENT_AUTHENTICATION"}
	envZipkinAddress  = []string{"SW_ZIPKIN_ADDRESS"}
)

// vendorEnv lists the vendor variables for the common fields.
var vendorEnv = VendorEnv{
	CACert:             []string{shortPrefix + "CA_CERT"},
	ClientCert:         []string{shortPrefix + "CLIENT_CERT"},
	ClientKey:          []string{shortPrefix + "CLIENT_KEY"},
	InsecureSkipVerify: []string{shortPrefix + "INSECURE_SKIP_VERIFY"},
	Proxy:              []string{shortPrefix + "PROXY"},
}

// Config configures a Client. Build it with a struct literal or ConfigFromEnv;
// zero values mean "use the default". Call Validate to check it.
//
// SkyWalking OAP has no query authentication: GraphQL and the native /v3 HTTP API are
// open. When OAP runs behind an authenticating gateway, put the gateway credentials
// in Headers; they are sent with every HTTP call and redacted everywhere.
type Config struct {
	CommonConfig

	// GraphQLPath is the GraphQL path below URL (default "/graphql").
	GraphQLPath string
	// OTLPEndpoint is the OTLP endpoint used by InitTracing and the OTLP log exporter
	// (default http://<URL host>:11800, OAP's gRPC port). Never point it at port 12800:
	// OAP 10.4 serves OTLP over gRPC only.
	OTLPEndpoint string
	// OTLPProtocol is the OTLP protocol: ProtocolGRPC (default) or ProtocolHTTPProtobuf
	// (only for an OpenTelemetry Collector in front of OAP).
	OTLPProtocol Protocol
	// OTLPHeaders are sent with every OTLP export (values redacted in String()).
	OTLPHeaders map[string]string
	// Authentication is the OAP gRPC token (SW_AUTHENTICATION on the server), sent as the
	// "authentication" metadata on OTLP exports. It does not protect GraphQL or /v3.
	Authentication Secret
	// ZipkinURL is the base URL of OAP's Zipkin query API, where OTLP spans are stored
	// (default http://<URL host>:9412/zipkin; needs SW_QUERY_ZIPKIN on the server).
	ZipkinURL string
	// TraceBackend selects the trace store read by Traces (default TraceBackendAuto).
	TraceBackend TraceBackend
}

// ConfigFromEnv reads the configuration from the process environment.
func ConfigFromEnv() (Config, error) { return ConfigFromEnvWith(Config{}, nil) }

// ConfigFromEnvWith resolves explicit values > GROW_APACHE_SKYWALKING_* >
// GROW_SKYWALKING_* > vendor variables > defaults. env replaces the process
// environment when non-nil.
//
//   - URL: URL, GRAPHQL_URL (a trailing /graphql is removed), SW_GRAPHQL_URL (same),
//     SW_OAP_ADDRESS; default http://127.0.0.1:12800.
//   - OTLPEndpoint: OTLP_ENDPOINT, OTEL_EXPORTER_OTLP_TRACES_ENDPOINT,
//     OTEL_EXPORTER_OTLP_ENDPOINT, SW_AGENT_COLLECTOR_BACKEND_SERVICES (host:port);
//     default http://<URL host>:11800.
//   - OTLPProtocol: OTLP_PROTOCOL, OTEL_EXPORTER_OTLP_TRACES_PROTOCOL,
//     OTEL_EXPORTER_OTLP_PROTOCOL; default grpc.
//   - OTLPHeaders: OTEL_EXPORTER_OTLP_TRACES_HEADERS or OTEL_EXPORTER_OTLP_HEADERS
//     (k=v,k2=v2) for keys not set explicitly.
//   - Authentication: AUTHENTICATION, SW_AUTHENTICATION, SW_AGENT_AUTHENTICATION.
//   - ZipkinURL: ZIPKIN_URL, SW_ZIPKIN_ADDRESS ("/zipkin" appended when it has no
//     path); default http://<URL host>:9412/zipkin.
//   - TraceBackend: TRACE_BACKEND (auto, zipkin, native); default auto.
//   - Timeouts, retries, TLS and proxy: the common GROW_APACHE_SKYWALKING_* variables.
func ConfigFromEnvWith(explicit Config, env map[string]string) (Config, error) {
	r := NewEnvReader(EnvPrefix, env)
	c := explicit
	if c.OTLPHeaders != nil {
		c.OTLPHeaders = copyStrings(c.OTLPHeaders)
	}
	alias := func(name string, vendor []string) []string {
		return append([]string{shortPrefix + name}, vendor...)
	}
	if c.URL == "" {
		if v, ok := r.String("URL", shortPrefix+"URL"); ok {
			c.URL = strings.TrimSpace(v)
		} else if v, ok := r.String("GRAPHQL_URL", alias("GRAPHQL_URL", envGraphQLURL)...); ok {
			base, path := SplitGraphQLURL(strings.TrimSpace(v))
			c.URL = base
			if c.GraphQLPath == "" {
				c.GraphQLPath = path
			}
		} else if v, ok := r.String("", envOAPAddress...); ok {
			c.URL = strings.TrimSpace(v)
		}
	}
	if err := c.applyEnv(r, vendorEnv); err != nil {
		return Config{}, err
	}
	if c.OTLPEndpoint == "" {
		if v, ok := r.String("OTLP_ENDPOINT", alias("OTLP_ENDPOINT", envOTLPEndpoint)...); ok {
			c.OTLPEndpoint = strings.TrimSpace(v)
		} else if v, ok := r.String("", envCollector...); ok {
			c.OTLPEndpoint = collectorEndpoint(v)
		}
	}
	if c.OTLPProtocol == "" {
		if v, ok := r.String("OTLP_PROTOCOL", alias("OTLP_PROTOCOL", envOTLPProtocol)...); ok {
			c.OTLPProtocol = Protocol(strings.ToLower(strings.TrimSpace(v)))
		}
	}
	if raw, variable, ok := r.find("", envOTLPHeaders); ok {
		parsed, err := ParseOTLPHeaders(raw)
		if err != nil {
			return Config{}, ConfigError(variable + " is not a valid header list")
		}
		for k, v := range parsed {
			if !hasHeader(c.OTLPHeaders, k) {
				if c.OTLPHeaders == nil {
					c.OTLPHeaders = map[string]string{}
				}
				c.OTLPHeaders[k] = v
			}
		}
	}
	if c.Authentication.IsZero() {
		if v, ok := r.String("AUTHENTICATION", alias("AUTHENTICATION", envAuthentication)...); ok {
			c.Authentication = NewSecret(strings.TrimSpace(v))
		}
	}
	if c.ZipkinURL == "" {
		if v, ok := r.String("ZIPKIN_URL", shortPrefix+"ZIPKIN_URL"); ok {
			c.ZipkinURL = strings.TrimSpace(v)
		} else if v, ok := r.String("", envZipkinAddress...); ok {
			c.ZipkinURL = zipkinFromAddress(strings.TrimSpace(v))
		}
	}
	if c.TraceBackend == "" {
		if v, ok := r.String("TRACE_BACKEND", shortPrefix+"TRACE_BACKEND"); ok {
			c.TraceBackend = TraceBackend(strings.ToLower(strings.TrimSpace(v)))
		}
	}
	c = c.withServiceDefaults()
	if err := c.Validate(); err != nil {
		return Config{}, err
	}
	return c, nil
}

// SplitGraphQLURL splits a GraphQL endpoint URL into the base URL and the GraphQL
// path: "http://oap:12800/graphql" → ("http://oap:12800", "/graphql"). A URL without
// a path gets DefaultGraphQLPath.
func SplitGraphQLURL(graphqlURL string) (base, path string) {
	u, err := url.Parse(strings.TrimRight(graphqlURL, "/"))
	if err != nil || u.Host == "" {
		return graphqlURL, DefaultGraphQLPath
	}
	path = u.Path
	if path == "" {
		path = DefaultGraphQLPath
	}
	u.Path, u.RawPath, u.RawQuery = "", "", ""
	return u.String(), path
}

// collectorEndpoint turns SW_AGENT_COLLECTOR_BACKEND_SERVICES ("host:port[,host:port]")
// into an OTLP endpoint URL (first entry, http://).
func collectorEndpoint(v string) string {
	first, _, _ := strings.Cut(strings.TrimSpace(v), ",")
	first = strings.TrimSpace(first)
	if strings.Contains(first, "://") {
		return first
	}
	return "http://" + first
}

// zipkinFromAddress appends /zipkin to a Zipkin query address without a path (the UI's
// SW_ZIPKIN_ADDRESS is "http://oap:9412").
func zipkinFromAddress(v string) string {
	u, err := url.Parse(v)
	if err != nil || (u.Path != "" && u.Path != "/") {
		return v
	}
	u.Path = DefaultZipkinPath
	return u.String()
}

// derivedURL returns scheme://<host of base>:port+path.
func derivedURL(base, port, path string) string {
	u, err := url.Parse(base)
	if err != nil || u.Hostname() == "" {
		u = &url.URL{Scheme: "http", Host: "127.0.0.1"}
	}
	scheme := u.Scheme
	if scheme != "https" {
		scheme = "http"
	}
	return (&url.URL{Scheme: scheme, Host: net.JoinHostPort(u.Hostname(), port), Path: path}).String()
}

// withServiceDefaults returns a copy with the common and service defaults applied.
func (c Config) withServiceDefaults() Config {
	c.CommonConfig = c.withDefaults(DefaultURL)
	if c.GraphQLPath == "" {
		c.GraphQLPath = DefaultGraphQLPath
	}
	if c.OTLPEndpoint == "" {
		c.OTLPEndpoint = derivedURL(c.URL, DefaultOTLPPort, "")
	}
	if c.OTLPProtocol == "" {
		c.OTLPProtocol = ProtocolGRPC
	}
	if c.ZipkinURL == "" {
		c.ZipkinURL = derivedURL(c.URL, DefaultZipkinPort, DefaultZipkinPath)
	}
	if c.TraceBackend == "" {
		c.TraceBackend = TraceBackendAuto
	}
	return c
}

// Validate checks the configuration and returns a CONFIG error.
func (c Config) Validate() error {
	d := c.withServiceDefaults()
	if err := d.validate(); err != nil {
		return err
	}
	if !strings.HasPrefix(d.GraphQLPath, "/") {
		return ConfigError("graphqlPath must start with /")
	}
	for name, raw := range map[string]string{"otlpEndpoint": d.OTLPEndpoint, "zipkinUrl": d.ZipkinURL} {
		u, err := url.Parse(raw)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			return ConfigError(name + " must be an absolute http or https URL")
		}
	}
	switch d.OTLPProtocol {
	case ProtocolGRPC, ProtocolHTTPProtobuf:
	default:
		return ConfigError("otlpProtocol must be grpc or http/protobuf")
	}
	switch d.TraceBackend {
	case TraceBackendAuto, TraceBackendZipkin, TraceBackendNative:
	default:
		return ConfigError("traceBackend must be auto, zipkin or native")
	}
	return nil
}

// GraphQLURL returns the full GraphQL endpoint URL.
func (c Config) GraphQLURL() string {
	d := c.withServiceDefaults()
	return strings.TrimRight(d.URL, "/") + d.GraphQLPath
}

// String renders the configuration with every credential redacted.
func (c Config) String() string {
	return fmt.Sprintf("Config{%s GraphQLPath:%s OTLPEndpoint:%s OTLPProtocol:%s OTLPHeaders:{%s} Authentication:%s ZipkinURL:%s TraceBackend:%s}",
		c.describe(), c.GraphQLPath, RedactURL(c.OTLPEndpoint), string(c.OTLPProtocol), describeHeaders(c.OTLPHeaders),
		c.Authentication, RedactURL(c.ZipkinURL), string(c.TraceBackend))
}

// GoString renders the configuration with every credential redacted.
func (c Config) GoString() string { return c.String() }

func describeHeaders(h map[string]string) string {
	red := RedactHeaderMap(h)
	keys := make([]string, 0, len(red))
	for k := range red {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	parts := make([]string, len(keys))
	for i, k := range keys {
		parts[i] = k + "=" + red[k]
	}
	return strings.Join(parts, " ")
}

// ParseOTLPHeaders parses the OTEL_EXPORTER_OTLP_HEADERS format: comma-separated
// key=value pairs with percent-encoded values. Empty input gives an empty map.
func ParseOTLPHeaders(raw string) (map[string]string, error) {
	out := map[string]string{}
	for _, part := range strings.Split(raw, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		k, v, ok := strings.Cut(part, "=")
		k = strings.TrimSpace(k)
		if !ok || k == "" {
			return nil, ValidationError("otlp headers", "entries must be key=value")
		}
		dv, err := url.PathUnescape(strings.TrimSpace(v))
		if err != nil {
			return nil, ValidationError("otlp headers", "invalid percent-encoding")
		}
		out[k] = dv
	}
	return out, nil
}

func hasHeader(h map[string]string, name string) bool {
	for k := range h {
		if strings.EqualFold(k, name) {
			return true
		}
	}
	return false
}

func copyStrings(m map[string]string) map[string]string {
	out := make(map[string]string, len(m))
	for k, v := range m {
		out[k] = v
	}
	return out
}
