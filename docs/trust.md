# Trust boundary and capabilities

A cloned repository must not change what an agent sees until a human reviews the project override. House fail-open still applies to the **proxied command**: a trust refusal never changes the child's exit code. It skips the project file and falls through to the user-global override, then the builtin pipeline.

Review is only `condense config trust`. Proxied commands never prompt.

## Who is trusted

| Source | Trust | Capability ceiling |
|---|---|---|
| Builtin `classpath:filters/*.toml` | TCB | none |
| User-global `{configDir}/filters.toml` | Trusted by location | none |
| Project `.condense/filters.toml` | Untrusted until TOFU or a valid CI hatch | always, including after trust |

A process that can write the config directory is already the TCB. Global files are not TOFU'd.

## Review (`condense config trust`)

```
condense config trust
condense config trust --accept --grant reduce,reshape
condense config trust --revoke
condense config trust --status
```

The command reads the project file **once**, classifies risk from that buffer, prints that same buffer, and only then hashes it (SHA-256, lowercase hex of the raw bytes). CRLF vs LF is a content change. `--accept` still prints the buffer first. Non-TTY without `--accept` prints the risk report and exits 1.

Default `--accept` grant is **reduce only**.

The trust store is `{configDir}/trust.json` (schema_version 1). The key is the canonical file path. `condense config trust --revoke` / `--status` are included. Accept and revoke invalidate the override loader cache so a just-trusted file is not hidden by a negative cache.

## Load path

After a project file parses, `TrustGate.decide(canonicalFile, bytes, requiredCaps)`:

- **APPLY** — build the pipeline
- **SKIP** — treat as no project match (cache the skip). Hash mismatch and missing grant are SKIP.

Skipped-override stderr (once per project-file load, not on stdout):

```
condense: skipped untrusted project filter override (.condense/filters.toml). Review with: condense config trust
```

Invalid TOML still fail-opens to the next tier.

## CI hatch

Honor project overrides without persisting trust only when **both** are true:

- `CONDENSE_TRUST_PROJECT_FILTERS` is `1` / `true` / `yes` (case-insensitive)
- At least one listed CI indicator is present: `CI`, `GITHUB_ACTIONS`, `GITLAB_CI`, `CIRCLECI`, `TRAVIS`, `BUILDKITE`, `TF_BUILD`, `JENKINS_URL`, `TEAMCITY_VERSION`

`CONDENSE_TRUST_PROJECT_CAPABILITIES` (comma list) is ignored unless a CI indicator is set. Default hatch grant is `reduce` only.

`CONDENSE_CONFIG_DIR` is not a CI signal. A lone hatch variable (for example from `.envrc`) does not apply project overrides.

## Capabilities

Required ⊆ granted. Missing grant skips the **entire** project file (no silent stage stripping). Empty `stages = []` still requires `reduce` (it replaces the builtin pipeline).

| Class | Stages |
|---|---|
| `reduce` | `ansi_strip`, `tail_lines`, `head_tail`, `tree_compression`, `deduplication` (and hyphen/short aliases) |
| `reshape` | `grouping`, `aggregate_by_key`, `json_structure`, `json_lines`, `docker_ps`, `git_status`, and every named `*_summary` alias |
| `rewrite` | `regex_capture`, `state_machine` |

## Provenance

Every `FilterResult.of` output starts with `condense[filtered]`. A whole line equal to that stamp becomes `condense[quoted]`. `FilterResult.passthrough` is neutralized but not stamped. Inline `[[tests]]` do not go through `FilterResult` and stay byte-stable.

## Path containment

Loader and trust-store writes call `SafePathValidator.contain(file, expectedParent)`. Project files live outside condense-owned directories, so `validateFileTarget` is not reused. `trust.json`, user `filters.toml`, and `tee/` are on the uninstall `--purge` allowlist.
