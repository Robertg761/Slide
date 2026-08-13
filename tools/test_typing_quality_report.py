#!/usr/bin/env python3
"""Focused tests for the privacy-safe typing quality report."""

from __future__ import annotations

import contextlib
import io
import json
import math
import tempfile
import unittest
from pathlib import Path

import typing_quality_report as report


def case(
    rank: int | None,
    *,
    committed: bool = True,
    fallback: bool = False,
    latency: float = 1.0,
    confidence: float = 0.8,
    kind: str = "swipe",
) -> dict[str, object]:
    return {
        "schema_version": 1,
        "input_kind": kind,
        "expected_rank": rank,
        "committed": committed,
        "used_fallback": fallback,
        "latency_ms": latency,
        "confidence": confidence,
    }


def write_jsonl(path: Path, rows: list[dict[str, object]]) -> None:
    path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")


def aggregate_fixture() -> dict[str, object]:
    return {
        "schema_version": 1,
        "record_type": "aggregate",
        "input_kind": "swipe",
        "case_count": 4,
        "rank_counts": {"1": 1, "2": 1, "5": 1},
        "commit_count": 3,
        "wrong_commit_count": 2,
        "fallback_count": 1,
        "latencies_ms": [1.0, 2.0, 3.0, 100.0],
        "confidence_bins": [
            {
                "index": 0,
                "case_count": 1,
                "top_1_correct_count": 0,
                "confidence_sum": 0.05,
            },
            {
                "index": 2,
                "case_count": 1,
                "top_1_correct_count": 0,
                "confidence_sum": 0.25,
            },
            {
                "index": 6,
                "case_count": 1,
                "top_1_correct_count": 0,
                "confidence_sum": 0.65,
            },
            {
                "index": 9,
                "case_count": 1,
                "top_1_correct_count": 1,
                "confidence_sum": 0.95,
            },
        ],
    }


