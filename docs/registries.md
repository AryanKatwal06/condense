# Closed registries

Runtime registries stay closed. No classpath directory walk, no user-supplied class names, no runtime `ServiceLoader` for stages.

## Generated at compile time

Stage aliases, capabilities, and factory bindings come from `@DeclarativeStage` on each `FilterStage`. `StageRegistryProcessor` emits `GeneratedStageRegistry`. Adding a stage does not edit `StageFactory`. The alias list is [generated/stage-inventory.md](generated/stage-inventory.md).

## Keep handwritten

These are security or TCB boundaries. Do not generate them with classpath scans.

| Registry | Why it stays explicit |
|---|---|
| `HookTool` / `HookInstaller` | Writes user-home agent configs |
| `Capability` / `TrustGate` | Decides whether hostile project overrides run |
| `StrategyRegistry` + `@CommandFilter` beans | Dispatch, including routers such as `PythonFilter` |
| `DiscoverRuleCatalog` + `discover/index.toml` | Fail-closed TCB, index-only |
| `LanguageDefinition.family` | Builtin language rules, no project override |
| `McpHandlers` tool dispatch | Closed method and tool surface |
| Picocli command classes | Small security-sensitive CLI surface |

Catalog leftovers (`filters/index.toml` commands without a Java bean) are already data-driven on `CatalogBackedFilter`.
