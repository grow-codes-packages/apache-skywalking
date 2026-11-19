package skywalking

import (
	"strings"
	"testing"
)

func TestConfigDefaults(t *testing.T) {
	c, err := ConfigFromEnvWith(Config{}, map[string]string{})
	if err != nil {
		t.Fatal(err)
	}
	if c.URL != DefaultURL || c.GraphQLPath != "/graphql" || c.OTLPEndpoint != "http://127.0.0.1:11800" ||
		c.OTLPProtocol != ProtocolGRPC || c.ZipkinURL != "http://127.0.0.1:9412/zipkin" || c.TraceBackend != TraceBackendAuto {
		t.Fatalf("defaults: %s", c)
	}
	if c.GraphQLURL() != "http://127.0.0.1:12800/graphql" {
		t.Fatal(c.GraphQLURL())
	}
	// derived endpoints follow the URL host and scheme
	c, _ = ConfigFromEnvWith(Config{CommonConfig: CommonConfig{URL: "https://oap.example:443/"}}, map[string]string{})
	if c.OTLPEndpoint != "https://oap.example:11800" || c.ZipkinURL != "https://oap.example:9412/zipkin" {
		t.Fatal(c.OTLPEndpoint, c.ZipkinURL)
	}
	if got := derivedURL("::bad", "1", ""); got != "http://127.0.0.1:1" {
		t.Fatal(got)
	}
}

func TestConfigEnvPrecedence(t *testing.T) {
	t.Run("core.config.env-precedence", func(t *testing.T) {
		env := map[string]string{
			"GROW_APACHE_SKYWALKING_URL": "http://grow:12800",
			"GROW_SKYWALKING_URL":        "http://short:12800",
			"SW_GRAPHQL_URL":             "http://vendor:12800/graphql",
			"SW_OAP_ADDRESS":             "http://ui:12800",
		}
		c, err := ConfigFromEnvWith(Config{}, env)
		if err != nil || c.URL != "http://grow:12800" {
			t.Fatalf("%v %s", err, c.URL)
		}
		c, _ = ConfigFromEnvWith(Config{CommonConfig: CommonConfig{URL: "http://explicit:1"}}, env)
		if c.URL != "http://explicit:1" {
			t.Fatal(c.URL)
		}
		delete(env, "GROW_APACHE_SKYWALKING_URL")
		c, _ = ConfigFromEnvWith(Config{}, env)
		if c.URL != "http://short:12800" {
			t.Fatal(c.URL)
		}
		delete(env, "GROW_SKYWALKING_URL")
		c, _ = ConfigFromEnvWith(Config{}, env)
		if c.URL != "http://vendor:12800" || c.GraphQLPath != "/graphql" {
			t.Fatal(c.URL, c.GraphQLPath)
		}
		delete(env, "SW_GRAPHQL_URL")
		c, _ = ConfigFromEnvWith(Config{}, env)
		if c.URL != "http://ui:12800" {
			t.Fatal(c.URL)
		}
	})
	t.Run("graphql url with custom path", func(t *testing.T) {
		c, err := ConfigFromEnvWith(Config{}, map[string]string{"GROW_APACHE_SKYWALKING_GRAPHQL_URL": "http://gw:8080/sw/graphql"})
		if err != nil || c.URL != "http://gw:8080" || c.GraphQLPath != "/sw/graphql" {
			t.Fatalf("%v %s %s", err, c.URL, c.GraphQLPath)
		}
		base, path := SplitGraphQLURL("http://oap:12800")
		if base != "http://oap:12800" || path != "/graphql" {
			t.Fatal(base, path)
		}
		base, path = SplitGraphQLURL("not a url")
		if base != "not a url" || path != "/graphql" {
			t.Fatal(base, path)
		}
	})
	t.Run("otlp and zipkin variables", func(t *testing.T) {
		env := map[string]string{
			"OTEL_EXPORTER_OTLP_ENDPOINT":         "http://collector:4317",
			"SW_AGENT_COLLECTOR_BACKEND_SERVICES": "oap:11800,oap2:11800",
			"OTEL_EXPORTER_OTLP_PROTOCOL":         "GRPC",
			"OTEL_EXPORTER_OTLP_HEADERS":          "x-a=1,x-b=hello%20world",
			"SW_AUTHENTICATION":                   "sw-token-1",
			"SW_ZIPKIN_ADDRESS":                   "http://oap:9412",
			"GROW_SKYWALKING_TRACE_BACKEND":       "Native",
		}
		c, err := ConfigFromEnvWith(Config{OTLPHeaders: map[string]string{"X-A": "explicit"}}, env)
		if err != nil {
			t.Fatal(err)
		}
		if c.OTLPEndpoint != "http://collector:4317" || c.OTLPProtocol != ProtocolGRPC || c.ZipkinURL != "http://oap:9412/zipkin" ||
			c.TraceBackend != TraceBackendNative || c.Authentication.Reveal() != "sw-token-1" {
			t.Fatalf("%s", c)
		}
		if c.OTLPHeaders["X-A"] != "explicit" || c.OTLPHeaders["x-b"] != "hello world" || len(c.OTLPHeaders) != 2 {
			t.Fatal(c.OTLPHeaders)
		}
		delete(env, "OTEL_EXPORTER_OTLP_ENDPOINT")
		env["SW_ZIPKIN_ADDRESS"] = "http://oap:9412/custom"
		c, _ = ConfigFromEnvWith(Config{}, env)
		if c.OTLPEndpoint != "http://oap:11800" || c.ZipkinURL != "http://oap:9412/custom" {
			t.Fatal(c.OTLPEndpoint, c.ZipkinURL)
		}
		env["SW_AGENT_COLLECTOR_BACKEND_SERVICES"] = "https://oap:11800"
		env["GROW_APACHE_SKYWALKING_ZIPKIN_URL"] = "http://z:9412/zipkin"
		env["GROW_APACHE_SKYWALKING_AUTHENTICATION"] = "grow-token"
		c, _ = ConfigFromEnvWith(Config{}, env)
		if c.OTLPEndpoint != "https://oap:11800" || c.ZipkinURL != "http://z:9412/zipkin" || c.Authentication.Reveal() != "grow-token" {
			t.Fatal(c.OTLPEndpoint, c.ZipkinURL)
		}
		if zipkinFromAddress("%zz") != "%zz" {
			t.Fatal("bad address must pass through")
		}
	})
	t.Run("invalid values are config errors naming the variable", func(t *testing.T) {
		cases := []map[string]string{
			{"OTEL_EXPORTER_OTLP_HEADERS": "novalue"},
			{"OTEL_EXPORTER_OTLP_HEADERS": "k=%zz"},
			{"GROW_APACHE_SKYWALKING_OTLP_PROTOCOL": "thrift"},
			{"GROW_APACHE_SKYWALKING_TRACE_BACKEND": "jaeger"},
			{"GROW_APACHE_SKYWALKING_ZIPKIN_URL": "ftp://x"},
			{"GROW_APACHE_SKYWALKING_REQUEST_TIMEOUT": "soon"},
		}
		for i, env := range cases {
			_, err := ConfigFromEnvWith(Config{}, env)
			mustCode(t, err, ErrorCodeConfig)
			for _, v := range env {
				if strings.Contains(err.Error(), v) {
					t.Errorf("case %d leaks value: %v", i, err)
				}
			}
		}
	})
}

