package skywalking

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// GraphQLError is one entry of a GraphQL "errors" array.
type GraphQLError struct {
	// Message is the server's error text.
	Message string `json:"message"`
	// Path is the response path of the failing field (strings and indexes).
	Path []any `json:"path,omitempty"`
	// Extensions holds vendor details (OAP: "classification").
	Extensions map[string]any `json:"extensions,omitempty"`
}

// GraphQLResponse is a raw GraphQL response. Data and Errors may both be set
// (partial results).
type GraphQLResponse struct {
	// Data is the "data" member as raw JSON (nil when absent or null).
	Data json.RawMessage `json:"data,omitempty"`
	// Errors are the GraphQL errors, empty on success.
	Errors []GraphQLError `json:"errors,omitempty"`
}

// Err converts Errors to an *Error (nil when there are none): UNSUPPORTED when OAP
// reports an UnsupportedOperationException, VALIDATION otherwise.
func (r *GraphQLResponse) Err(operation string) error {
	if r == nil || len(r.Errors) == 0 {
		return nil
	}
	return graphQLError(operation, r.Errors)
}

// Decode unmarshals Data into out.
func (r *GraphQLResponse) Decode(out any) error {
	if r == nil || len(r.Data) == 0 || string(r.Data) == "null" {
		return &Error{Code: ErrorCodeUnavailable, Message: "graphql: response has no data", Retryable: true}
	}
	if err := json.Unmarshal(r.Data, out); err != nil {
		return &Error{Code: ErrorCodeUnavailable, Message: "graphql: invalid data: " + err.Error(), Retryable: true}
	}
	return nil
}

func graphQLError(operation string, errs []GraphQLError) *Error {
	msg := errs[0].Message
	code := ErrorCodeValidation
	for _, e := range errs {
		if strings.Contains(e.Message, "UnsupportedOperationException") {
			code = ErrorCodeUnsupported
			msg = e.Message
			break
		}
	}
	return &Error{Code: code, Message: operation + ": " + truncate(strings.TrimSpace(msg), maxVendorText)}
}

// graphql posts a query to the GraphQL endpoint. HTTP errors are *Error values; a 200
// response is returned as is (it may carry GraphQL errors).
func (c *Client) graphql(ctx context.Context, operation, query string, variables map[string]any) (*GraphQLResponse, error) {
	if variables == nil {
		variables = map[string]any{}
	}
	res, err := c.transport.do(ctx, &request{
		Method:     http.MethodPost,
		Path:       c.cfg.GraphQLPath,
		JSON:       map[string]any{"query": query, "variables": variables},
		Idempotent: true,
		Operation:  operation,
	})
	if err != nil {
		return nil, err
	}
	var out GraphQLResponse
	if err := res.decodeJSON(operation, &out); err != nil {
		return nil, err
	}
	for i := range out.Errors {
		out.Errors[i].Message = c.transport.redact(out.Errors[i].Message)
	}
	return &out, nil
}

// gql runs a typed query: any GraphQL error fails the call, data field is decoded
// into out.
func (c *Client) gql(ctx context.Context, operation, query string, variables map[string]any, field string, out any) error {
	res, err := c.graphql(ctx, operation, query, variables)
	if err != nil {
		return err
	}
	if err := res.Err(operation); err != nil {
		return err
	}
	var data map[string]json.RawMessage
	if err := res.Decode(&data); err != nil {
		return &Error{Code: ErrorCodeUnavailable, Message: operation + ": invalid GraphQL data", Retryable: true}
	}
	raw, ok := data[field]
	if !ok {
		return &Error{Code: ErrorCodeUnavailable, Message: operation + ": field " + field + " missing in response", Retryable: true}
	}
	if err := json.Unmarshal(raw, out); err != nil {
		return &Error{Code: ErrorCodeUnavailable, Message: operation + ": invalid GraphQL data", Retryable: true}
	}
	return nil
}

// Step is the time bucket of a GraphQL Duration.
type Step string

// Duration steps.
const (
	StepDay    Step = "DAY"
	StepHour   Step = "HOUR"
	StepMinute Step = "MINUTE"
	StepSecond Step = "SECOND"
)

// DefaultQueryWindow is the time window of metadata/topology/trace/log queries when
// no TimeRange is given (the last hour, step MINUTE).
const DefaultQueryWindow = time.Hour

