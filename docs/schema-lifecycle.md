# Schema lifecycle

Condense versions several documents independently. This file is the policy. Numbers do not change in superiority Phase 4.

Additive optional fields are allowed within a major version. Unknown keys stay rejected on filter and IR documents. Unknown runtime stage names cannot instantiate a stage. Phase 4 added optional IR fields on `ResourceDocument` (`format`, `add`, `change`, `destroy`, `replace`, `capped`) and `ResourceRow` (`address`, `action`, `reason`, `resourceType`), plus `DiagnosticDocument.tool`. They are omitted when unused so docker/eslint schema-1 JSON stays byte-stable. See [machine-output.md](machine-output.md).

| Surface | Current | Ahead or unknown | Missing version |
|---|---|---|---|
| Filter TOML | `FilterOverrideConfig.SCHEMA_VERSION` = 1 | Override fail-open skip tier; builtin fail-closed / fail build | Override fail-open |
| IR / explain | `Document.SCHEMA_VERSION` = 1 | `JsonRenderer.parse` rejects; proxy keeps child exit and last-good text or opaque | Additive optional fields such as `termination`, `format`, and `tool` still parse |
| MCP | JSON-RPC 2.0; protocols `2024-11-05`, `2025-03-26`, `2025-06-18` | Unknown protocol uses `FALLBACK_PROTOCOL` (`2024-11-05`) | Tool payloads reuse CLI records |
| SQLite | `SchemaMigrator.TARGET_VERSION` = **2** | Skip migrate, doctor `schema_ahead`, proxy fail-open | Migrate forward only |
| Trust | `TrustStore.SCHEMA_VERSION` = 1 | Ignore store (empty pins) | Treat as empty |
| Discover / languages | definition `schema_version` = 1 | Fail-closed catalog load / fail build | Fail-closed |
| Propose / doctor JSON | report schema 1 / migrator target | Additive fields only | N/A |

Invalid builtins fail Maven `process-classes`. User override failures never change a proxied child exit.

See [filter-schema.md](filter-schema.md), [ir.md](ir.md), [persistence.md](persistence.md), and [registries.md](registries.md).
