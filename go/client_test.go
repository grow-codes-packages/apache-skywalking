package skywalking

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestHealth(t *testing.T) {
	t.Run("healthcheck 200", func(t *testing.T) {
		m := newMockOAP(t).route("GET /healthcheck", "health.http")
		hs, err := m.client().Health(t.Context())
		if err != nil || !hs.Healthy || hs.Status != "ok" || hs.Details["httpStatus"] != "200" {
			t.Fatalf("%+v %v", hs, err)
		}
	})
	t.Run("healthcheck 503", func(t *testing.T) {
		m := newMockOAP(t).route("GET /healthcheck", textBody(503, "text/plain", "storage down\nmore"))
		hs, err := m.client().Health(t.Context())
		if err != nil || hs.Healthy || hs.Status != "unhealthy" || hs.Details["details"] != "storage down" {
			t.Fatalf("%+v %v", hs, err)
		}
		m.route("GET /healthcheck", textBody(503, "text/plain", ""))
		hs, _ = m.client().Health(t.Context())
		if hs.Healthy || hs.Details["details"] != "" {
			t.Fatalf("%+v", hs)
		}
	})
	t.Run("graphql fallback", func(t *testing.T) {
		m := newMockOAP(t).on("checkHealth", "health.graphql")
		hs, err := m.client().Health(t.Context())
		if err != nil || !hs.Healthy || hs.Details["score"] != "0" {
			t.Fatalf("%+v %v", hs, err)
		}
		m.on("checkHealth", jsonBody(200, `{"data":{"checkHealth":{"score":2,"details":"banyandb unreachable"}}}`))
		hs, _ = m.client().Health(t.Context())
		if hs.Healthy || hs.Status != "unhealthy" || hs.Details["details"] != "banyandb unreachable" {
			t.Fatalf("%+v", hs)
		}
		m.on("checkHealth", jsonBody(500, `{}`))
		hs, err = m.client().Health(t.Context())
		if err != nil || hs.Status != "unreachable" {
			t.Fatalf("%+v %v", hs, err)
		}
	})
	t.Run("unreachable and cancelled", func(t *testing.T) {
		c, err := NewClient(Config{CommonConfig: CommonConfig{URL: "http://127.0.0.1:1", Retry: fastRetry(0)}})
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = c.Close() }()
		hs, err := c.Health(t.Context())
		if err != nil || hs.Status != "unreachable" || hs.Healthy {
			t.Fatalf("%+v %v", hs, err)
		}
		ctx, cancel := context.WithCancel(t.Context())
		cancel()
		_, err = c.Health(ctx)
		mustCode(t, err, ErrorCodeCancelled)
	})
}

func TestPingVersionCapabilities(t *testing.T) {
	m := newMockOAP(t).on("version", "serverVersion.ok").on("hasQueryTracesV2Support", "capabilities.ok")
	c := m.client()
	d, err := c.Ping(t.Context())
	if err != nil || d <= 0 {
		t.Fatal(d, err)
	}
	r := m.last("getTimeInfo")
	if r.Header.Get("Content-Type") != "application/json" || !strings.HasPrefix(r.Header.Get("User-Agent"), "grow-codes-apache-skywalking/") {
		t.Fatal(r.Header)
	}
	v, err := c.ServerVersion(t.Context())
	if err != nil || v != "10.4.0-b272da3" {
		t.Fatal(v, err)
	}
	caps, err := c.Capabilities(t.Context())
	if err != nil || !caps.QueryTracesV2 || caps.LogsByKeywords {
		t.Fatal(caps, err)
	}
	_, _ = c.Capabilities(t.Context())
	if m.count("hasQueryTracesV2Support") != 1 {
		t.Fatal("capabilities not cached")
	}
	ti, err := c.TimeInfo(t.Context())
	if err != nil || ti.Timezone != "+0000" || ti.CurrentTime.UnixMilli() != 1795036301381 {
		t.Fatal(ti, err)
	}

	m.on("version", jsonBody(200, `{"data":{"version":""}}`))
	_, err = c.ServerVersion(t.Context())
	mustCode(t, err, ErrorCodeUnsupported)

	m2 := newMockOAP(t).on("hasQueryTracesV2Support", "graphql.validation-error")
	_, err = m2.client().Capabilities(t.Context())
	mustCode(t, err, ErrorCodeValidation)
	m2.on("hasQueryTracesV2Support", "queryBasicTraces.unsupported")
	_, err = m2.client().Capabilities(t.Context())
	mustCode(t, err, ErrorCodeUnsupported)
	m2.on("hasQueryTracesV2Support", jsonBody(200, `{"data":null}`))
	_, err = m2.client().Capabilities(t.Context())
	mustCode(t, err, ErrorCodeUnavailable)
}

