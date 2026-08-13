# Typing quality benchmark reports

`tools/typing_quality_report.py` turns typing and swipe benchmark outcomes into a stable report
that can be compared in CI or during decoder tuning. Its retained inputs and outputs contain no
typed text, intended words, surrounding editor context, gesture coordinates, or case identifiers.
Unknown fields are rejected rather than silently copied or ignored.

## Run it

```bash
python3 tools/typing_quality_report.py after.jsonl \
  --baseline before.jsonl \
  --top-k 5 \
  --budgets quality-budgets.json \
  --json-output build/reports/typing-quality.json
```

The current JSONL path is the only mandatory command-line argument. `--top-k` defaults to `5`.
The baseline, budgets, and machine-readable output are optional, except that a budget using
`max_regression` requires `--baseline`. A baseline must have the same input kind and case count;
because identifiers and content are deliberately absent, the caller must also use the same ordered
fixture corpus for a meaningful before/after comparison. Human-readable output always goes to
stdout.

Exit status is `0` for a valid report that meets every supplied budget, `1` for a budget failure,
and `2` for malformed input, an unsupported schema, or an I/O failure.

## Runtime support snapshot

The live IME separately keeps fixed-size, process-local aggregate buckets for typed/swipe decisions,
latency, candidate counts, confidence, actual neural/fallback provenance, explicit corrections,
stale/cancelled work, and model readiness. It stores no event rows or content and never writes or
sends this state automatically. Android's permission-gated service dump exposes the aggregate on
demand for support sessions:

```bash
adb shell dumpsys activity service com.slide/com.slide.ime.SlideInputMethodService
```

This diagnostic resets when the IME process does. It is operational evidence from that process,
not a labeled accuracy benchmark; the JSONL/report path below is what compares expected outcomes.

## Per-case JSONL schema

Write exactly one JSON object on every line; blank lines are invalid. Every field below is
mandatory; there are no optional per-case fields. Keep typing and swipe records in separate files;
`input_kind` must be consistent within a benchmark and must match its baseline.

```json
{"schema_version":1,"input_kind":"swipe","expected_rank":1,"committed":true,"used_fallback":false,"latency_ms":4.25,"confidence":0.87}
{"schema_version":1,"input_kind":"swipe","expected_rank":null,"committed":false,"used_fallback":true,"latency_ms":7.5,"confidence":0.12}
```

| Field | Contract |
| --- | --- |
| `schema_version` | Integer `1`. Booleans are not integers. |
| `input_kind` | Exactly `"typing"` or `"swipe"`. |
| `expected_rank` | One-based rank of the expected result, or `null` if it was absent. |
| `committed` | Whether the model automatically applied its top decision. For typing, leaving literal input unchanged is an abstention; for swipe, accepting the top decoded candidate is a commit. |
| `used_fallback` | Whether this case was served by a fallback decoder/path. |
| `latency_ms` | Finite end-to-end decision latency greater than or equal to zero. |
| `confidence` | Finite top-decision confidence in `[0, 1]`; use `0` when no decision exists. It may be an uncalibrated score, because the report measures its calibration. |

JSON `NaN`, infinities, duplicate keys, missing fields, unknown fields, invalid ranks, and values
outside their ranges fail closed. In particular, adding a `word`, `typed_text`, `points`, or
`surrounding_context` field makes the input invalid.

## Aggregate JSONL schema

A benchmark that should not retain even per-case outcome relationships can emit aggregate batch
records. Multiple aggregate lines are merged. Aggregate and per-case records cannot be mixed in
one file.

```json
{"schema_version":1,"record_type":"aggregate","input_kind":"swipe","case_count":4,"rank_counts":{"1":2,"2":1},"commit_count":3,"wrong_commit_count":1,"fallback_count":1,"latencies_ms":[2.0,2.5,3.0,8.0],"confidence_bins":[{"index":1,"case_count":1,"top_1_correct_count":0,"confidence_sum":0.15},{"index":7,"case_count":1,"top_1_correct_count":1,"confidence_sum":0.75},{"index":8,"case_count":1,"top_1_correct_count":0,"confidence_sum":0.85},{"index":9,"case_count":1,"top_1_correct_count":1,"confidence_sum":0.95}]}
```

