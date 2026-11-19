package skywalking

import (
	"context"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Client is the Apache SkyWalking client. It is safe for concurrent use.
//
// It talks to OAP's REST/GraphQL port (Config.URL) and, for OTLP spans, to OAP's
// Zipkin query API (Config.ZipkinURL). Query returns the GraphQL query client,
// Traces the TraceQueryClient, Logs the LogQueryClient; NewLogClient sends logs.
type Client struct {
	cfg       Config
	transport *httpTransport
	zipkin    *httpTransport
	closeOnce sync.Once

	tzMu sync.Mutex
	tz   *time.Location // OAP time zone once known

	capsMu sync.Mutex
	caps   *Capabilities

	now func() time.Time
}

// NewClient validates cfg and returns a client.
func NewClient(cfg Config) (*Client, error) {
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	cfg = cfg.withServiceDefaults()
	t, err := newHTTPTransport(cfg.CommonConfig)
	if err != nil {
		return nil, err
	}
	zcfg := cfg.CommonConfig
	zcfg.URL = cfg.ZipkinURL
	z, err := newHTTPTransport(zcfg)
	if err != nil {
		t.close()
		return nil, err
	}
	z.client.Transport = textErrorTransport{base: z.client.Transport}
	for _, tr := range []*httpTransport{t, z} {
		tr.addSecret(cfg.Authentication.Reveal())
		for k, v := range cfg.OTLPHeaders {
			if IsSensitiveHeader(k) {
				tr.addSecret(v)
			}
		}
	}
	return &Client{cfg: cfg, transport: t, zipkin: z, now: time.Now}, nil
}

// NewClientFromEnv builds a client from the environment (see ConfigFromEnv).
func NewClientFromEnv() (*Client, error) {
	cfg, err := ConfigFromEnv()
	if err != nil {
		return nil, err
	}
	return NewClient(cfg)
}

// textErrorTransport rewrites the "content-type: text/*" of Zipkin error responses to
// text/plain so that their message ends up in the SDK error.
type textErrorTransport struct{ base http.RoundTripper }

// RoundTrip implements http.RoundTripper.
func (t textErrorTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	resp, err := t.base.RoundTrip(req)
	if err == nil && resp.StatusCode >= 400 && resp.Header.Get("Content-Type") == "text/*" {
		resp.Header.Set("Content-Type", "text/plain; charset=utf-8")
	}
	return resp, err
}

// Config returns the effective configuration (defaults applied).
func (c *Client) Config() Config { return c.cfg }

// Health checks liveness with GET /healthcheck (needs SW_HEALTH_CHECKER on the server).
// When that route does not exist it falls back to GraphQL checkHealth: score 0 is
// healthy ("ok"), anything else "unhealthy" with the server details. It reports an
// unreachable or unhealthy service in the result; it fails only for CANCELLED and
// CONFIG.
func (c *Client) Health(ctx context.Context) (HealthStatus, error) {
	res, err := c.transport.do(ctx, &request{Method: http.MethodGet, Path: "/healthcheck", Operation: "health", NoRetry: true, RawStatus: true})
	if err != nil {
		return unreachable(err)
	}
	details := map[string]string{"httpStatus": strconv.Itoa(res.Status)}
	switch {
	case res.Status >= 200 && res.Status < 300:
		return HealthStatus{Healthy: true, Status: "ok", Details: details}, nil
	case res.Status == http.StatusNotFound || res.Status == http.StatusMethodNotAllowed:
		return c.graphQLHealth(ctx)
	}
	if body := strings.TrimSpace(string(res.Body)); body != "" {
		line, _, _ := strings.Cut(body, "\n")
		details["details"] = c.transport.redact(truncate(line, maxVendorText))
	}
	return HealthStatus{Healthy: false, Status: "unhealthy", Details: details}, nil
}

func unreachable(err error) (HealthStatus, error) {
	switch CodeOf(err) {
	case ErrorCodeCancelled, ErrorCodeConfig:
		return HealthStatus{}, err
	}
	return HealthStatus{Healthy: false, Status: "unreachable", Details: map[string]string{"error": err.Error()}}, nil
}

func (c *Client) graphQLHealth(ctx context.Context) (HealthStatus, error) {
	var out struct {
		Score   int    `json:"score"`
		Details string `json:"details"`
	}
	if err := c.gql(ctx, "health", "{checkHealth{score details}}", nil, "checkHealth", &out); err != nil {
		return unreachable(err)
	}
	hs := HealthStatus{Healthy: out.Score == 0, Status: "ok", Details: map[string]string{"score": strconv.Itoa(out.Score)}}
	if out.Details != "" {
		hs.Details["details"] = c.transport.redact(out.Details)
	}
	if !hs.Healthy {
		hs.Status = "unhealthy"
	}
	return hs, nil
}

// Ping makes a GraphQL round trip (getTimeInfo) and returns its latency. OAP itself
// has no query authentication; behind a gateway it proves the credentials in Headers
// (AUTHENTICATION error on HTTP 401). It also caches the OAP time zone.
func (c *Client) Ping(ctx context.Context) (time.Duration, error) {
	start := time.Now()
	if _, err := c.timeInfo(ctx); err != nil {
		return 0, err
	}
	return time.Since(start), nil
}

// ServerVersion returns the OAP version from GraphQL {version}, for example
// "10.4.0-b272da3".
func (c *Client) ServerVersion(ctx context.Context) (string, error) {
	var v string
	if err := c.gql(ctx, "serverVersion", "{version}", nil, "version", &v); err != nil {
		return "", err
	}
	if v == "" {
		return "", UnsupportedError("serverVersion", "the server exposes no version")
	}
	return v, nil
}

// GraphQL runs a raw GraphQL query (same as Query().GraphQL).
func (c *Client) GraphQL(ctx context.Context, query string, variables map[string]any) (*GraphQLResponse, error) {
	return c.Query().GraphQL(ctx, query, variables)
}

// Capabilities are the optional query features of the connected OAP.
type Capabilities struct {
	// QueryTracesV2 is true when queryTraces (whole traces) is available.
	QueryTracesV2 bool `json:"queryTracesV2"`
	// LogsByKeywords is true when the storage filters logs by content keywords
	// (false on BanyanDB: keywordsOfContent is ignored).
	LogsByKeywords bool `json:"logsByKeywords"`
}

// capabilities probes the optional query features once and caches the answer
// (QueryClient.Capabilities).
func (c *Client) capabilities(ctx context.Context) (Capabilities, error) {
	c.capsMu.Lock()
	defer c.capsMu.Unlock()
	if c.caps != nil {
		return *c.caps, nil
	}
	res, err := c.graphql(ctx, "capabilities", "{hasQueryTracesV2Support supportQueryLogsByKeywords}", nil)
	if err != nil {
		return Capabilities{}, err
	}
	if err := res.Err("capabilities"); err != nil {
		return Capabilities{}, err
	}
	var out struct {
		V2       bool `json:"hasQueryTracesV2Support"`
		Keywords bool `json:"supportQueryLogsByKeywords"`
	}
	if err := res.Decode(&out); err != nil {
		return Capabilities{}, err
	}
	c.caps = &Capabilities{QueryTracesV2: out.V2, LogsByKeywords: out.Keywords}
	return *c.caps, nil
}

// Close releases resources. It is idempotent; later calls fail with CANCELLED.
func (c *Client) Close() error {
	c.closeOnce.Do(func() {
		c.transport.close()
		c.zipkin.close()
	})
	return nil
}
