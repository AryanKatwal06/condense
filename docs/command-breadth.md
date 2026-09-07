# Command breadth

Superiority Phase 6 covers every verified zap command family at pin `d9498bb` with a leftover catalog definition or an already-shipped higher-fidelity host. New families are data: a TOML pipeline, fixtures, a corpus row, and a golden. No new Java stage is required.

The machine-readable pin is `condense/src/test/resources/inventory/zap-families.json`. `CompetitiveInventoryTest` fails `mvn test` if a non-excluded zap family lacks a registered Condense prefix. Generated human output lives in [generated/command-inventory.md](generated/command-inventory.md). Do not edit that file by hand.

`mvn test` never contacts GitHub. A quarterly workflow (`.github/workflows/zap-inventory-freshness.yml`) diffs zap HEAD `src/filters/*.toml` against the pin and opens or updates an issue.

## Prefix rules

Claim the noisy command, not the whole tool.

- `systemctl status` not `systemctl`
- `ansible-playbook` not `ansible`
- `fail2ban-client` not `fail2ban`
- `terraform state list` not `terraform state`
- Bare `iptables`, `df`, `du`, `ps`, and `ping` are the tool itself and are claimed
- `ssh` is claimed as `ssh` with a keep-errors pipeline. Empty or malformed sessions fail open
- `sops` keeps status and errors only. Nonzero exits pass through raw so ciphertext is never rewritten
- `jq` uses `json_structure` when the payload is JSON and returns the original text when it is not
- `npx biome` / `pnpm exec` are not claimed. Unprefixed `npx` stays passthrough
- `spring-boot` is the Spring Boot CLI. It does not steal `mvn`

Aliases alone do not count. A zap family row must point at a real `filters/index.toml` definition that registers at least one of that family's commands.

## iptables versus zap

Zap's iptables TOML strips `Chain DOCKER` and `Chain BR-` headers and can leave orphan rule lines. Condense uses `state_machine` to keep `INPUT` / `FORWARD` / `OUTPUT` (and other non-Docker) headers with their following rules, and discards Docker and bridge sections entirely. `grouping` is not used here because it would destroy rule text.

## Fixture kinds

Each new leftover has six fixtures under `src/test/resources/fixtures/<name>/`:

| File | Role |
|---|---|
| `typical.txt` | Compressing representative. Corpus row and golden. `savings_floor` ≥ 60 or a listed exemption. No new `meets_contribution_bar: false` |
| `failure.txt` or `success.txt` | The other exit path |
| `empty.txt` | Zero bytes or whitespace |
| `malformed.txt` | Truncated or garbage |
| `unicode.txt` | Non-ASCII paths or messages that must survive |
| `oversized.txt` | Long enough to exercise tail or group caps |

Only `typical` is a corpus/golden row. `LeftoverBreadthFixtureTest` runs the other five: no throw, empty stays empty, malformed does not become a fake success, a unicode critical substring survives, oversized does not hang.

## Fail-open

Unrecognized variants and parse uncertainty passthrough. Stage exceptions continue from last-good text. Child exit is unchanged. `jq` and `sops` never turn secret or user JSON into a successful-looking summary on failure.

## Native matrix

`NativeCatalogMatrixIT` PATH-stubs every `filters/index.toml` definition using `inventory/native-catalog-matrix.json`. Compressed rows must carry `condense[filtered]`. Passthrough rows (`git push` rejected) stay unstamped; the tee footer is stripped before that comparison. `NativeCatalogMatrixCoverageTest` fails if an index name is missing from that matrix. A missing native binary fails the IT; it does not skip.

## Zap-internal exclusions

Zap utilities that are not third-party proxies stay `kind=zap_internal` in the pin: `read`, `summary`, `tree`, `wc`, `json`, `format`, `env`, `pipe`, `local_llm`, `log`, `deps`. They are not leftover commands.