All top-level fields in that example are mandatory:

- `rank_counts` maps canonical one-based rank strings to non-negative counts. Cases absent from
  the candidate list are the difference between `case_count` and the sum of these counts.
- `commit_count`, `wrong_commit_count`, and `fallback_count` are non-negative counts bounded by
  `case_count`. Wrong commits are also bounded by the number of incorrect top-one cases.
- `latencies_ms` is an unordered, content-free latency multiset with exactly `case_count` finite,
  non-negative entries. Keeping it unlinked from ranks and confidence retains exact percentiles
  without retaining per-case relationships.
- `confidence_bins` contains the non-empty deciles. Each entry has mandatory `index` (`0` through
  `9`), `case_count`, `top_1_correct_count`, and `confidence_sum`. Empty deciles may be omitted.
  Counts across the bins must equal `case_count`, correct counts must equal `rank_counts["1"]`,
  and every confidence sum must be possible for its decile.

## Metrics and definitions

Every valid report always includes these metrics:

- `top_1_accuracy`: cases whose expected result ranked first, divided by all cases.
- `top_k_accuracy`: cases whose expected rank is at most the requested `--top-k`, divided by all
  cases.
- `wrong_commit_rate`: committed cases whose expected result did not rank first, divided by all
  cases. This deliberately penalizes a confident bad automatic action more than an abstention.
- `commit_precision`: correct committed cases divided by committed cases.
- `fallback_rate` and `abstention_rate`: fallback cases and uncommitted cases, respectively,
  divided by all cases.
- `latency_p50_ms`, `latency_p95_ms`, and `latency_p99_ms`: deterministic nearest-rank
  percentiles.
- `expected_calibration_error`: coverage-weighted absolute difference between mean confidence and
  top-one accuracy across ten fixed confidence deciles.
- Ten confidence rows with case count, dataset coverage, mean confidence, top-one accuracy, and
  calibration gap. Empty rows are emitted too, keeping machine output structurally stable.

Supplying a baseline adds before-versus-after deltas for every scalar metric. A positive delta
always means the current numeric value is larger; interpretation depends on the metric.

## Quality budgets

A budget document has two mandatory top-level fields. `budgets` must be a non-empty array:

```json
{
  "schema_version": 1,
  "budgets": [
    {"metric": "top_1_accuracy", "minimum": 0.85, "max_regression": 0.01},
    {"metric": "wrong_commit_rate", "maximum": 0.015, "max_regression": 0.002},
    {"metric": "latency_p95_ms", "maximum": 25.0, "max_regression": 2.0}
  ]
}
```

Each entry requires `metric` and at least one of `minimum`, `maximum`, or `max_regression`; the
three limits are otherwise optional and may be combined. `max_regression` is measured in the
metric's native units, not as a relative percentage. Higher is better for top-one/top-k accuracy
and commit precision. Lower is better for wrong-commit, fallback, abstention, latency, and expected
calibration error.

Budgets may target any scalar metric above except `case_count`. The machine-readable report adds a
`budget_result` object containing the evaluated count, pass/fail state, and deterministic failure
messages.

## Real-swipe producer

`RealSwipeBenchmarkTest` writes this schema when the opt-in output property is present, while
keeping its existing console summary:

```bash
./gradlew :engine:testDebugUnitTest \
  --tests '*RealSwipeBenchmarkTest*' \
  -Dslide.realSwipeDataset=/path/to/test.jsonl \
  -Dslide.typingQualityOutput=build/reports/real-swipe.jsonl
```

The writer receives only the one-based expected rank, commit/fallback outcomes, elapsed decode
time, and a bounded softmax share for top-candidate confidence. The expected word is used for the
in-memory comparison and is never serialized; neither are the previous word, sentence, or points.
The benchmark directly constructs `GestureDecoder`, so `used_fallback` is always `false` in this
particular producer. A future failover benchmark must use the decoder's per-call provenance rather
than infer fallback from model availability.

An equivalent typed-correction producer belongs immediately around `TypingSuggester.suggest`: rank
the intended result in `TypingSuggestions.words`, set `committed` only when `autocorrection` is
non-null, and treat a literal/no-correction decision as an abstention. Generated fixtures must use
the intended result only for the in-memory comparison and never serialize it.
