#!/usr/bin/env python3
"""Build deterministic, privacy-safe typing quality reports from JSONL results.

The accepted per-case schema deliberately describes only an outcome. Unknown fields are
rejected so a benchmark cannot accidentally feed typed text, editor context, or gesture data
into a retained report. See ``--help`` and ``docs/typing-quality-benchmarks.md`` for examples.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Sequence

SCHEMA_VERSION = 1
INPUT_KINDS = frozenset({"typing", "swipe"})
CASE_FIELDS = frozenset(
    {
        "schema_version",
        "input_kind",
        "expected_rank",
        "committed",
        "used_fallback",
        "latency_ms",
        "confidence",
    }
)
REQUIRED_CASE_FIELDS = CASE_FIELDS
AGGREGATE_FIELDS = frozenset(
    {
        "schema_version",
        "record_type",
        "input_kind",
        "case_count",
        "rank_counts",
        "commit_count",
        "wrong_commit_count",
        "fallback_count",
        "latencies_ms",
        "confidence_bins",
    }
)
AGGREGATE_BIN_FIELDS = frozenset(
    {"index", "case_count", "top_1_correct_count", "confidence_sum"}
)
CONFIDENCE_BIN_COUNT = 10
HIGHER_IS_BETTER = frozenset({"top_1_accuracy", "top_k_accuracy", "commit_precision"})
LOWER_IS_BETTER = frozenset(
    {
        "wrong_commit_rate",
        "fallback_rate",
        "abstention_rate",
        "latency_p50_ms",
        "latency_p95_ms",
        "latency_p99_ms",
        "expected_calibration_error",
    }
)
BUDGET_METRICS = HIGHER_IS_BETTER | LOWER_IS_BETTER
BUDGET_FIELDS = frozenset({"metric", "minimum", "maximum", "max_regression"})


class InputError(ValueError):
    """Raised for malformed or unsupported benchmark input."""


@dataclass(frozen=True)
class Case:
    input_kind: str
    expected_rank: int | None
    committed: bool
    used_fallback: bool
    latency_ms: float
    confidence: float

    @property
    def top_one_correct(self) -> bool:
        return self.expected_rank == 1


@dataclass(frozen=True)
class AggregateConfidenceBin:
    index: int
    case_count: int
    top_one_correct_count: int
    confidence_sum: float


@dataclass(frozen=True)
class Aggregate:
    input_kind: str
    case_count: int
    rank_counts: tuple[tuple[int, int], ...]
    commit_count: int
    wrong_commit_count: int
    fallback_count: int
    latencies_ms: tuple[float, ...]
    confidence_bins: tuple[AggregateConfidenceBin, ...]


def _strict_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise InputError(f"duplicate JSON field {key!r}")
        result[key] = value
    return result


def _loads_json(raw: str) -> object:
    return json.loads(
        raw,
        object_pairs_hook=_strict_object,
        parse_constant=lambda token: (_ for _ in ()).throw(
            InputError(f"non-finite JSON number {token}")
        ),
    )


def _number(
    value: object, *, field: str, minimum: float, maximum: float | None = None
) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise InputError(f"{field} must be a number")
    try:
        result = float(value)
    except OverflowError as error:
        raise InputError(f"{field} must be finite") from error
    if not math.isfinite(result):
        raise InputError(f"{field} must be finite")
    if result < minimum or (maximum is not None and result > maximum):
        bounds = f"[{minimum}, {maximum}]" if maximum is not None else f">= {minimum}"
        raise InputError(f"{field} must be {bounds}")
    return result


def _integer(value: object, *, field: str, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise InputError(f"{field} must be an integer >= {minimum}")
    return value


def parse_case(value: object, *, location: str) -> Case:
    if not isinstance(value, dict):
        raise InputError(f"{location}: each line must be a JSON object")
    fields = set(value)
    missing = REQUIRED_CASE_FIELDS - fields
    unknown = fields - CASE_FIELDS
    if missing:
        raise InputError(f"{location}: missing fields: {', '.join(sorted(missing))}")
    if unknown:
        raise InputError(f"{location}: unknown fields: {', '.join(sorted(unknown))}")
    if (
        not isinstance(value["schema_version"], int)
        or isinstance(value["schema_version"], bool)
        or value["schema_version"] != SCHEMA_VERSION
    ):
        raise InputError(f"{location}: schema_version must be integer {SCHEMA_VERSION}")
    input_kind = value["input_kind"]
    if not isinstance(input_kind, str) or input_kind not in INPUT_KINDS:
        raise InputError(
            f"{location}: input_kind must be one of {', '.join(sorted(INPUT_KINDS))}"
        )
    rank = value["expected_rank"]
    if rank is not None and (
        isinstance(rank, bool) or not isinstance(rank, int) or rank < 1
    ):
        raise InputError(f"{location}: expected_rank must be null or an integer >= 1")
    for field in ("committed", "used_fallback"):
        if not isinstance(value[field], bool):
            raise InputError(f"{location}: {field} must be a boolean")
    return Case(
        input_kind=input_kind,
        expected_rank=rank,
        committed=value["committed"],
        used_fallback=value["used_fallback"],
        latency_ms=_number(
            value["latency_ms"], field=f"{location}: latency_ms", minimum=0.0
        ),
        confidence=_number(
            value["confidence"],
            field=f"{location}: confidence",
            minimum=0.0,
            maximum=1.0,
        ),
    )


def parse_aggregate(value: object, *, location: str) -> Aggregate:
    if not isinstance(value, dict):
        raise InputError(f"{location}: each line must be a JSON object")
    fields = set(value)
    missing = AGGREGATE_FIELDS - fields
    unknown = fields - AGGREGATE_FIELDS
    if missing:
        raise InputError(f"{location}: missing fields: {', '.join(sorted(missing))}")
    if unknown:
        raise InputError(f"{location}: unknown fields: {', '.join(sorted(unknown))}")
    if (
        not isinstance(value["schema_version"], int)
        or isinstance(value["schema_version"], bool)
        or value["schema_version"] != SCHEMA_VERSION
    ):
        raise InputError(f"{location}: schema_version must be integer {SCHEMA_VERSION}")
    if value["record_type"] != "aggregate":
        raise InputError(f"{location}: record_type must be 'aggregate'")
    input_kind = value["input_kind"]
    if not isinstance(input_kind, str) or input_kind not in INPUT_KINDS:
        raise InputError(
            f"{location}: input_kind must be one of {', '.join(sorted(INPUT_KINDS))}"
        )
    case_count = _integer(
        value["case_count"], field=f"{location}: case_count", minimum=1
    )

    raw_ranks = value["rank_counts"]
    if not isinstance(raw_ranks, dict):
        raise InputError(f"{location}: rank_counts must be an object")
    ranks: list[tuple[int, int]] = []
    for raw_rank, raw_count in raw_ranks.items():
        if (
            not isinstance(raw_rank, str)
            or not raw_rank.isascii()
            or not raw_rank.isdecimal()
            or raw_rank.startswith("0")
        ):
            raise InputError(
                f"{location}: rank_counts keys must be canonical integer strings >= 1"
            )
        rank = int(raw_rank)
        count = _integer(raw_count, field=f"{location}: rank_counts[{raw_rank!r}]")
        ranks.append((rank, count))
    ranks.sort()
    ranked_count = sum(count for _, count in ranks)
    if ranked_count > case_count:
        raise InputError(f"{location}: rank_counts exceed case_count")

    commit_count = _integer(value["commit_count"], field=f"{location}: commit_count")
    wrong_commit_count = _integer(
        value["wrong_commit_count"], field=f"{location}: wrong_commit_count"
    )
    fallback_count = _integer(
        value["fallback_count"], field=f"{location}: fallback_count"
    )
    if commit_count > case_count:
        raise InputError(f"{location}: commit_count exceeds case_count")
    if wrong_commit_count > commit_count:
        raise InputError(f"{location}: wrong_commit_count exceeds commit_count")
    if fallback_count > case_count:
        raise InputError(f"{location}: fallback_count exceeds case_count")

    raw_latencies = value["latencies_ms"]
    if not isinstance(raw_latencies, list) or len(raw_latencies) != case_count:
        raise InputError(
            f"{location}: latencies_ms must contain exactly case_count values"
        )
    latencies = tuple(
        _number(item, field=f"{location}: latencies_ms[{index}]", minimum=0.0)
        for index, item in enumerate(raw_latencies)
    )

    raw_bins = value["confidence_bins"]
    if not isinstance(raw_bins, list):
        raise InputError(f"{location}: confidence_bins must be an array")
    bins: list[AggregateConfidenceBin] = []
    seen_indices: set[int] = set()
    for position, raw_bin in enumerate(raw_bins):
        bin_location = f"{location}: confidence_bins[{position}]"
        if not isinstance(raw_bin, dict) or set(raw_bin) != AGGREGATE_BIN_FIELDS:
            raise InputError(
                f"{bin_location} must contain only "
                + ", ".join(sorted(AGGREGATE_BIN_FIELDS))
            )
        index = _integer(raw_bin["index"], field=f"{bin_location}: index")
        if index >= CONFIDENCE_BIN_COUNT:
            raise InputError(
                f"{bin_location}: index must be below {CONFIDENCE_BIN_COUNT}"
            )
        if index in seen_indices:
            raise InputError(f"{location}: duplicate confidence bin {index}")
        seen_indices.add(index)
        bin_count = _integer(raw_bin["case_count"], field=f"{bin_location}: case_count")
        correct_count = _integer(
            raw_bin["top_1_correct_count"],
            field=f"{bin_location}: top_1_correct_count",
        )
        if correct_count > bin_count:
            raise InputError(f"{bin_location}: top_1_correct_count exceeds case_count")
        confidence_sum = _number(
            raw_bin["confidence_sum"],
            field=f"{bin_location}: confidence_sum",
            minimum=0.0,
            maximum=float(bin_count),
        )
        lower_total = index / CONFIDENCE_BIN_COUNT * bin_count
        upper_total = (index + 1) / CONFIDENCE_BIN_COUNT * bin_count
        if confidence_sum < lower_total - 1e-9 or confidence_sum > upper_total + 1e-9:
            raise InputError(
                f"{bin_location}: confidence_sum is outside the bin's confidence range"
            )
        bins.append(
            AggregateConfidenceBin(
                index=index,
                case_count=bin_count,
                top_one_correct_count=correct_count,
                confidence_sum=confidence_sum,
            )
        )
    if sum(item.case_count for item in bins) != case_count:
        raise InputError(
            f"{location}: confidence bin case counts must sum to case_count"
        )
    top_one_count = dict(ranks).get(1, 0)
    if sum(item.top_one_correct_count for item in bins) != top_one_count:
        raise InputError(
            f"{location}: confidence bin correct counts must equal rank_counts['1']"
        )
    correct_commit_count = commit_count - wrong_commit_count
    if wrong_commit_count > case_count - top_one_count:
        raise InputError(
            f"{location}: wrong_commit_count exceeds incorrect top-one cases"
        )
    if correct_commit_count > top_one_count:
        raise InputError(f"{location}: correct commits exceed correct top-one cases")

    return Aggregate(
        input_kind=input_kind,
        case_count=case_count,
        rank_counts=tuple(ranks),
        commit_count=commit_count,
        wrong_commit_count=wrong_commit_count,
        fallback_count=fallback_count,
        latencies_ms=latencies,
        confidence_bins=tuple(sorted(bins, key=lambda item: item.index)),
    )


def load_benchmark(path: Path) -> list[Case] | list[Aggregate]:
    records: list[Case] | list[Aggregate] | None = None
    style: str | None = None
    input_kind: str | None = None
    try:
        with path.open(encoding="utf-8") as source:
            for line_number, raw_line in enumerate(source, start=1):
                location = f"{path}:{line_number}"
                if not raw_line.strip():
                    raise InputError(f"{location}: blank lines are not allowed")
                try:
                    value = _loads_json(raw_line)
                except json.JSONDecodeError as error:
                    raise InputError(
                        f"{location}: invalid JSON: {error.msg}"
                    ) from error
                except InputError as error:
                    raise InputError(f"{location}: {error}") from error
                aggregate = (
                    isinstance(value, dict) and value.get("record_type") == "aggregate"
                )
                next_style = "aggregate" if aggregate else "case"
                if style is not None and style != next_style:
                    raise InputError(
                        f"{location}: case and aggregate records cannot be mixed"
                    )
                style = next_style
                if aggregate:
                    if records is None:
                        records = []
                    parsed_aggregate = parse_aggregate(value, location=location)
                    if (
                        input_kind is not None
                        and parsed_aggregate.input_kind != input_kind
                    ):
                        raise InputError(
                            f"{location}: input_kind differs from earlier records"
                        )
                    input_kind = parsed_aggregate.input_kind
                    records.append(parsed_aggregate)  # type: ignore[arg-type]
                else:
                    if records is None:
                        records = []
                    parsed_case = parse_case(value, location=location)
                    if input_kind is not None and parsed_case.input_kind != input_kind:
                        raise InputError(
                            f"{location}: input_kind differs from earlier records"
                        )
                    input_kind = parsed_case.input_kind
                    records.append(parsed_case)  # type: ignore[arg-type]
    except (OSError, UnicodeError) as error:
        raise InputError(f"cannot read {path}: {error}") from error
    if not records:
        raise InputError(f"{path}: benchmark contains no records")
    return records


def _rounded(value: float) -> float:
    # A stable precision is easier to diff and is substantially finer than useful benchmark noise.
    return round(value, 6)


def _ratio(numerator: int, denominator: int) -> float:
    return _rounded(numerator / denominator) if denominator else 0.0


def _nearest_rank(values: Sequence[float], percentile: int) -> float:
    ordered = sorted(values)
    index = max(0, math.ceil(percentile / 100 * len(ordered)) - 1)
    return _rounded(ordered[index])


def _confidence_rows(
    *,
    total_cases: int,
    case_counts: Sequence[int],
    correct_counts: Sequence[int],
    confidence_sums: Sequence[float],
) -> tuple[list[dict[str, Any]], float]:
    if not (
        len(case_counts)
        == len(correct_counts)
        == len(confidence_sums)
        == CONFIDENCE_BIN_COUNT
    ):
        raise InputError("confidence aggregate must contain exactly ten bins")
    rows: list[dict[str, Any]] = []
    weighted_gap = 0.0
    for index in range(CONFIDENCE_BIN_COUNT):
        count = case_counts[index]
        correct = correct_counts[index]
        mean_confidence = confidence_sums[index] / count if count else 0.0
        accuracy = correct / count if count else 0.0
        gap = abs(mean_confidence - accuracy) if count else 0.0
        weighted_gap += count / total_cases * gap
        upper = (index + 1) / CONFIDENCE_BIN_COUNT
        rows.append(
            {
                "lower_inclusive": _rounded(index / CONFIDENCE_BIN_COUNT),
                "upper_inclusive": index == CONFIDENCE_BIN_COUNT - 1,
                "upper_exclusive": None
                if index == CONFIDENCE_BIN_COUNT - 1
                else _rounded(upper),
                "case_count": count,
                "coverage": _ratio(count, total_cases),
                "top_1_accuracy": _ratio(correct, count),
                "mean_confidence": _rounded(mean_confidence),
                "calibration_gap": _rounded(gap),
            }
        )
    return rows, _rounded(weighted_gap)


def _confidence_bins(cases: Sequence[Case]) -> tuple[list[dict[str, Any]], float]:
    buckets: list[list[Case]] = [[] for _ in range(CONFIDENCE_BIN_COUNT)]
    for case in cases:
        index = min(
            int(case.confidence * CONFIDENCE_BIN_COUNT), CONFIDENCE_BIN_COUNT - 1
        )
        buckets[index].append(case)
    return _confidence_rows(
        total_cases=len(cases),
        case_counts=[len(bucket) for bucket in buckets],
        correct_counts=[
            sum(case.top_one_correct for case in bucket) for bucket in buckets
        ],
        confidence_sums=[sum(case.confidence for case in bucket) for bucket in buckets],
    )


def _result(
    *,
    count: int,
    top_one: int,
    top_k_correct: int,
    commits: int,
    wrong_commits: int,
    fallbacks: int,
    latencies: Sequence[float],
    confidence: tuple[list[dict[str, Any]], float],
) -> dict[str, Any]:
    bins, calibration_error = confidence
    correct_commits = commits - wrong_commits
    abstentions = count - commits
    metrics: dict[str, int | float] = {
        "case_count": count,
        "top_1_accuracy": _ratio(top_one, count),
        "top_k_accuracy": _ratio(top_k_correct, count),
        "commit_precision": _ratio(correct_commits, commits),
        "wrong_commit_rate": _ratio(wrong_commits, count),
        "fallback_rate": _ratio(fallbacks, count),
        "abstention_rate": _ratio(abstentions, count),
        "latency_p50_ms": _nearest_rank(latencies, 50),
        "latency_p95_ms": _nearest_rank(latencies, 95),
        "latency_p99_ms": _nearest_rank(latencies, 99),
        "expected_calibration_error": calibration_error,
    }
    return {"metrics": metrics, "confidence_bins": bins}


def _calculate_cases(cases: Sequence[Case], *, top_k: int) -> dict[str, Any]:
    if isinstance(top_k, bool) or not isinstance(top_k, int) or top_k < 1:
        raise InputError("top_k must be an integer >= 1")

    count = len(cases)
    top_one = sum(case.top_one_correct for case in cases)
    top_k_correct = sum(
        case.expected_rank is not None and case.expected_rank <= top_k for case in cases
    )
    commits = sum(case.committed for case in cases)
    wrong_commits = sum(case.committed and not case.top_one_correct for case in cases)
    fallbacks = sum(case.used_fallback for case in cases)
    return _result(
        count=count,
        top_one=top_one,
        top_k_correct=top_k_correct,
        commits=commits,
        wrong_commits=wrong_commits,
        fallbacks=fallbacks,
        latencies=[case.latency_ms for case in cases],
        confidence=_confidence_bins(cases),
    )


def _calculate_aggregates(
    aggregates: Sequence[Aggregate], *, top_k: int
) -> dict[str, Any]:
    count = sum(record.case_count for record in aggregates)
    rank_counts: dict[int, int] = {}
    case_counts = [0] * CONFIDENCE_BIN_COUNT
    correct_counts = [0] * CONFIDENCE_BIN_COUNT
    confidence_sums = [0.0] * CONFIDENCE_BIN_COUNT
    latencies: list[float] = []
    for record in aggregates:
        for rank, rank_count in record.rank_counts:
            rank_counts[rank] = rank_counts.get(rank, 0) + rank_count
        for item in record.confidence_bins:
            case_counts[item.index] += item.case_count
            correct_counts[item.index] += item.top_one_correct_count
            confidence_sums[item.index] += item.confidence_sum
        latencies.extend(record.latencies_ms)
    return _result(
        count=count,
        top_one=rank_counts.get(1, 0),
        top_k_correct=sum(
            rank_count for rank, rank_count in rank_counts.items() if rank <= top_k
        ),
        commits=sum(record.commit_count for record in aggregates),
        wrong_commits=sum(record.wrong_commit_count for record in aggregates),
        fallbacks=sum(record.fallback_count for record in aggregates),
        latencies=latencies,
        confidence=_confidence_rows(
            total_cases=count,
            case_counts=case_counts,
            correct_counts=correct_counts,
            confidence_sums=confidence_sums,
        ),
    )


def calculate(
    records: Sequence[Case] | Sequence[Aggregate], *, top_k: int
) -> dict[str, Any]:
    if not records:
        raise InputError("cannot calculate a report without records")
    if isinstance(top_k, bool) or not isinstance(top_k, int) or top_k < 1:
        raise InputError("top_k must be an integer >= 1")
    if isinstance(records[0], Case):
        if not all(isinstance(record, Case) for record in records):
            raise InputError("case and aggregate records cannot be mixed")
        if len({record.input_kind for record in records}) != 1:
            raise InputError("input_kind must be consistent within a benchmark")
        return _calculate_cases(records, top_k=top_k)  # type: ignore[arg-type]
    if not all(isinstance(record, Aggregate) for record in records):
        raise InputError("case and aggregate records cannot be mixed")
    if len({record.input_kind for record in records}) != 1:
        raise InputError("input_kind must be consistent within a benchmark")
    return _calculate_aggregates(records, top_k=top_k)  # type: ignore[arg-type]


def build_report(
    current: Sequence[Case] | Sequence[Aggregate],
    *,
    top_k: int,
    baseline: Sequence[Case] | Sequence[Aggregate] | None = None,
) -> dict[str, Any]:
    current_result = calculate(current, top_k=top_k)
    current_kind = current[0].input_kind
    report: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "input_kind": current_kind,
        "top_k": top_k,
        "current": current_result,
    }
    if baseline is not None:
        baseline_result = calculate(baseline, top_k=top_k)
        if baseline[0].input_kind != current_kind:
            raise InputError("current and baseline input_kind must match")
        if (
            baseline_result["metrics"]["case_count"]
            != current_result["metrics"]["case_count"]
        ):
            raise InputError("current and baseline case_count must match")
        deltas = {
            metric: _rounded(
                float(current_result["metrics"][metric])
                - float(baseline_result["metrics"][metric])
            )
            for metric in sorted(current_result["metrics"])
        }
        report["baseline"] = baseline_result
        report["delta"] = deltas
    return report


def load_budgets(path: Path) -> list[dict[str, float | str]]:
    try:
        document = _loads_json(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise InputError(f"cannot read budgets from {path}: {error}") from error
    if not isinstance(document, dict) or set(document) != {"schema_version", "budgets"}:
        raise InputError("budget document must contain only schema_version and budgets")
    if (
        not isinstance(document["schema_version"], int)
        or isinstance(document["schema_version"], bool)
        or document["schema_version"] != SCHEMA_VERSION
    ):
        raise InputError(f"budget schema_version must be integer {SCHEMA_VERSION}")
    raw_budgets = document["budgets"]
    if not isinstance(raw_budgets, list) or not raw_budgets:
        raise InputError("budgets must be a non-empty array")

    budgets: list[dict[str, float | str]] = []
    seen: set[str] = set()
    for index, raw in enumerate(raw_budgets):
        location = f"budget {index + 1}"
        if not isinstance(raw, dict):
            raise InputError(f"{location} must be an object")
        unknown = set(raw) - BUDGET_FIELDS
        if unknown:
            raise InputError(
                f"{location} has unknown fields: {', '.join(sorted(unknown))}"
            )
        metric = raw.get("metric")
        if not isinstance(metric, str) or metric not in BUDGET_METRICS:
            raise InputError(f"{location} has unsupported metric {metric!r}")
        if metric in seen:
            raise InputError(f"duplicate budget for {metric}")
        seen.add(metric)
        limits = set(raw) - {"metric"}
        if not limits:
            raise InputError(f"{location} must set minimum, maximum, or max_regression")
        parsed: dict[str, float | str] = {"metric": metric}
        for field in sorted(limits):
            parsed[field] = _number(
                raw[field], field=f"{location}: {field}", minimum=0.0
            )
        if (
            "minimum" in parsed
            and "maximum" in parsed
            and float(parsed["minimum"]) > float(parsed["maximum"])
        ):
            raise InputError(f"{location} minimum exceeds maximum")
        budgets.append(parsed)
    return budgets


def check_budgets(
    report: dict[str, Any], budgets: Iterable[dict[str, float | str]]
) -> list[str]:
    current = report["current"]["metrics"]
    baseline = report.get("baseline", {}).get("metrics")
    failures: list[str] = []
    for budget in budgets:
        metric = str(budget["metric"])
        value = float(current[metric])
        if "minimum" in budget and value < float(budget["minimum"]):
            failures.append(
                f"{metric} {value:.6f} is below minimum {float(budget['minimum']):.6f}"
            )
        if "maximum" in budget and value > float(budget["maximum"]):
            failures.append(
                f"{metric} {value:.6f} exceeds maximum {float(budget['maximum']):.6f}"
            )
        if "max_regression" in budget:
            if baseline is None:
                raise InputError(
                    f"budget for {metric} uses max_regression but no baseline was supplied"
                )
            old = float(baseline[metric])
            regression = old - value if metric in HIGHER_IS_BETTER else value - old
            allowed = float(budget["max_regression"])
            if regression > allowed:
                failures.append(
                    f"{metric} regressed by {regression:.6f}; maximum allowed is {allowed:.6f}"
                )
    return failures


def render_human(
    report: dict[str, Any], failures: Sequence[str], *, budgets_supplied: bool = True
) -> str:
    metrics = report["current"]["metrics"]
    lines = [
        f"Typing quality report ({report['input_kind']}, {metrics['case_count']} cases, "
        f"top-k={report['top_k']})",
        f"  top-1 accuracy       {metrics['top_1_accuracy'] * 100:7.2f}%",
        f"  top-{report['top_k']} accuracy       {metrics['top_k_accuracy'] * 100:7.2f}%",
        f"  commit precision     {metrics['commit_precision'] * 100:7.2f}%",
        f"  wrong-commit rate    {metrics['wrong_commit_rate'] * 100:7.2f}%",
        f"  fallback rate        {metrics['fallback_rate'] * 100:7.2f}%",
        f"  abstention rate      {metrics['abstention_rate'] * 100:7.2f}%",
        "  latency p50/p95/p99 "
        f"{metrics['latency_p50_ms']:.2f}/{metrics['latency_p95_ms']:.2f}/"
        f"{metrics['latency_p99_ms']:.2f} ms",
        f"  calibration error    {metrics['expected_calibration_error']:.4f}",
    ]
    if "delta" in report:
        lines.append("  before -> after deltas")
        for metric in sorted(report["delta"]):
            if metric == "case_count":
                continue
            lines.append(f"    {metric:<28} {report['delta'][metric]:+.6f}")
    lines.append("  confidence calibration / coverage")
    for row in report["current"]["confidence_bins"]:
        upper_mark = "]" if row["upper_inclusive"] else ")"
        upper = 1.0 if row["upper_inclusive"] else row["upper_exclusive"]
        lines.append(
            f"    [{row['lower_inclusive']:.1f}, {upper:.1f}{upper_mark} "
            f"n={row['case_count']:5d} coverage={row['coverage'] * 100:6.2f}% "
            f"accuracy={row['top_1_accuracy'] * 100:6.2f}%"
        )
    if failures:
        lines.append("Budget failures:")
        lines.extend(f"  - {failure}" for failure in failures)
    elif budgets_supplied:
        lines.append("Budgets: pass")
    else:
        lines.append("Budgets: not supplied")
    return "\n".join(lines) + "\n"


def _positive_int(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be an integer") from error
    if parsed < 1:
        raise argparse.ArgumentTypeError("must be >= 1")
    return parsed


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "current", type=Path, help="current case or aggregate JSONL benchmark"
    )
    parser.add_argument("--baseline", type=Path, help="before/baseline JSONL benchmark")
    parser.add_argument("--top-k", type=_positive_int, default=5)
    parser.add_argument("--budgets", type=Path, help="explicit quality budget JSON")
    parser.add_argument(
        "--json-output", type=Path, help="write deterministic machine-readable report"
    )
    args = parser.parse_args(argv)

    try:
        current = load_benchmark(args.current)
        baseline = load_benchmark(args.baseline) if args.baseline is not None else None
        report = build_report(current, top_k=args.top_k, baseline=baseline)
        budgets = load_budgets(args.budgets) if args.budgets is not None else []
        failures = check_budgets(report, budgets)
        report["budget_result"] = {
            "evaluated": len(budgets),
            "failures": list(failures),
            "passed": not failures,
        }
        machine = json.dumps(report, indent=2, sort_keys=True, allow_nan=False) + "\n"
        if args.json_output is not None:
            args.json_output.write_text(machine, encoding="utf-8")
        sys.stdout.write(
            render_human(report, failures, budgets_supplied=args.budgets is not None)
        )
        return 1 if failures else 0
    except (InputError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
