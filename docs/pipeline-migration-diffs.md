# Pipeline migration diffs

Phase 4 locks filtered corpus output in `condense/src/test/resources/corpus/golden/`.
`GoldenLockTest` fails `mvn test` on any byte difference.

If a migration must change output, update that one golden file and add a one-line
reason here. Unexplained golden edits fail review.

| Catalog id | Why the locked bytes changed |
|---|---|
| pytest/typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| jest/typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| jest/passing | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| vitest/typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| vitest/passing | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| eslint/typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| eslint/passing | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| eslint-json/typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| tsc/typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| npm-install/typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| npm-install/with-vulns | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| pip-install/typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| ruff-check/typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| ruff-check/passing | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| mvn/success | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| mvn/failure | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| gradle/success | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| gradle/failure | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| make/success | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| make/failure | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| git-status/clean | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| git-status/modified | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| git-status/staged | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| git-status/untracked | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| git-status/mixed | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| git-status/detached-head | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| git-status/porcelain-mixed | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| git-diff/stat | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| git-diff/typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| git-add/typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| git-log/typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| git-commit/typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| grep/typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| find/typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| ls/large | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| cat/large | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| kubectl/pods-unhealthy | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| kubectl/pods-healthy | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| docker-ps/typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| docker-build/typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| docker-logs/long | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| aws/describe-instances | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| go-test/json-typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| go-test/json-passing | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| golangci-lint/typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| cargo-test/typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| cargo-test/passing | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| cargo-clippy/typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| cargo-build/typical | Phase 6 provenance header (`condense[filtered]`) on `FilterResult.of` |
| npm-install/typical | Phase 9 streams irrevocable `npm warn` lines plus the existing summary |
| npm-install/with-vulns | Phase 9 streams irrevocable `npm warn` lines plus the existing summary |
| docker-build/typical | Phase 9 streams `#N DONE` step completions plus the existing summary |
| terraform/plan-json | New superiority Phase 4 row. Machine-UI NDJSON plan becomes infra resource text |
| terraform/validate-json | New superiority Phase 4 row. Validate JSON becomes diagnostic text |
| terraform/init-text | New superiority Phase 4 row. Grouping keeps the initialized line |
| terraform-fmt/files | New superiority Phase 4 row. Fmt filenames stay identity (`too_small`) |
| terraform-state/typical | New superiority Phase 4 row. State list grouped by type (`too_small`) |
| tofu/plan-json | New superiority Phase 4 row. OpenTofu machine-UI plan |
| tofu-fmt/files | New superiority Phase 4 row. Same fmt identity as terraform-fmt |
| tofu-state/typical | New superiority Phase 4 row. Same state-list grouping as terraform-state |
| dotnet-format/typical | New superiority Phase 5 row. Human format console keeps warning and Formatted lines |
| msbuild/typical | New superiority Phase 5 row. Same compiler-error grouping as dotnet-build |
| ps/typical | Superiority Phase 6 leftover. `head_tail` widened to 8/8 so fuzz-prefixed noise cannot drop the header |
| gt/typical | Superiority Phase 6 leftover. Typical fixture lengthened so Linux LF token counts still meet the 60% floor at `head_tail` 8/8 |
| jj/typical | Superiority Phase 6 leftover. Typical fixture lengthened so Linux LF token counts still meet the 60% floor at `head_tail` 8/8 |
| jira/typical | Superiority Phase 6 leftover. Typical fixture lengthened so Linux LF token counts still meet the 60% floor at `head_tail` 8/8 |
| yadm/typical | Superiority Phase 6 leftover. Typical fixture lengthened so Linux LF token counts still meet the 60% floor at `head_tail` 8/8 |
| mix-format/typical | Superiority Phase 6 leftover. Typical fixture lengthened so Linux LF token counts still meet the 60% floor at `head_tail` 8/8 |
| jest/typical | Phase 4 JS/TS runner depth: preserves failing test titles, error messages, and assertion diffs |
| vitest/typical | Phase 4 JS/TS runner depth: preserves failing test diagnostic details and assertion diffs |
| playwright/typical | Phase 4 JS/TS runner depth: dedicated PlaywrightSummaryStage replacing grouping |
| git-diff/typical | Stage 2 P0-1: unified patches keep file headers, hunk headers, and bounded change lines instead of collapsing to a `+N / -M` count |
| gradle/failure | Stage 2 P0-2 fidelity guard: dropping Java compiler diagnostics in favour of a build summary is a real loss, so the raw output is returned |
| cargo-test/typical | Stage 2 P0-2 fidelity guard: dropping Rust panic payloads and source locations triggers raw passthrough |
| cat/large | Stage 2 P0-4: fixture extended to 300 lines so the new 8000-byte gate and 200-line threshold apply; head/tail window widened to 120/60 and the omission marker now states the omitted 1-based line range `lines 121..240 of 300` |
| cat/package-json | Stage 2 P0-4: new intact-manifest row. Small JSON (under 32 KiB) passes through byte-for-byte in normal mode instead of being reduced to a skeleton (exempt `too_small`) |
| gh/typical | Stage 2 P0-4: `head_tail` omission marker now includes the omitted 1-based line range |
| glab/typical | Stage 2 P0-4: `head_tail` omission marker now includes the omitted 1-based line range |
| gt/typical | Stage 2 P0-4: `head_tail` omission marker now includes the omitted 1-based line range |
| jj/typical | Stage 2 P0-4: `head_tail` omission marker now includes the omitted 1-based line range |
| jira/typical | Stage 2 P0-4: `head_tail` omission marker now includes the omitted 1-based line range |
| yadm/typical | Stage 2 P0-4: `head_tail` omission marker now includes the omitted 1-based line range |
| mix-format/typical | Stage 2 P0-4: `head_tail` omission marker now includes the omitted 1-based line range |
| ps/typical | Stage 2 P0-4: `head_tail` omission marker now includes the omitted 1-based line range |
| psql/typical | Stage 2 P0-4: `head_tail` omission marker now includes the omitted 1-based line range |
| bundle-install/typical | Stage 2 P0-4: `tail_lines` truncation notice replaced with the standard 1-based range marker; the longer marker on a small payload lowers max savings to 58%, so the entry floor is now 55 |
| curl/typical | Stage 2 P0-4: `tail_lines` truncation notice replaced with the standard 1-based range marker |
| docker-logs/long | Stage 2 P0-4: `tail_lines` truncation notice replaced with the standard 1-based range marker (short fixture shows `0 lines omitted: showing all N lines`) |
| dotnet-restore/typical | Stage 2 P0-4: `tail_lines` truncation notice replaced with the standard 1-based range marker |
| du/typical | Stage 2 P0-4: `tail_lines` truncation notice replaced with the standard 1-based range marker |
| helm/typical | Stage 2 P0-4: `tail_lines` truncation notice replaced with the standard 1-based range marker |
| wget/typical | Stage 2 P0-4: `tail_lines` truncation notice replaced with the standard 1-based range marker |
