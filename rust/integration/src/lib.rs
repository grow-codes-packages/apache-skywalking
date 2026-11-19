// Created by sdk-kit 0.1.0; owned by this repository (SkyWalking: run ids and polling added).
//! Helpers for the integration tests: bootstrap credentials and the conformance directory.

use std::path::PathBuf;

use grow_apache_skywalking as sdk;

/// Loads `KEY=value` lines from `$GROW_BOOTSTRAP_ENV` (default `/bootstrap/env`) into the
/// process environment; variables that are already set win. A missing file is not an error.
pub fn load_bootstrap_env() {
    let path = std::env::var("GROW_BOOTSTRAP_ENV").unwrap_or_else(|_| "/bootstrap/env".to_owned());
    let Ok(text) = std::fs::read_to_string(path) else {
        return;
    };
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if let Some((key, value)) = line.split_once('=') {
            let key = key.trim().trim_start_matches("export ").trim();
            let value = value.trim().trim_matches('"');
            if !key.is_empty() && std::env::var_os(key).is_none() {
                std::env::set_var(key, value);
            }
        }
    }
}

/// `$GROW_CONFORMANCE_DIR`, else `<repo>/conformance/scenarios`.
pub fn conformance_dir() -> PathBuf {
    sdk::testing::scenarios_dir(concat!(env!("CARGO_MANIFEST_DIR"), "/.."))
}

/// Unique, lower-case id of this run (`GROW_TEST_RUN_ID` plus a random suffix).
pub fn run_id() -> String {
    let base = std::env::var("GROW_TEST_RUN_ID").unwrap_or_else(|_| "local".to_owned());
    let raw = format!("{base}-{}", sdk::testing::run_id());
    raw.to_lowercase()
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '-')
        .collect()
}

/// Polls `f` every 2 s until `ok` accepts the value (errors are retried); panics after
/// `timeout` with the last value and error.
pub async fn poll<T, F, Fut>(
    label: &str,
    timeout: std::time::Duration,
    mut f: F,
    ok: impl Fn(&T) -> bool,
) -> T
where
    T: std::fmt::Debug,
    F: FnMut() -> Fut,
    Fut: std::future::Future<Output = sdk::Result<T>>,
{
    let deadline = std::time::Instant::now() + timeout;
    loop {
        let last = match f().await {
            Ok(v) if ok(&v) => return v,
            Ok(v) => format!("value {v:?}"),
            Err(e) => format!("error {e}"),
        };
        assert!(
            std::time::Instant::now() < deadline,
            "{label}: not satisfied within {timeout:?}; last {last}"
        );
        tokio::time::sleep(std::time::Duration::from_secs(2)).await;
    }
}