func TestPingErrors(t *testing.T) {
	m := newMockOAP(t).on("getTimeInfo", textBody(401, "text/plain", "gateway says no"))
	_, err := m.client().Ping(t.Context())
	mustCode(t, err, ErrorCodeAuthentication)
	if !strings.Contains(err.Error(), "gateway says no") {
		t.Fatal(err)
	}
}

func TestGraphQLRaw(t *testing.T) {
	m := newMockOAP(t).on("version", "serverVersion.ok")
	c := m.client()
	res, err := c.GraphQL(t.Context(), "{version}", nil)
	if err != nil || res.Err("x") != nil {
		t.Fatal(err)
	}
	var data struct {
		Version string `json:"version"`
	}
	if err := res.Decode(&data); err != nil || data.Version != "10.4.0-b272da3" {
		t.Fatal(data, err)
	}
	if got := m.last("version").GQL.Variables; got == nil || len(got) != 0 {
		t.Fatalf("variables must be an empty object: %v", got)
	}

	t.Run("validation error is HTTP 400", func(t *testing.T) {
		m.on("nope", "graphql.validation-error")
		_, err := c.GraphQL(t.Context(), "{nope}", map[string]any{"a": 1})
		mustCode(t, err, ErrorCodeValidation)
		if !strings.Contains(err.Error(), "FieldUndefined") || asError(t, err).HTTPStatus != 400 {
			t.Fatal(err)
		}
	})
	t.Run("fetch error returns partial data", func(t *testing.T) {
		m.on("getGlobalTopology", "graphql.fetch-error")
		res, err := c.GraphQL(t.Context(), "query($d:Duration!){getGlobalTopology(duration:$d){nodes{id}}}", nil)
		if err != nil || len(res.Errors) != 1 || res.Errors[0].Extensions["classification"] != "DataFetchingException" {
			t.Fatal(res, err)
		}
		mustCode(t, res.Err("topology"), ErrorCodeValidation)
		_, err = c.Query().Topology(t.Context(), TimeRange{}, TopologyOptions{})
		mustCode(t, err, ErrorCodeValidation)
	})
	t.Run("bad json and wrong method are validation errors", func(t *testing.T) {
		m.on("broken", "graphql.bad-json")
		_, err := c.GraphQL(t.Context(), "{broken}", nil)
		mustCode(t, err, ErrorCodeValidation)
		if !strings.Contains(err.Error(), "Failed to parse") {
			t.Fatal(err)
		}
		m.on("method", "graphql.get-405")
		_, err = c.GraphQL(t.Context(), "{method}", nil)
		mustCode(t, err, ErrorCodeValidation)
	})
	t.Run("required query and invalid bodies", func(t *testing.T) {
		_, err := c.GraphQL(t.Context(), " ", nil)
		mustCode(t, err, ErrorCodeValidation)
		m.on("garbage", textBody(200, "application/json", "not json"))
		_, err = c.GraphQL(t.Context(), "{garbage}", nil)
		mustCode(t, err, ErrorCodeUnavailable)
		var x map[string]any
		mustCode(t, (*GraphQLResponse)(nil).Decode(&x), ErrorCodeUnavailable)
		mustCode(t, (&GraphQLResponse{Data: json.RawMessage(`[`)}).Decode(&x), ErrorCodeUnavailable)
		if (*GraphQLResponse)(nil).Err("x") != nil {
			t.Fatal("nil response has no error")
		}
		// typed calls: missing field and wrong type
		m.on("version", jsonBody(200, `{"data":{"other":1}}`))
		_, err = c.ServerVersion(t.Context())
		mustCode(t, err, ErrorCodeUnavailable)
		m.on("version", jsonBody(200, `{"data":{"version":5}}`))
		_, err = c.ServerVersion(t.Context())
		mustCode(t, err, ErrorCodeUnavailable)
		m.on("version", jsonBody(200, `{"data":[1]}`))
		_, err = c.ServerVersion(t.Context())
		mustCode(t, err, ErrorCodeUnavailable)
	})
	t.Run("secrets are redacted from graphql errors", func(t *testing.T) {
		mc := newMockOAP(t).on("leak", jsonBody(200, `{"errors":[{"message":"bad token sw-secret-1"}],"data":null}`))
		cc := mc.client(func(cfg *Config) { cfg.Authentication = NewSecret("sw-secret-1") })
		res, err := cc.GraphQL(t.Context(), "{leak}", nil)
		if err != nil || strings.Contains(res.Errors[0].Message, "sw-secret-1") {
			t.Fatal(res, err)
		}
	})
}

