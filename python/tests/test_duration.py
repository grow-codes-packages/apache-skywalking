"""GraphQL Duration formatting, time windows and id derivation."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

import grow_apache_skywalking as sdk

T = datetime(2026, 11, 18, 21, 5, 9, 123000, tzinfo=timezone.utc)


def test_parse_timezone() -> None:
    assert sdk.parse_timezone("+0000") == 0
    assert sdk.parse_timezone("-0530") == -330
    assert sdk.parse_timezone("+08:00") == 480
    assert sdk.parse_timezone("UTC") == 0
    assert sdk.parse_timezone(None) == 0


def test_format_duration_time() -> None:
    assert sdk.format_duration_time(T, "DAY") == "2026-11-18"
    assert sdk.format_duration_time(T, "HOUR") == "2026-11-18 21"
    assert sdk.format_duration_time(T, "MINUTE") == "2026-11-18 2105"
    assert sdk.format_duration_time(T, "SECOND") == "2026-11-18 210509"
    assert sdk.format_duration_time(T, "MINUTE", 480) == "2026-11-19 0505"
    naive = datetime(2026, 1, 2, 3, 4)
    assert sdk.format_duration_time(naive, "MINUTE") == "2026-01-02 0304"
    assert sdk.format_duration_time(sdk.epoch_ms(T), "SECOND", -60) == "2026-11-18 200509"


def test_resolve_range() -> None:
    now = sdk.epoch_ms(T)
    r = sdk.resolve_range(None, now, "op")
    assert (r.end_ms - r.start_ms, r.step) == (3_600_000, "MINUTE")
    r = sdk.resolve_range(sdk.TimeRange(start=T - timedelta(minutes=5), step="SECOND"), now, "op")
    assert r.end_ms - r.start_ms == 300_000
    d = sdk.to_graphql_duration(r, 0)
    assert d.to_json() == {
        "start": "2026-11-18 210009",
        "end": "2026-11-18 210509",
        "step": "SECOND",
    }
    with pytest.raises(sdk.ValidationError, match="start must not be after end"):
        sdk.resolve_range(sdk.TimeRange(start=T, end=T - timedelta(seconds=1)), now, "op")
    with pytest.raises(sdk.ValidationError, match="invalid step"):
        sdk.resolve_range(sdk.TimeRange(step="WEEK"), now, "op")  # type: ignore[arg-type]


def test_ids() -> None:
    assert sdk.service_id("sw-native-a-1fddf66a") == "c3ctbmF0aXZlLWEtMWZkZGY2NmE=.1"
    assert sdk.service_id("User", normal=False) == "VXNlcg==.0"
    assert sdk.instance_id("sw-native-a-1fddf66a", "inst-a-1fddf66a") == (
        "c3ctbmF0aXZlLWEtMWZkZGY2NmE=.1_aW5zdC1hLTFmZGRmNjZh"
    )
    assert sdk.endpoint_id("svc", "/x") == "c3Zj.1_L3g="