// TimeRange is a query window. Zero Start means End − DefaultQueryWindow, zero End
// means now, empty Step means StepMinute. OAP expects the bounds in its own time zone;
// the client converts them (see Client.TimeInfo).
type TimeRange struct {
	Start time.Time
	End   time.Time
	Step  Step
}

// IsZero reports whether no bound and no step is set.
func (r TimeRange) IsZero() bool { return r.Start.IsZero() && r.End.IsZero() && r.Step == "" }

// resolved fills the defaults.
func (r TimeRange) resolved(now time.Time) TimeRange {
	if r.End.IsZero() {
		r.End = now
	}
	if r.Start.IsZero() {
		r.Start = r.End.Add(-DefaultQueryWindow)
	}
	if r.Step == "" {
		r.Step = StepMinute
	}
	return r
}

var stepLayouts = map[Step]string{
	StepDay:    "2006-01-02",
	StepHour:   "2006-01-02 15",
	StepMinute: "2006-01-02 1504",
	StepSecond: "2006-01-02 150405",
}

// durationVar renders the GraphQL Duration input in the OAP time zone.
func durationVar(operation string, r TimeRange, loc *time.Location, now time.Time) (map[string]any, error) {
	r = r.resolved(now)
	layout, ok := stepLayouts[r.Step]
	if !ok {
		return nil, ValidationError(operation, "step must be DAY, HOUR, MINUTE or SECOND")
	}
	if r.Start.After(r.End) {
		return nil, ValidationError(operation, "start must not be after end")
	}
	return map[string]any{
		"start": r.Start.In(loc).Format(layout),
		"end":   r.End.In(loc).Format(layout),
		"step":  string(r.Step),
	}, nil
}

// parseOffset parses OAP's "+0000" / "-0530" time zone.
func parseOffset(tz string) (*time.Location, error) {
	tz = strings.TrimSpace(tz)
	if len(tz) != 5 || (tz[0] != '+' && tz[0] != '-') {
		return nil, fmt.Errorf("invalid timezone %q", tz)
	}
	h, err1 := strconv.Atoi(tz[1:3])
	m, err2 := strconv.Atoi(tz[3:5])
	if err1 != nil || err2 != nil || h > 14 || m > 59 {
		return nil, fmt.Errorf("invalid timezone %q", tz)
	}
	off := h*3600 + m*60
	if tz[0] == '-' {
		off = -off
	}
	return time.FixedZone(tz, off), nil
}

// TimeInfo is the OAP clock (getTimeInfo).
type TimeInfo struct {
	// Timezone is the OAP offset, for example "+0000".
	Timezone string `json:"timezone"`
	// CurrentTime is the OAP wall clock.
	CurrentTime time.Time `json:"currentTime"`
}

// TimeInfo returns the OAP time zone and clock (GraphQL getTimeInfo) and caches the
// time zone used to format query windows.
func (c *Client) TimeInfo(ctx context.Context) (TimeInfo, error) {
	var out struct {
		Timezone         string `json:"timezone"`
		CurrentTimestamp int64  `json:"currentTimestamp"`
	}
	if err := c.gql(ctx, "timeInfo", "{getTimeInfo{timezone currentTimestamp}}", nil, "getTimeInfo", &out); err != nil {
		return TimeInfo{}, err
	}
	if loc, err := parseOffset(out.Timezone); err == nil {
		c.tzMu.Lock()
		c.tz = loc
		c.tzMu.Unlock()
	}
	return TimeInfo{Timezone: out.Timezone, CurrentTime: time.UnixMilli(out.CurrentTimestamp)}, nil
}

// location returns the cached OAP time zone, asking the server once (UTC when the
// server reports an unknown format).
func (c *Client) location(ctx context.Context) (*time.Location, error) {
	c.tzMu.Lock()
	loc := c.tz
	c.tzMu.Unlock()
	if loc != nil {
		return loc, nil
	}
	if _, err := c.TimeInfo(ctx); err != nil {
		return nil, err
	}
	c.tzMu.Lock()
	defer c.tzMu.Unlock()
	if c.tz == nil {
		c.tz = time.UTC
	}
	return c.tz, nil
}

// duration renders r for a query.
func (c *Client) duration(ctx context.Context, operation string, r TimeRange) (map[string]any, error) {
	loc, err := c.location(ctx)
	if err != nil {
		return nil, err
	}
	return durationVar(operation, r, loc, c.now())
}
