#!/bin/sh
# Bootstrap for the Apache SkyWalking SDK fixture (GRO-1383). Idempotent, POSIX sh (curlimages/curl).
#
# SkyWalking OAP has no users/tokens/streams to create: GraphQL, native REST and OTLP gRPC are
# unauthenticated in the stack template (SW_AUTHENTICATION unset). This bootstrap therefore only
#   1. waits until the health checker reports score 0 (storage + modules ready),
#   2. waits until the Zipkin query API (OTLP trace read path) answers,
#   3. reads the server version and timezone,
#   4. writes the endpoints to /bootstrap/env for the test-<lang> containers.
set -eu
SW_URL=${SW_URL:-http://apache-skywalking:12800}
SW_OTLP=${SW_OTLP:-http://apache-skywalking:11800}
SW_ZIPKIN=${SW_ZIPKIN:-http://apache-skywalking:9412/zipkin}
DIR=${BOOTSTRAP_DIR:-/bootstrap}
TRIES=${BOOTSTRAP_TRIES:-90}

gql() { # gql <query-json> -> body on stdout
  curl -fsS -m 10 -H 'Content-Type: application/json' --data-binary "$1" "$SW_URL/graphql"
}

i=0
until gql '{"query":"{checkHealth{score details}}"}' 2>/dev/null | grep -q '"score":0'; do
  i=$((i + 1)); [ "$i" -ge "$TRIES" ] && { echo "bootstrap: OAP health score never reached 0" >&2; exit 1; }
  sleep 2
done
i=0
until curl -fsS -m 10 -o /dev/null "$SW_ZIPKIN/api/v2/services"; do
  i=$((i + 1)); [ "$i" -ge "$TRIES" ] && { echo "bootstrap: Zipkin query API not ready" >&2; exit 1; }
  sleep 2
done

VERSION=$(gql '{"query":"{version}"}' | sed -n 's/.*"version":"\([^"]*\)".*/\1/p')
TZ_OFFSET=$(gql '{"query":"{getTimeInfo{timezone}}"}' | sed -n 's/.*"timezone":"\([^"]*\)".*/\1/p')
[ -n "$VERSION" ] || { echo "bootstrap: could not read server version" >&2; exit 1; }

mkdir -p "$DIR"
tmp="$DIR/env.tmp.$$"
cat >"$tmp" <<EOF
GROW_APACHE_SKYWALKING_URL=$SW_URL
GROW_APACHE_SKYWALKING_GRAPHQL_URL=$SW_URL/graphql
GROW_APACHE_SKYWALKING_OTLP_ENDPOINT=$SW_OTLP
GROW_APACHE_SKYWALKING_OTLP_PROTOCOL=grpc
GROW_APACHE_SKYWALKING_ZIPKIN_URL=$SW_ZIPKIN
SW_GRAPHQL_URL=$SW_URL/graphql
SW_SERVER_VERSION=$VERSION
SW_SERVER_TIMEZONE=$TZ_OFFSET
SW_SEARCHABLE_LOGS_TAG_KEYS=level,http.status_code,trace_id,span_id,run_id
SW_ZIPKIN_SEARCHABLE_TAG_KEYS=http.method,run_id
EOF
chmod 0644 "$tmp"
mv -f "$tmp" "$DIR/env"
echo "bootstrap: ok version=$VERSION timezone=$TZ_OFFSET env=$DIR/env"
