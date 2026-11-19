package skywalking

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"
)

// fixture is one recorded exchange from testdata/fixtures (real OAP 10.4.0).
type fixture struct {
	Request struct {
		Method string          `json:"method"`
		URL    string          `json:"url"`
		Body   json.RawMessage `json:"body"`
	} `json:"request"`
	Response struct {
		Status  int               `json:"status"`
		Headers map[string]string `json:"headers"`
		Body    json.RawMessage   `json:"body"`
	} `json:"response"`
}

func loadFixture(t *testing.T, name string) fixture {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("testdata", "fixtures", name+".json"))
	if err != nil {
		t.Fatalf("fixture %s: %v", name, err)
	}
	var f fixture
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatalf("fixture %s: %v", name, err)
	}
	return f
}

// write replays the recorded response.
func (f fixture) write(w http.ResponseWriter) {
	for k, v := range f.Response.Headers {
		w.Header().Set(k, v)
	}
	w.WriteHeader(f.Response.Status)
	var s string
	if json.Unmarshal(f.Response.Body, &s) == nil && !strings.Contains(f.Response.Headers["content-type"], "json") {
		_, _ = io.WriteString(w, s)
		return
	}
	_, _ = w.Write(f.Response.Body)
}

// recorded is one request seen by the mock.
type recorded struct {
	Method string
	Path   string
	Query  map[string][]string
	Header http.Header
	Body   []byte
	GQL    struct {
		Query     string         `json:"query"`
		Variables map[string]any `json:"variables"`
	}
}

// mockOAP serves fixtures: GraphQL by top-level field, everything else by
// "METHOD /path". Handlers override fixtures.
type mockOAP struct {
	t        *testing.T
	srv      *httptest.Server
	mu       sync.Mutex
	gql      map[string]any // field → fixture name, fixture, or func(recorded, http.ResponseWriter)
	routes   map[string]any // "GET /path" → same
	requests []recorded
}

var firstField = regexp.MustCompile(`^\s*(?:query\s*(?:\([^)]*\))?\s*)?\{\s*(\w+)`)

func newMockOAP(t *testing.T) *mockOAP {
	t.Helper()
	m := &mockOAP{t: t, gql: map[string]any{"getTimeInfo": "ping.ok"}, routes: map[string]any{}}
	m.srv = httptest.NewServer(http.HandlerFunc(m.serve))
	t.Cleanup(m.srv.Close)
	return m
}

func (m *mockOAP) serve(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	rec := recorded{Method: r.Method, Path: r.URL.Path, Query: r.URL.Query(), Header: r.Header.Clone(), Body: body}
	var handler any
	m.mu.Lock()
	if r.Method == http.MethodPost && r.URL.Path == "/graphql" {
		_ = json.Unmarshal(body, &rec.GQL)
		if mm := firstField.FindStringSubmatch(rec.GQL.Query); mm != nil {
			handler = m.gql[mm[1]]
		}
	} else {
		handler = m.routes[r.Method+" "+r.URL.Path]
	}
	m.requests = append(m.requests, rec)
	m.mu.Unlock()
	switch h := handler.(type) {
	case string:
		loadFixture(m.t, h).write(w)
	case fixture:
		h.write(w)
	case func(recorded, http.ResponseWriter):
		h(rec, w)
	default:
		w.WriteHeader(http.StatusNotFound)
	}
}

// on registers a GraphQL field handler.
func (m *mockOAP) on(field string, h any) *mockOAP {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.gql[field] = h
	return m
}

// route registers a non-GraphQL handler ("GET /zipkin/api/v2/services").
func (m *mockOAP) route(key string, h any) *mockOAP {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.routes[key] = h
	return m
}

// last returns the last request whose GraphQL query or path contains s.
func (m *mockOAP) last(s string) recorded {
	m.t.Helper()
	m.mu.Lock()
	defer m.mu.Unlock()
	for i := len(m.requests) - 1; i >= 0; i-- {
		r := m.requests[i]
		if strings.Contains(r.GQL.Query, s) || strings.Contains(r.Path, s) {
			return r
		}
	}
	m.t.Fatalf("no request matching %q", s)
	return recorded{}
}

func (m *mockOAP) count(s string) int {
	m.mu.Lock()
	defer m.mu.Unlock()
	n := 0
	for _, r := range m.requests {
		if strings.Contains(r.GQL.Query, s) || strings.Contains(r.Path, s) {
			n++
		}
	}
	return n
}

// fixedNow is the clock of test clients (2026-11-18 21:15:00 UTC).
var fixedNow = time.Date(2026, 11, 18, 21, 15, 0, 0, time.UTC)

// client returns a client for the mock (Zipkin on /zipkin, no retries, fixed clock).
func (m *mockOAP) client(mutate ...func(*Config)) *Client {
	m.t.Helper()
	cfg := Config{CommonConfig: CommonConfig{URL: m.srv.URL, Retry: fastRetry(0)}, ZipkinURL: m.srv.URL + "/zipkin"}
	for _, f := range mutate {
		f(&cfg)
	}
	c, err := NewClient(cfg)
	if err != nil {
		m.t.Fatalf("NewClient: %v", err)
	}
	c.now = func() time.Time { return fixedNow }
	m.t.Cleanup(func() { _ = c.Close() })
	return c
}

// jsonBody writes a JSON response.
func jsonBody(status int, body string) func(recorded, http.ResponseWriter) {
	return func(_ recorded, w http.ResponseWriter) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = io.WriteString(w, body)
	}
}

// textBody writes a text response.
func textBody(status int, ctype, body string) func(recorded, http.ResponseWriter) {
	return func(_ recorded, w http.ResponseWriter) {
		w.Header().Set("Content-Type", ctype)
		w.WriteHeader(status)
		_, _ = io.WriteString(w, body)
	}
}

func mustCode(t *testing.T, err error, code ErrorCode) {
	t.Helper()
	if CodeOf(err) != code {
		t.Fatalf("want %s, got %v", code, err)
	}
}
