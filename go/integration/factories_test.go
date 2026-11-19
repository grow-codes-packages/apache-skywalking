package integration

import (
	"context"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	skywalking "github.com/grow-codes-packages/apache-skywalking/go"
	"github.com/grow-codes-packages/apache-skywalking/go/testkit"
)

// capabilities the deployment under test offers (see conformance "requires").
var capabilities = []string{"version", "query", "services"}

// categories runs "core" plus this service's categories.
func categories() []string {
	return append([]string{"core"}, strings.Split("logging,tracing", ",")...)
}

// metadataDelay is how long OAP may take to list a new service: the Zipkin name index
// and the native metadata are flushed about 30-35 s after the first data.
const metadataDelay = 120 * time.Second

// visibleQuery polls ListServices until the scenario's service is listed (the
// conformance runner calls it once).
type visibleQuery struct {
	*skywalking.Traces
	service string
}

func (q visibleQuery) ListServices(ctx context.Context) ([]string, error) {
	return skywalking.PollUntil(ctx, "listServices", metadataDelay, time.Second, func(ctx context.Context) ([]string, bool, error) {
		svcs, err := q.Traces.ListServices(ctx)
		return svcs, err == nil && slices.Contains(svcs, q.service), err
	})
}

var warmUp sync.Once

// warmUpTracing sends one span for serviceName and waits until OAP lists the service,
// so the tracing.services scenario does not race the ~35 s metadata flush.
func warmUpTracing(t *testing.T, client *skywalking.Client, serviceName string) {
	warmUp.Do(func() {
		ctx, cancel := context.WithTimeout(context.Background(), metadataDelay+30*time.Second)
		defer cancel()
		h, err := client.InitTracing(ctx, skywalking.TracingConfig{ServiceName: serviceName, DisableSetGlobal: true})
		if err != nil {
			t.Logf("warm-up: %v", err)
			return
		}
		_, span := h.Tracer("warm-up").Start(ctx, "warm-up")
		span.End()
		_ = h.Shutdown(ctx)
		if _, err := (visibleQuery{Traces: client.Traces(), service: serviceName}).ListServices(ctx); err != nil {
			t.Logf("warm-up: %v", err)
		}
	})
}

// factories builds the conformance targets.
func factories(t *testing.T) testkit.Factories {
	t.Helper()
	return testkit.Factories{
		"core": testkit.CoreFactory(func(context.Context) (testkit.CoreTarget, error) {
			return skywalking.NewClientFromEnv()
		}),
		"tracing": testkit.TracingFactory(func(ctx context.Context, serviceName string) (testkit.TracingTarget, error) {
			client, err := skywalking.NewClientFromEnv()
			if err != nil {
				return testkit.TracingTarget{}, err
			}
			t.Cleanup(func() { _ = client.Close() })
			warmUpTracing(t, client, serviceName)
			h, err := client.InitTracing(ctx, skywalking.TracingConfig{
				ServiceName:      serviceName,
				DisableSetGlobal: true,
				Batch:            skywalking.TracingBatchOptions{ScheduledDelay: 200 * time.Millisecond},
			})
			if err != nil {
				return testkit.TracingTarget{}, err
			}
			return testkit.TracingTarget{Handle: h, Query: visibleQuery{Traces: client.Traces(), service: serviceName}}, nil
		}),
		"logging": testkit.LoggingFactory(func(_ context.Context, opts skywalking.BatchOptions) (testkit.LoggingTarget, error) {
			client, err := skywalking.NewClientFromEnv()
			if err != nil {
				return testkit.LoggingTarget{}, err
			}
			t.Cleanup(func() { _ = client.Close() })
			lc, err := client.NewLogClient(skywalking.LogClientOptions{
				Batch:    opts,
				Resource: map[string]skywalking.AttrValue{"service.name": "grow-conformance-go-logs"},
			})
			if err != nil {
				return testkit.LoggingTarget{}, err
			}
			return testkit.LoggingTarget{Client: lc, Query: client.Logs()}, nil
		}),
	}
}
