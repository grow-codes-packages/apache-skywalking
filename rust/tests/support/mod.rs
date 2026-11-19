// Service test helpers (Apache SkyWalking): recorded fixtures and GraphQL matchers.
#![allow(dead_code)]

use std::time::Duration;

use grow_apache_skywalking as sdk;
use sdk::core::{RandomSource, RetryPolicy};
use sdk::{SkyWalkingClient, SkyWalkingConfig, SkyWalkingConfigBuilder};
use serde_json::Value;
use wiremock::matchers::{method, path};
use wiremock::{Match, Mock, MockServer, Request, ResponseTemplate};

/// A recorded exchange from `tests/fixtures/skywalking/<name>.json`.
pub fn fixture(name: &str) -> Value {
    let p = format!(
        "{}/tests/fixtures/skywalking/{name}.json",
        env!("CARGO_MANIFEST_DIR")
    );
    serde_json::from_str(&std::fs::read_to_string(&p).unwrap_or_else(|_| panic!("{p}")))
        .expect("fixture json")
}

/// The recorded response as a wiremock response.
pub fn response(name: &str) -> ResponseTemplate {
    let f = fixture(name);
    let r = &f["response"];
    let status = r["status"].as_u64().unwrap_or(200) as u16;
    let tpl = ResponseTemplate::new(status);
    match &r["body"] {
        Value::String(s) => tpl.set_body_raw(s.clone(), "text/plain"),
        Value::Null => tpl,
        body => tpl.set_body_json(body.clone()),
    }
}

/// The recorded response body.
pub fn body(name: &str) -> Value {
    fixture(name)["response"]["body"].clone()
}

/// A GraphQL response with `data`.
pub fn gql_data(data: Value) -> ResponseTemplate {
    ResponseTemplate::new(200).set_body_json(serde_json::json!({ "data": data }))
}

/// Matches a GraphQL POST whose query contains `needle`.
pub struct Gql(pub &'static str);

impl Match for Gql {
    fn matches(&self, request: &Request) -> bool {
        serde_json::from_slice::<Value>(&request.body)
            .ok()
            .and_then(|b| b.get("query").and_then(Value::as_str).map(str::to_owned))
            .is_some_and(|q| q.contains(self.0))
    }
}

/// Matches when the GraphQL variables at a JSON pointer equal `value`.
pub struct GqlVar(pub &'static str, pub Value);

impl Match for GqlVar {
    fn matches(&self, request: &Request) -> bool {
        serde_json::from_slice::<Value>(&request.body)
            .ok()
            .and_then(|b| b.pointer(&format!("/variables{}", self.0)).cloned())
            .is_some_and(|v| v == self.1)
    }
}

/// Matches when the GraphQL variables have nothing at a JSON pointer.
pub struct GqlNoVar(pub &'static str);

impl Match for GqlNoVar {
    fn matches(&self, request: &Request) -> bool {
        serde_json::from_slice::<Value>(&request.body)
            .ok()
            .is_some_and(|b| b.pointer(&format!("/variables{}", self.0)).is_none())
    }
}

/// Mounts a GraphQL mock for `needle` answering with `tpl`.
pub async fn gql(server: &MockServer, needle: &'static str, tpl: ResponseTemplate) {
    Mock::given(method("POST"))
        .and(path("/graphql"))
        .and(Gql(needle))
        .respond_with(tpl)
        .mount(server)
        .await;
}

/// Mounts the `getTimeInfo` fixture (timezone `+0000`).
pub async fn time_info(server: &MockServer) {
    gql(server, "getTimeInfo", response("ping.ok")).await;
}

/// Fast deterministic retries.
pub fn fast_retry() -> RetryPolicy {
    RetryPolicy {
        initial_backoff: Duration::from_millis(5),
        max_backoff: Duration::from_millis(20),
        ..RetryPolicy::default()
    }
    .with_random(RandomSource::fixed(0.0))
}

/// Builder pointing GraphQL/REST and Zipkin at the mock server.
pub fn builder(server: &MockServer) -> SkyWalkingConfigBuilder {
    SkyWalkingConfig::builder()
        .url(server.uri())
        .zipkin_url(format!("{}/zipkin", server.uri()))
        .retry(fast_retry())
}

/// Client pointing at the mock server.
pub fn client(server: &MockServer) -> SkyWalkingClient {
    SkyWalkingClient::new(builder(server).build().expect("config")).expect("client")
}
