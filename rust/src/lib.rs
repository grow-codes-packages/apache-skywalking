// Skeleton created by sdk-kit 0.1.0; owned by this repository (edit freely).
//! # grow-apache-skywalking
//!
//! Rust SDK for [Apache SkyWalking](https://grow-codes-packages.github.io/apache-skywalking/) by Grow Codes.
//!
//! ```no_run
//! # async fn demo() -> grow_apache_skywalking::Result<()> {
//! use grow_apache_skywalking::{SkyWalkingClient, SkyWalkingConfig};
//!
//! let client = SkyWalkingClient::new(SkyWalkingConfig::from_env()?)?;
//! let health = client.health().await?;
//! println!("healthy: {}", health.healthy);
//! client.close().await;
//! # Ok(())
//! # }
//! ```
//!
//! Features: `blocking` (a blocking client), `testing` (in-memory fakes and the conformance
//! runner)
//! , `tracing-layer` and `log-bridge` (logging adapters, on by default)
//! , `tower` and `reqwest-middleware` (tracing instrumentation)
//! .
#![cfg_attr(docsrs, feature(doc_cfg))]

pub mod core;

mod client;
mod config;

#[cfg(feature = "blocking")]
#[cfg_attr(docsrs, doc(cfg(feature = "blocking")))]
pub mod blocking;

#[cfg(feature = "testing")]
#[cfg_attr(docsrs, doc(cfg(feature = "testing")))]
pub mod testing;

pub mod logging;
pub mod tracing;

pub use crate::core::{Error, ErrorCode, HealthStatus, Result, RetryPolicy, Secret};

pub use crate::config::DEFAULT_URL;

pub use crate::client::SkyWalkingClient;

pub use crate::config::SkyWalkingConfig;

pub use crate::config::SkyWalkingConfigBuilder;

/// Service-prefixed alias of [`Error`].
pub type SkyWalkingError = Error;
