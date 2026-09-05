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