func TestConfigValidateAndString(t *testing.T) {
	bad := []Config{
		{GraphQLPath: "graphql"},
		{OTLPEndpoint: "oap:11800"},
		{CommonConfig: CommonConfig{URL: "oap"}},
	}
	for i, c := range bad {
		if CodeOf(c.Validate()) != ErrorCodeConfig {
			t.Errorf("case %d accepted", i)
		}
	}
	if _, err := NewClient(Config{GraphQLPath: "x"}); CodeOf(err) != ErrorCodeConfig {
		t.Fatal(err)
	}
	c := Config{
		Authentication: NewSecret("sw-secret-token"),
		OTLPHeaders:    map[string]string{"Authorization": "Bearer abc-secret", "x-tenant": "t1"},
	}
	s := c.String() + c.GoString()
	if strings.Contains(s, "sw-secret-token") || strings.Contains(s, "abc-secret") || !strings.Contains(s, "x-tenant=t1") {
		t.Fatal(s)
	}
	h, err := ParseOTLPHeaders(" , a = b ")
	if err != nil || h["a"] != "b" {
		t.Fatal(h, err)
	}
	if collectorEndpoint(" oap:11800 ") != "http://oap:11800" {
		t.Fatal("collector")
	}
}

func TestNewClientFromEnv(t *testing.T) {
	t.Setenv("GROW_APACHE_SKYWALKING_URL", "http://oap.test:12800")
	c, err := NewClientFromEnv()
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()
	if c.Config().URL != "http://oap.test:12800" || c.Config().OTLPEndpoint != "http://oap.test:11800" {
		t.Fatal(c.Config())
	}
	t.Setenv("GROW_APACHE_SKYWALKING_URL", "ftp://bad")
	if _, err := NewClientFromEnv(); CodeOf(err) != ErrorCodeConfig {
		t.Fatal(err)
	}
}
