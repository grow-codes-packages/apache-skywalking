// Service code (Apache SkyWalking): GraphQL `Duration` formatting and id derivation.
//! GraphQL `Duration` windows (times in the OAP timezone, formatted per step) and the derived
//! SkyWalking ids (`base64(name).1`).

use std::time::{Duration, SystemTime, UNIX_EPOCH};

use base64::engine::general_purpose::STANDARD;
use base64::Engine as _;
use serde::Serialize;

use crate::core::{Error, Result};

/// Default query window: one hour.
pub const DEFAULT_RANGE: Duration = Duration::from_secs(3600);

/// Granularity of a GraphQL `Duration`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum Step {
    /// `yyyy-MM-dd`.
    Day,
    /// `yyyy-MM-dd HH`.
    Hour,
    /// `yyyy-MM-dd HHmm` (default).
    #[default]
    Minute,
    /// `yyyy-MM-dd HHmmss`.
    Second,
}

impl Step {
    /// Wire value: `DAY`, `HOUR`, `MINUTE` or `SECOND`.
    pub fn as_str(self) -> &'static str {
        match self {
            Step::Day => "DAY",
            Step::Hour => "HOUR",
            Step::Minute => "MINUTE",
            Step::Second => "SECOND",
        }
    }
}

/// Time window of a metadata/trace/log query. Defaults: `end` = now, `start` = `end` - 1 h,
/// `step` = `MINUTE`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct TimeRange {
    /// Window start (default `end` - 1 h).
    pub start: Option<SystemTime>,
    /// Window end (default now).
    pub end: Option<SystemTime>,
    /// Granularity (default `MINUTE`).
    pub step: Option<Step>,
}

impl TimeRange {
    /// The last `window` up to now.
    pub fn last(window: Duration) -> Self {
        TimeRange {
            start: SystemTime::now().checked_sub(window),
            end: None,
            step: None,
        }
    }
    /// Sets the start.
    pub fn start(mut self, start: SystemTime) -> Self {
        self.start = Some(start);
        self
    }
    /// Sets the end.
    pub fn end(mut self, end: SystemTime) -> Self {
        self.end = Some(end);
        self
    }
    /// Sets the step.
    pub fn step(mut self, step: Step) -> Self {
        self.step = Some(step);
        self
    }
}

/// The GraphQL `Duration` input (times in the OAP timezone, formatted per step).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct GraphQLDuration {
    /// Formatted start.
    pub start: String,
    /// Formatted end.
    pub end: String,
    /// Step.
    pub step: Step,
}

/// A window resolved to epoch milliseconds.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ResolvedRange {
    /// Start, epoch ms.
    pub start_ms: i64,
    /// End, epoch ms.
    pub end_ms: i64,
    /// Step.
    pub step: Step,
}

/// Epoch milliseconds of a time (negative before 1970).
pub fn epoch_ms(t: SystemTime) -> i64 {
    match t.duration_since(UNIX_EPOCH) {
        Ok(d) => d.as_millis() as i64,
        Err(e) => -(e.duration().as_millis() as i64),
    }
}

/// The time at an epoch millisecond (clamped at 1970).
pub fn from_epoch_ms(ms: i64) -> SystemTime {
    UNIX_EPOCH + Duration::from_millis(ms.max(0) as u64)
}

/// Parses an OAP timezone (`getTimeInfo.timezone`, e.g. `+0000`, `-0530`, `+08:00`) into
/// minutes east of UTC. Unknown text → 0.
pub fn parse_timezone(tz: &str) -> i32 {
    let t = tz.trim();
    let (sign, rest) = match t.chars().next() {
        Some('+') => (1, &t[1..]),
        Some('-') => (-1, &t[1..]),
        _ => return 0,
    };
    let digits: String = rest.chars().filter(|c| *c != ':').collect();
    let colon_ok = rest.len() == 4 || (rest.len() == 5 && rest.as_bytes()[2] == b':');
    if digits.len() != 4 || !colon_ok || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return 0;
    }
    let hours: i32 = digits[..2].parse().unwrap_or(0);
    let minutes: i32 = digits[2..].parse().unwrap_or(0);
    sign * (hours * 60 + minutes)
}

/// Civil date from days since 1970-01-01 (proleptic Gregorian).
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let y = yoe + era * 400 + i64::from(m <= 2);
    (y, m, d)
}

/// Formats an epoch-ms time for a `Duration` field in a timezone `offset_minutes` east of UTC:
/// `DAY yyyy-MM-dd`, `HOUR yyyy-MM-dd HH`, `MINUTE yyyy-MM-dd HHmm`, `SECOND yyyy-MM-dd HHmmss`.
pub fn format_duration_time(epoch_millis: i64, step: Step, offset_minutes: i32) -> String {
    let secs = (epoch_millis + i64::from(offset_minutes) * 60_000).div_euclid(1000);
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    let (y, m, d) = civil_from_days(days);
    let (hh, mm, ss) = (rem / 3600, (rem % 3600) / 60, rem % 60);
    let day = format!("{y:04}-{m:02}-{d:02}");
    match step {
        Step::Day => day,
        Step::Hour => format!("{day} {hh:02}"),
        Step::Minute => format!("{day} {hh:02}{mm:02}"),
        Step::Second => format!("{day} {hh:02}{mm:02}{ss:02}"),
    }
}

/// Applies the [`TimeRange`] defaults; `VALIDATION` when `start` is after `end`.
pub fn resolve_range(range: &TimeRange, now: SystemTime, operation: &str) -> Result<ResolvedRange> {
    let end_ms = epoch_ms(range.end.unwrap_or(now));
    let start_ms = range
        .start
        .map(epoch_ms)
        .unwrap_or(end_ms - DEFAULT_RANGE.as_millis() as i64);
    if start_ms > end_ms {
        return Err(Error::validation(format!(
            "{operation}: start must not be after end"
        )));
    }
    Ok(ResolvedRange {
        start_ms,
        end_ms,
        step: range.step.unwrap_or_default(),
    })
}

/// Builds the GraphQL `Duration` for a resolved window.
pub fn to_graphql_duration(range: &ResolvedRange, offset_minutes: i32) -> GraphQLDuration {
    GraphQLDuration {
        start: format_duration_time(range.start_ms, range.step, offset_minutes),
        end: format_duration_time(range.end_ms, range.step, offset_minutes),
        step: range.step,
    }
}

fn b64(text: &str) -> String {
    STANDARD.encode(text.as_bytes())
}

/// SkyWalking service id of a real (normal) service: `base64(name) + ".1"`.
pub fn service_id(name: &str) -> String {
    service_id_with(name, true)
}

/// Service id: `base64(name) + ".1"` when `normal`, `".0"` for a virtual service.
pub fn service_id_with(name: &str, normal: bool) -> String {
    format!("{}.{}", b64(name), u8::from(normal))
}

/// Service instance id: `serviceId + "_" + base64(instanceName)`.
pub fn instance_id(service: &str, instance: &str) -> String {
    format!("{}_{}", service_id(service), b64(instance))
}

/// Endpoint id: `serviceId + "_" + base64(endpointName)`.
pub fn endpoint_id(service: &str, endpoint: &str) -> String {
    format!("{}_{}", service_id(service), b64(endpoint))
}