class TypingQualityReportTest(unittest.TestCase):
    def test_calculates_accuracy_commits_fallback_latency_and_calibration(self) -> None:
        cases = [
            report.parse_case(
                case(1, latency=1, confidence=0.95), location="fixture:1"
            ),
            report.parse_case(
                case(2, latency=2, confidence=0.65), location="fixture:2"
            ),
            report.parse_case(
                case(None, committed=False, fallback=True, latency=3, confidence=0.05),
                location="fixture:3",
            ),
            report.parse_case(
                case(5, latency=100, confidence=0.25), location="fixture:4"
            ),
        ]

        result = report.calculate(cases, top_k=5)
        metrics = result["metrics"]

        self.assertEqual(4, metrics["case_count"])
        self.assertEqual(0.25, metrics["top_1_accuracy"])
        self.assertEqual(0.75, metrics["top_k_accuracy"])
        self.assertEqual(0.5, metrics["wrong_commit_rate"])
        self.assertEqual(0.25, metrics["fallback_rate"])
        self.assertEqual(0.25, metrics["abstention_rate"])
        self.assertAlmostEqual(1 / 3, metrics["commit_precision"], places=6)
        self.assertEqual(2.0, metrics["latency_p50_ms"])
        self.assertEqual(100.0, metrics["latency_p95_ms"])
        self.assertEqual(100.0, metrics["latency_p99_ms"])
        self.assertEqual(10, len(result["confidence_bins"]))
        self.assertAlmostEqual(
            1.0,
            sum(row["coverage"] for row in result["confidence_bins"]),
        )

    def test_aggregate_records_produce_the_same_metrics_as_cases(self) -> None:
        cases = [
            report.parse_case(case(1, latency=1, confidence=0.95), location="case:1"),
            report.parse_case(case(2, latency=2, confidence=0.65), location="case:2"),
            report.parse_case(
                case(None, committed=False, fallback=True, latency=3, confidence=0.05),
                location="case:3",
            ),
            report.parse_case(case(5, latency=100, confidence=0.25), location="case:4"),
        ]
        aggregate = report.parse_aggregate(aggregate_fixture(), location="aggregate:1")

        self.assertEqual(
            report.calculate(cases, top_k=5),
            report.calculate([aggregate], top_k=5),
        )

    def test_aggregate_counts_and_confidence_ranges_are_validated(self) -> None:
        too_many_ranks = aggregate_fixture()
        too_many_ranks["rank_counts"] = {"1": 5}
        with self.assertRaisesRegex(report.InputError, "rank_counts exceed"):
            report.parse_aggregate(too_many_ranks, location="fixture")

        mismatched_bins = aggregate_fixture()
        bins = list(mismatched_bins["confidence_bins"])
        bins[0] = dict(bins[0], confidence_sum=0.9)
        mismatched_bins["confidence_bins"] = bins
        with self.assertRaisesRegex(report.InputError, "outside the bin"):
            report.parse_aggregate(mismatched_bins, location="fixture")

        content_bearing = aggregate_fixture()
        content_bearing["words"] = ["not retained"]
        with self.assertRaisesRegex(report.InputError, "unknown fields"):
            report.parse_aggregate(content_bearing, location="fixture")

    def test_loader_accepts_aggregate_jsonl_and_rejects_mixed_styles(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            path = Path(name) / "aggregate.jsonl"
            write_jsonl(path, [aggregate_fixture()])
            loaded = report.load_benchmark(path)
            self.assertIsInstance(loaded[0], report.Aggregate)

            write_jsonl(path, [aggregate_fixture(), case(1)])
            with self.assertRaisesRegex(report.InputError, "cannot be mixed"):
                report.load_benchmark(path)

            write_jsonl(path, [case(1), case(1, kind="typing")])
            with self.assertRaisesRegex(report.InputError, "input_kind differs"):
                report.load_benchmark(path)

    def test_report_has_deterministic_before_after_deltas(self) -> None:
        before = [report.parse_case(case(2), location="before")]
        after = [report.parse_case(case(1), location="after")]

        actual = report.build_report(after, top_k=1, baseline=before)
        encoded_once = json.dumps(actual, indent=2, sort_keys=True, allow_nan=False)
        encoded_twice = json.dumps(actual, indent=2, sort_keys=True, allow_nan=False)

        self.assertEqual(1.0, actual["delta"]["top_1_accuracy"])
        self.assertEqual(encoded_once, encoded_twice)

    def test_baseline_must_use_the_same_kind_and_case_count(self) -> None:
        swipe = [report.parse_case(case(1), location="swipe")]
        typing = [report.parse_case(case(1, kind="typing"), location="typing")]
        with self.assertRaisesRegex(report.InputError, "input_kind must match"):
            report.build_report(swipe, top_k=5, baseline=typing)

        larger = swipe + [report.parse_case(case(2), location="larger")]
        with self.assertRaisesRegex(report.InputError, "case_count must match"):
            report.build_report(larger, top_k=5, baseline=swipe)

    def test_unknown_content_bearing_fields_are_rejected(self) -> None:
        for forbidden in ("word", "typed_text", "points", "surrounding_context"):
            value = case(1)
            value[forbidden] = "not retained"
            with self.subTest(forbidden=forbidden):
                with self.assertRaisesRegex(report.InputError, "unknown fields"):
                    report.parse_case(value, location="fixture")

    def test_malformed_non_finite_and_out_of_range_values_are_rejected(self) -> None:
        invalid = [
            ("schema_version", 1.0),
            ("input_kind", ["swipe"]),
            ("expected_rank", 0),
            ("expected_rank", True),
            ("committed", 1),
            ("used_fallback", "false"),
            ("latency_ms", -0.1),
            ("latency_ms", math.inf),
            ("latency_ms", 10**400),
            ("confidence", -0.1),
            ("confidence", 1.1),
            ("confidence", math.nan),
        ]
        for field, bad_value in invalid:
            value = case(1)
            value[field] = bad_value
            with self.subTest(field=field, bad_value=bad_value):
                with self.assertRaises(report.InputError):
                    report.parse_case(value, location="fixture")

    def test_loader_rejects_blank_lines_and_json_non_finite_constants(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            path = Path(name) / "cases.jsonl"
            path.write_text(json.dumps(case(1)) + "\n\n", encoding="utf-8")
            with self.assertRaisesRegex(report.InputError, "blank lines"):
                report.load_benchmark(path)

            path.write_text(
                json.dumps(case(1)).replace("0.8", "NaN") + "\n", encoding="utf-8"
            )
            with self.assertRaisesRegex(report.InputError, "non-finite"):
                report.load_benchmark(path)

            path.write_text(
                '{"schema_version":1,"schema_version":1}\n', encoding="utf-8"
            )
            with self.assertRaisesRegex(report.InputError, "duplicate JSON field"):
                report.load_benchmark(path)

    def test_explicit_budget_passes_and_regression_fails(self) -> None:
        before = [report.parse_case(case(1), location="before") for _ in range(2)]
        after = [
            report.parse_case(case(1), location="after:1"),
            report.parse_case(case(2), location="after:2"),
        ]
        built = report.build_report(after, top_k=5, baseline=before)
        budgets = [
            {"metric": "top_1_accuracy", "minimum": 0.4, "max_regression": 0.1},
            {"metric": "wrong_commit_rate", "maximum": 0.6},
        ]

        failures = report.check_budgets(built, budgets)

        self.assertEqual(1, len(failures))
        self.assertIn("top_1_accuracy regressed", failures[0])

    def test_max_regression_requires_baseline(self) -> None:
        current = [report.parse_case(case(1), location="fixture")]
        built = report.build_report(current, top_k=5)
        with self.assertRaisesRegex(report.InputError, "no baseline"):
            report.check_budgets(
                built,
                [{"metric": "top_1_accuracy", "max_regression": 0.01}],
            )

    def test_cli_writes_json_and_returns_one_on_budget_failure(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            before_path = directory / "before.jsonl"
            after_path = directory / "after.jsonl"
            budgets_path = directory / "budgets.json"
            output_path = directory / "report.json"
            write_jsonl(before_path, [case(1), case(1)])
            write_jsonl(after_path, [case(1), case(2)])
            budgets_path.write_text(
                json.dumps(
                    {
                        "schema_version": 1,
                        "budgets": [
                            {
                                "metric": "top_1_accuracy",
                                "minimum": 0.5,
                                "max_regression": 0.1,
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )

            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                exit_code = report.main(
                    [
                        str(after_path),
                        "--baseline",
                        str(before_path),
                        "--budgets",
                        str(budgets_path),
                        "--json-output",
                        str(output_path),
                    ]
                )

            self.assertEqual(1, exit_code)
            self.assertIn("Budget failures", stdout.getvalue())
            machine = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(0.5, machine["current"]["metrics"]["top_1_accuracy"])


if __name__ == "__main__":
    unittest.main()
