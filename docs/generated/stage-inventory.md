# Stage inventory

Generated from `@DeclarativeStage`. Do not edit by hand.

| Canonical | Aliases | Capability | Class |
|---|---|---|---|
| `aggregate_by_key` | `aggregate_by_key`, `aggregate-by-key` | RESHAPE | `com.condense.filter.strategy.AggregateByKeyStage` |
| `ansi_strip` | `ansi_strip`, `ansi-strip`, `ansi` | REDUCE | `com.condense.filter.strategy.AnsiStripStrategy` |
| `cargo_clippy_summary` | `cargo_clippy_summary` | RESHAPE | `com.condense.filter.stage.CargoClippySummaryStage` |
| `cargo_install_summary` | `cargo_install_summary` | RESHAPE | `com.condense.filter.stage.CargoInstallSummaryStage` |
| `cargo_test_summary` | `cargo_test_summary` | RESHAPE | `com.condense.filter.stage.CargoTestSummaryStage` |
| `cat_content` | `cat_content` | RESHAPE | `com.condense.filter.stage.CatContentStage` |
| `deduplication` | `deduplication`, `dedup` | REDUCE | `com.condense.filter.strategy.DeduplicationStrategy` |
| `docker_build_summary` | `docker_build_summary` | RESHAPE | `com.condense.filter.stage.DockerBuildSummaryStage` |
| `docker_ps` | `docker_ps`, `docker-ps` | RESHAPE | `com.condense.filter.strategy.DockerPsStage` |
| `eslint_json` | `eslint_json` | RESHAPE | `com.condense.filter.stage.EsLintJsonStage` |
| `eslint_text` | `eslint_text` | RESHAPE | `com.condense.filter.stage.EsLintTextStage` |
| `git_add_summary` | `git_add_summary` | RESHAPE | `com.condense.filter.stage.GitAddSummaryStage` |
| `git_commit_summary` | `git_commit_summary` | RESHAPE | `com.condense.filter.stage.GitCommitSummaryStage` |
| `git_diff_summary` | `git_diff_summary` | RESHAPE | `com.condense.filter.stage.GitDiffSummaryStage` |
| `git_log` | `git_log` | RESHAPE | `com.condense.filter.stage.GitLogStage` |
| `git_push_summary` | `git_push_summary` | RESHAPE | `com.condense.filter.stage.GitPushSummaryStage` |
| `git_status` | `git_status`, `git-status` | RESHAPE | `com.condense.filter.strategy.GitStatusStage` |
| `golangci_summary` | `golangci_summary` | RESHAPE | `com.condense.filter.stage.GolangciSummaryStage` |
| `gradle_summary` | `gradle_summary` | RESHAPE | `com.condense.filter.stage.GradleSummaryStage` |
| `grouping` | `grouping`, `group` | RESHAPE | `com.condense.filter.strategy.GroupingStrategy` |
| `head_tail` | `head_tail`, `head-tail` | REDUCE | `com.condense.filter.strategy.HeadTailStage` |
| `jest_summary` | `jest_summary` | RESHAPE | `com.condense.filter.stage.JestSummaryStage` |
| `json_lines` | `json_lines`, `json-lines` | RESHAPE | `com.condense.filter.strategy.JsonLinesStage` |
| `json_structure` | `json_structure`, `json-structure`, `json` | RESHAPE | `com.condense.filter.strategy.JsonStructureStrategy` |
| `kubectl_dispatch` | `kubectl_dispatch` | RESHAPE | `com.condense.filter.stage.KubectlDispatchStage` |
| `ls_empty_tree_fallback` | `ls_empty_tree_fallback` | RESHAPE | `com.condense.filter.stage.LsEmptyTreeFallbackStage` |
| `make_summary` | `make_summary` | RESHAPE | `com.condense.filter.stage.MakeSummaryStage` |
| `mvn_summary` | `mvn_summary` | RESHAPE | `com.condense.filter.stage.MvnSummaryStage` |
| `npm_install_summary` | `npm_install_summary` | RESHAPE | `com.condense.filter.stage.NpmInstallSummaryStage` |
| `pip_install_summary` | `pip_install_summary` | RESHAPE | `com.condense.filter.stage.PipInstallSummaryStage` |
| `pytest_summary` | `pytest_summary` | RESHAPE | `com.condense.filter.stage.PytestSummaryStage` |
| `regex_capture` | `regex_capture`, `regex-capture` | REWRITE | `com.condense.filter.strategy.RegexCaptureStage` |
| `ruff_summary` | `ruff_summary` | RESHAPE | `com.condense.filter.stage.RuffSummaryStage` |
| `state_machine` | `state_machine`, `state-machine` | REWRITE | `com.condense.filter.strategy.StateMachineStrategy` |
| `tail_lines` | `tail_lines`, `tail-lines` | REDUCE | `com.condense.filter.strategy.TailLinesStage` |
| `tree_compression` | `tree_compression`, `tree-compression`, `tree` | REDUCE | `com.condense.filter.strategy.TreeCompressionStrategy` |
| `tsc_summary` | `tsc_summary` | RESHAPE | `com.condense.filter.stage.TscSummaryStage` |
| `vitest_summary` | `vitest_summary` | RESHAPE | `com.condense.filter.stage.VitestSummaryStage` |
