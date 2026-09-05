# `condense explain`

`condense explain` shows which filter stages ran, which lines each stage dropped or added, how many tokens moved, and which precedence tier supplied the pipeline.

Options must come **before** the proxied command. The root parser stops at the first positional:

```
condense explain --format json pytest
condense explain --input fixture.txt --exit-code 1 git status
condense explain --stdin --format json npm install
```

Default (no `--input` / `--stdin`) executes the command the same way proxy mode does, then prints the report instead of the filtered body. It does **not** write `commands` or `filter_outcomes`, and it does not tee.

## Accounting identities

Lines are split with `\\R` (the same splitter as output provenance). A rewritten line is one drop plus one add.

For every stage that ran:

- `input_lines = kept + dropped`
- `output_lines = kept + added`

Across the pipeline, including the synthetic `provenance` stage when `condense[filtered]` is stamped:

- `sum(dropped - added) = input_lines - output_lines`
- `sum(input_tokens - output_tokens) = first_input_tokens - last_output_tokens`

Token counts use `utf8_weighted_v1`. They are estimates. JSON includes the same `estimator` object as `condense gain` (name, reference tokenizer, published p95 relative error).

`raw_tokens` / `out_tokens` on the report are the same `FilterResult` fields `apply()` would have written to analytics. They can differ from the pipeline-local totals because `raw_tokens` counts both stdout and stderr files, while the pipeline often sees only `selectInput()`.

## Tiers

Winning `tier` is `project`, `global`, `builtin`, or `passthrough`.

Skipped tiers use a closed reason set: `absent`, `invalid`, `untrusted`, `capability`, `no_match`, `error`. An untrusted project `.condense/filters.toml` still does not apply — explain only names the skip.

A `beforePipeline` gate (for example a rejected `git push`) sets `gate.fired` and leaves compressing stages empty. Passthrough output is unstamped.

## Native proof

`NativeExplainIT` runs `explain --input <pytest fixture> --format json pytest` inside the shipped binary, checks the line identity, and asserts `gain --format json` still has `total_commands = 0`.
