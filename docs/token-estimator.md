# Token estimator

Condense does not ship a tokenizer. `condense gain` reports **estimates** produced by `utf8_weighted_v1`, with a measured error bound against a public reference tokenizer.

## Algorithm

`Utf8WeightedTokenEstimator` walks Unicode **code points** (not UTF-16 units, not raw bytes):

1. Null, empty, and unreadable files count as 0 (fail-open).
2. Files are decoded as UTF-8 with replacement for malformed bytes. There is no `Charset.defaultCharset()` and no `Files.size()`.
3. Zero-width joiners, variation selectors, and Fitzpatrick modifiers are skipped so emoji sequences are not double-counted.
4. CJK (Han), Hangul, kana, Bopomofo, CJK punctuation / fullwidth forms, and emoji / pictographs count as **one token per code point**.
5. Remaining code points accumulate as a Latin run and are counted with ceiling division by **4**.

The same function is used for strings and files. `TokenCounter` is a static facade over this estimator so `FilterResult` call sites do not churn.

## Reference and bound

| | |
|---|---|
| Estimator name | `utf8_weighted_v1` |
| Reference tokenizer | `cl100k_base` (jtokkit, **test scope only**) |
| Measured p95 relative error | 0.366 on 59 corpus files (51 filter fixtures + 6 Unicode samples + empty + long Latin). The Phase 2 46-file sample measured 0.333; the published bound and CI gate did not change. |
| **Published p95 relative error** | **0.35** (35%) |
| CI gate | published + 0.05 = 0.40 |

Relative error is `|estimate − reference| / max(reference, 1)`. The published figure is the measured p95 rounded up to a clean percentage. `TokenEstimatorAccuracyTest` fails `mvn test` if a later estimator change exceeds the gate.

This bound is versus **cl100k_base**, not Claude's tokenizer and not tiktoken at runtime. A 35% p95 is honest about a four-line heuristic; it is not tokenizer-grade precision.

## What `gain` shows

Text summary labels input / output / saved as estimates and prints:

```
Estimator:            utf8_weighted_v1  p95 ±35% vs cl100k_base
```

JSON keeps the existing fields (`input_tokens`, `output_tokens`, `tokens_saved`, `savings_pct`, …) and adds:

```json
"estimator": {
  "name": "utf8_weighted_v1",
  "reference": "cl100k_base",
  "p95_rel_error": 0.35
}
```

## Historical rows

`raw_tokens` / `out_tokens` stay integers. Rows written before this estimator used `(length or byte-size) / 4`. New rows use `utf8_weighted_v1`. Stored history is never rewritten, so a mixed window in `gain` is permanent. The estimator metadata describes **how new counts are produced**. See [persistence.md](persistence.md).

## Why not a real tokenizer in the binary

A tiktoken-class dependency would add native-image reachability, startup cost, and a vocabulary blob to every platform image. The product requirement is a yardstick a reviewer can defend, not tokenizer-grade counts in the hot path. The yardstick lives in tests.
