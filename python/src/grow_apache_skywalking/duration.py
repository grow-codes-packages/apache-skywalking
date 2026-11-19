"""GraphQL ``Duration`` formatting and SkyWalking id derivation (service-owned)."""

from __future__ import annotations

import base64
import dataclasses
import re
from datetime import datetime, timedelta, timezone
from typing import Literal

from ._core import ValidationError

__all__ = [
    "DEFAULT_RANGE_S",
    "GraphQLDuration",
    "ResolvedRange",
    "Step",
    "TimeRange",
    "endpoint_id",
    "epoch_ms",
    "format_duration_time",
    "instance_id",
    "parse_timezone",
    "resolve_range",
    "service_id",
    "to_graphql_duration",
]

#: Granularity of a GraphQL ``Duration``.
Step = Literal["DAY", "HOUR", "MINUTE", "SECOND"]

#: Default query window: one hour (seconds).
DEFAULT_RANGE_S = 3600.0

_STEPS: tuple[str, ...] = ("DAY", "HOUR", "MINUTE", "SECOND")
_TZ = re.compile(r"^([+-])(\d{2}):?(\d{2})$")
_EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)


@dataclasses.dataclass(frozen=True)
class TimeRange:
    """Time window of a metadata/trace/log query.

    Defaults: ``end`` = now, ``start`` = ``end`` - 1 h, ``step`` = ``MINUTE``. Naive datetimes
    are UTC.
    """

    start: datetime | None = None
    end: datetime | None = None
    step: Step = "MINUTE"


@dataclasses.dataclass(frozen=True)
class GraphQLDuration:
    """The GraphQL ``Duration`` input (times in the OAP timezone, formatted per step)."""

    start: str
    end: str
    step: Step

    def to_json(self) -> dict[str, str]:
        """GraphQL variable form."""
        return {"start": self.start, "end": self.end, "step": self.step}


@dataclasses.dataclass(frozen=True)
class ResolvedRange:
    """A window in epoch milliseconds."""

    start_ms: int
    end_ms: int
    step: Step


def epoch_ms(value: datetime) -> int:
    """Epoch milliseconds of a datetime (naive = UTC)."""
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    delta = value - _EPOCH
    return (delta.days * 86_400 + delta.seconds) * 1000 + delta.microseconds // 1000


def parse_timezone(tz: str | None) -> int:
    """OAP timezone (``+0000``, ``-0530``, ``+08:00``) → minutes east of UTC; unknown → 0."""
    match = _TZ.match((tz or "").strip())
    if match is None:
        return 0
    minutes = int(match.group(2)) * 60 + int(match.group(3))
    return -minutes if match.group(1) == "-" else minutes


def format_duration_time(value: datetime | int, step: Step, offset_minutes: int = 0) -> str:
    """Format a time (datetime or epoch ms) for a ``Duration`` field.

    ``DAY yyyy-MM-dd``, ``HOUR yyyy-MM-dd HH``, ``MINUTE yyyy-MM-dd HHmm``,
    ``SECOND yyyy-MM-dd HHmmss``, in the OAP timezone given by ``offset_minutes``.
    """
    ms = value if isinstance(value, int) else epoch_ms(value)
    d = _EPOCH + timedelta(milliseconds=ms + offset_minutes * 60_000)
    day = f"{d.year:04d}-{d.month:02d}-{d.day:02d}"
    if step == "DAY":
        return day
    if step == "HOUR":
        return f"{day} {d.hour:02d}"
    if step == "SECOND":
        return f"{day} {d.hour:02d}{d.minute:02d}{d.second:02d}"
    return f"{day} {d.hour:02d}{d.minute:02d}"


def resolve_range(range: TimeRange | None, now_ms: int, operation: str) -> ResolvedRange:  # noqa: A002
    """Apply the :class:`TimeRange` defaults and validate (``ValidationError`` on bad input)."""
    r = range or TimeRange()
    end_ms = epoch_ms(r.end) if r.end is not None else now_ms
    start_ms = epoch_ms(r.start) if r.start is not None else end_ms - int(DEFAULT_RANGE_S * 1000)
    if start_ms > end_ms:
        raise ValidationError(f"{operation}: start must not be after end")
    if r.step not in _STEPS:
        raise ValidationError(f"{operation}: invalid step")
    return ResolvedRange(start_ms, end_ms, r.step)


def to_graphql_duration(range: ResolvedRange, offset_minutes: int) -> GraphQLDuration:  # noqa: A002
    """The GraphQL ``Duration`` of a resolved window."""
    return GraphQLDuration(
        format_duration_time(range.start_ms, range.step, offset_minutes),
        format_duration_time(range.end_ms, range.step, offset_minutes),
        range.step,
    )


def _b64(text: str) -> str:
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


def service_id(name: str, normal: bool = True) -> str:
    """SkyWalking service id: ``base64(name) + ".1"`` (``".0"`` for a virtual service)."""
    return f"{_b64(name)}.{1 if normal else 0}"


def instance_id(service: str, instance: str, normal: bool = True) -> str:
    """Service instance id: ``service_id + "_" + base64(instance)``."""
    return f"{service_id(service, normal)}_{_b64(instance)}"


def endpoint_id(service: str, endpoint: str, normal: bool = True) -> str:
    """Endpoint id: ``service_id + "_" + base64(endpoint)``."""
    return f"{service_id(service, normal)}_{_b64(endpoint)}"