func TestTimezoneAndDuration(t *testing.T) {
	m := newMockOAP(t).on("getTimeInfo", jsonBody(200, `{"data":{"getTimeInfo":{"timezone":"+0530","currentTimestamp":1}}}`)).
		on("queryTraceTagAutocompleteKeys", "queryTraceTagAutocompleteKeys.ok")
	c := m.client()
	start := time.Date(2026, 11, 18, 20, 40, 0, 0, time.UTC)
	keys, err := c.Query().ListTraceTagKeys(t.Context(), TimeRange{Start: start, End: fixedNow})
	if err != nil || strings.Join(keys, ",") != "http.method,http.status_code,run_id" {
		t.Fatal(keys, err)
	}
	d := m.last("queryTraceTagAutocompleteKeys").GQL.Variables["d"].(map[string]any)
	if d["start"] != "2026-11-19 0210" || d["end"] != "2026-11-19 0245" || d["step"] != "MINUTE" {
		t.Fatal(d)
	}
	_, _ = c.Query().ListTraceTagKeys(t.Context(), TimeRange{})
	if m.count("getTimeInfo") != 1 {
		t.Fatal("timezone not cached")
	}
	for _, tc := range []struct {
		step Step
		want string
	}{{StepDay, "2026-11-18"}, {StepHour, "2026-11-18 21"}, {StepSecond, "2026-11-18 211500"}} {
		v, err := durationVar("op", TimeRange{End: fixedNow, Step: tc.step}, time.UTC, fixedNow)
		if err != nil || v["end"] != tc.want {
			t.Errorf("%s: %v %v", tc.step, v, err)
		}
	}
	_, err = durationVar("op", TimeRange{Step: "WEEK"}, time.UTC, fixedNow)
	mustCode(t, err, ErrorCodeValidation)
	_, err = durationVar("op", TimeRange{Start: fixedNow, End: fixedNow.Add(-time.Hour)}, time.UTC, fixedNow)
	mustCode(t, err, ErrorCodeValidation)
	for _, bad := range []string{"UTC", "+9900", "+00x0", "+0099"} {
		if _, err := parseOffset(bad); err == nil {
			t.Errorf("%s accepted", bad)
		}
	}
	if loc, err := parseOffset("-0800"); err != nil {
		t.Fatal(err)
	} else if _, off := fixedNow.In(loc).Zone(); off != -8*3600 {
		t.Fatal(off)
	}
	// unknown timezone format: UTC; time info failure: error
	m2 := newMockOAP(t).on("getTimeInfo", jsonBody(200, `{"data":{"getTimeInfo":{"timezone":"Z","currentTimestamp":1}}}`)).
		on("queryLogTagAutocompleteKeys", "queryLogTagAutocompleteKeys.ok")
	if _, err := m2.client().Query().ListLogTagKeys(t.Context(), TimeRange{}); err != nil {
		t.Fatal(err)
	}
	m2.on("getTimeInfo", jsonBody(500, `{}`))
	_, err = m2.client().Query().ListLogTagKeys(t.Context(), TimeRange{})
	mustCode(t, err, ErrorCodeUnavailable)
}

func TestCloseIdempotent(t *testing.T) {
	m := newMockOAP(t)
	c := m.client()
	first, second := c.Close(), c.Close()
	if first != nil || second != nil {
		t.Fatal("close")
	}
	_, err := c.Ping(t.Context())
	mustCode(t, err, ErrorCodeCancelled)
}

func TestZipkinTextErrors(t *testing.T) {
	rt := textErrorTransport{base: roundTripFunc(func(*http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: 400, Header: http.Header{"Content-Type": {"text/*"}}}, nil
	})}
	res, err := rt.RoundTrip(&http.Request{})
	if err != nil || res.Header.Get("Content-Type") != "text/plain; charset=utf-8" {
		t.Fatal(res, err)
	}
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
