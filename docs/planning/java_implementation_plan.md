# Zap — Java + GraalVM Native Image: 5-Phase Implementation Plan

> This document is the engineering contract for porting `bitan-del/zap` from Rust to Java 21 + GraalVM Native Image + Quarkus + picocli. It is written to be handed directly to an AI coding agent or a senior engineer as a build specification. Every phase ends with a concrete, verifiable deliverable. Every architectural decision is justified. Nothing is left to interpretation.

---

## Executive Summary

Zap is a CLI proxy written in Rust. It intercepts shell commands, filters their output using one of 12 strategies, logs token savings to local SQLite, and installs shell hooks into AI tool config directories. The Java port must produce a GraalVM native binary — not a fat JAR — because startup latency is the product's core non-functional requirement. A JVM startup of 500 ms per invocation destroys the product's value proposition. GraalVM native image delivers ~50–80 ms cold start, which is the acceptable ceiling.

The five phases are structured so that each one produces a **runnable, testable artifact**. You never have a phase that ends with half-working code. Phase 1 ends with a working skeleton that compiles to native. Phase 2 ends with a proxy that can actually run real commands and filter their output. Phase 3 ends with all 42 command filters implemented and tested. Phase 4 ends with the analytics engine and hook installer. Phase 5 ends with cross-platform native binaries released to GitHub.

---

## Phase 1 — Foundation: Project Skeleton, Build Pipeline, and GraalVM Baseline

### Goal

By the end of Phase 1, the project must compile to a GraalVM native binary, start in under 100 ms, respond to `zap --version` and `zap --help`, pass all structural tests, and have every piece of infrastructure (config loading, SQLite schema, platform directories, logging) working at the interface level. No business logic. No actual command filtering. But everything the business logic will plug into is already in place and verified.

### Why This Phase Exists First

The single largest risk in the entire project is GraalVM native image compatibility. Every library you add — Jackson, picocli, sqlite-jdbc, TOML parsers — may use reflection, dynamic proxies, or runtime resource loading that breaks the native build. If you build all 42 filter modules first and then try to compile to native image, you will spend weeks untangling reflection errors with no idea which library introduced which problem. By proving the native build works with each infrastructure dependency individually, you eliminate this risk incrementally. This is the correct engineering order.

### Repository Initialization

The project must be initialized as a Quarkus 3.x application using the Quarkus CLI or Maven archetype. The group ID is `com.zapproxy`, artifact ID is `zap`. The project must use Java 21. The root `pom.xml` must include the `quarkus-bom` import for dependency management, the `quarkus-maven-plugin` for build lifecycle, and the `native-image-maven-plugin` for GraalVM compilation. The directory structure must follow standard Maven layout: `src/main/java`, `src/main/resources`, `src/test/java`, `src/test/resources`.

The `.gitignore` must exclude `target/`, `*.db`, `*.db-journal`, and any GraalVM agent output directories (`native-image-agent-output/`). A `README.md` must be created immediately with build instructions including the GraalVM prerequisite — specifically that `GRAALVM_HOME` must point to GraalVM JDK 21 and `native-image` must be on PATH.

### Core Dependencies

The following dependencies must be declared in `pom.xml` with explicit versions pinned. No floating versions. No `LATEST`. This is non-negotiable for a native image build where dependency upgrades can silently break reflection configs.

**picocli 4.7.x** via `quarkus-picocli` extension — this gives you Quarkus-aware picocli with automatic GraalVM reflection registration for all `@Command` and `@Option` annotated classes. Do not use bare picocli without the Quarkus extension; you will spend hours registering reflection manually.

**sqlite-jdbc 3.45.x (Xerial, bundled mode)** — the `bundled` feature embeds the SQLite native library directly in the JAR and extracts it at runtime. This causes a GraalVM native image problem because native image cannot bundle a JNI library that extracts itself. The solution is to use `sqlite-jdbc` in conjunction with the `org.graalvm.nativeimage.hosted` JNI config, explicitly registering the SQLite JNI classes. This will be addressed in detail in the GraalVM config section below.

**Jackson 2.17.x** for JSON parsing (serde_json equivalent). Use `jackson-databind` and `jackson-dataformat-toml` for TOML config parsing. The Quarkus `quarkus-jackson` extension handles GraalVM reflection registration automatically for Jackson's core serialisation infrastructure.

**SLF4J 2.x + Quarkus logging** — Quarkus uses JBoss Logging under the hood and routes it through SLF4J. No Logback configuration needed; Quarkus handles the logging backend. In native mode, logging is compiled in.

**No other third-party dependencies in Phase 1.** Jansi, jgitignore, and any other libraries are Phase 2+ concerns.

### Package Structure

Every package must be created now as empty directories with `.gitkeep` or skeleton classes. The structure is:

```
com.zapproxy/
├── ZapMain.java              — main() entry point
├── ZapRootCommand.java       — @Command(name = "zap") picocli root
├── VersionProvider.java      — implements IVersionProvider
├── commands/                 — one class per command module (Phase 2+)
├── core/
│   ├── CommandExecutor.java  — Phase 2
│   ├── ExecutionResult.java  — Phase 2
│   ├── FilterStrategy.java   — Phase 2
│   ├── FilterResult.java     — Phase 2
│   ├── StrategyRegistry.java — Phase 2
│   ├── TrackingRepository.java — Phase 1 (schema only)
│   ├── TeeWriter.java        — Phase 2
│   ├── ConfigLoader.java     — Phase 1
│   ├── ZapConfig.java        — Phase 1
│   └── PlatformDirs.java     — Phase 1
├── filters/                  — Phase 2+
├── hooks/                    — Phase 4
└── analytics/                — Phase 4
```

### The Root Command (`ZapRootCommand`)

This is the picocli entry point. It must be annotated with `@Command(name = "zap", mixinStandardHelpOptions = true, versionProvider = VersionProvider.class, subcommandsRepeatable = false)`. The `@CommandLine.Spec` field must be injected so that when no subcommand is provided and no arguments are given, it prints the help text and exits with code 0. If arguments are provided that don't match any subcommand, the root command must treat them as a passthrough command (Phase 2 concern, but the routing hook must exist now).

Two global options must be declared at the root level because they apply to every subcommand:
- `-v / --verbose` as a `boolean[]` array (allows `-v`, `-vv`, `-vvv` by counting array length)
- `-u / --ultra-compact` as a `boolean`

These must be mixed into every subcommand via `@Mixin`.

### Version Provider

The `VersionProvider` class must implement `picocli.CommandLine.IVersionProvider`. The version string `0.1.0` must be read from a `version.properties` file placed at `src/main/resources/com/zapproxy/version.properties` with content `version=0.1.0`. This file must be included in the GraalVM native image resources config (see below). The class must not hardcode the version string in Java source.

### Platform Directories (`PlatformDirs`)

This utility class encapsulates all platform-specific path resolution. It must be a `@ApplicationScoped` Quarkus CDI bean so it can be injected anywhere. It must expose the following methods:

- `getConfigDir()` — returns `Path`. Logic: check `$XDG_CONFIG_HOME/zap` on Linux, `~/Library/Application Support/zap` on macOS, `%APPDATA%\zap` on Windows. Falls back to `~/.config/zap` if XDG var is unset.
- `getDataDir()` — returns `Path`. Logic: check `$XDG_DATA_HOME/zap` on Linux, `~/Library/Application Support/zap` on macOS, `%APPDATA%\zap` on Windows.
- `getConfigFile()` — returns `Path` to `config.toml` inside `getConfigDir()`.
- `getDatabaseFile()` — returns `Path` to `zap.db` inside `getDataDir()`.

All directories must be created on first access using `Files.createDirectories()` with appropriate error handling. Do not throw unchecked exceptions from directory creation — log a warning and return the path even if creation fails (the caller will fail more gracefully when trying to write to it).

### Configuration System (`ZapConfig`, `ConfigLoader`)

`ZapConfig` must be a Java `record` — not a mutable class — because config is read-once at startup. It must have two nested records: `HooksConfig` and `TeeConfig`, matching the TOML structure:

```toml
[hooks]
exclude_commands = ["curl", "playwright"]

[tee]
enabled = true
mode = "failures"   # "failures" | "always" | "never"
```

The `ConfigLoader` must be an `@ApplicationScoped` CDI bean that reads the config file using `jackson-dataformat-toml`. If the config file does not exist, it must return a `ZapConfig` with sensible defaults (empty exclude list, tee enabled in "failures" mode) rather than throwing an exception. The first time Zap runs, there is no config file and it must work correctly.

The `TeeConfig.mode` field must be parsed into an enum `TeeMode { FAILURES, ALWAYS, NEVER }` with case-insensitive parsing. Invalid values in the TOML must log a warning and default to `FAILURES`.

### SQLite Schema (`TrackingRepository`)

The `TrackingRepository` must be an `@ApplicationScoped` CDI bean. It must establish the SQLite connection lazily (on first use, not at startup) using the path from `PlatformDirs.getDatabaseFile()`. On first connection, it must execute the schema migration:

```sql
CREATE TABLE IF NOT EXISTS commands (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    ts         INTEGER NOT NULL,
    command    TEXT NOT NULL,
    project    TEXT,
    cwd        TEXT,
    raw_tokens INTEGER NOT NULL,
    out_tokens INTEGER NOT NULL,
    exec_ms    INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_commands_ts ON commands(ts);
CREATE INDEX IF NOT EXISTS idx_commands_project ON commands(project);
```

In Phase 1, only the `insert()` and `findAll()` method signatures need to exist (they can throw `UnsupportedOperationException` as stubs). The schema creation must be tested. The connection must be closed via a `@PreDestroy` lifecycle hook.

The SQLite JDBC connection string must be `jdbc:sqlite:` followed by the absolute path from `PlatformDirs`. Do not use a relative path — the working directory when Zap is invoked from a hook may be anything.

**GraalVM + sqlite-jdbc note**: The Xerial sqlite-jdbc library extracts a native `.so`/`.dylib`/`.dll` from its JAR at runtime to a temp directory. GraalVM native image cannot support this pattern directly. The solution is to use the `org.xerial:sqlite-jdbc` artifact with the native library bundled for the target platform, and configure the native image build to include the JNI config. The `pom.xml` must include the following native image build argument: `-H:JNIConfigurationFiles=src/main/resources/META-INF/native-image/jni-config.json`. A `jni-config.json` must be generated by running the application with the native image agent (`-agentlib:native-image-agent=config-output-dir=...`) and committed to the repository.

### GraalVM Native Image Configuration

The `src/main/resources/META-INF/native-image/` directory must exist and contain:

- `native-image.properties` — build args: `--no-fallback`, `--initialize-at-build-time=org.slf4j`, `-H:+ReportExceptionStackTraces`
- `resource-config.json` — must include `version.properties` and all `filters/*.toml` files as included resources
- `reflect-config.json` — for any classes that are not auto-registered by Quarkus extensions
- `jni-config.json` — for sqlite-jdbc JNI classes

The `pom.xml` native profile must set `-Dquarkus.native.enabled=true` and pass `-Dquarkus.native.additional-build-args` for any extra flags.

### Logging Strategy

Quarkus logging must be configured in `src/main/resources/application.properties`:

```properties
quarkus.log.level=WARN
quarkus.log.console.enable=true
quarkus.log.console.format=%s%n
quarkus.native.resources.includes=**/*.toml,**/*.properties
```

The `%s%n` format produces clean, unstyled log lines — appropriate for a CLI tool where log output goes to stderr and must not pollute the filtered command output on stdout.

### CI Pipeline

A GitHub Actions workflow must be created at `.github/workflows/build.yml`. It must:
- Trigger on push to `main` and on pull requests
- Run on `ubuntu-latest`
- Install GraalVM 21 via `graalvm/setup-graalvm@v1` action
- Run `mvn verify` (JVM tests)
- Run `mvn package -Pnative` (native image build)
- Upload the native binary as a workflow artifact

This CI check is the Phase 1 exit criterion. The native build must pass in CI, not just on a developer's machine.

### Phase 1 Exit Criteria (all must pass)

1. `mvn verify` passes with zero test failures on JVM
2. `mvn package -Pnative` produces a native binary without fallback (`--no-fallback` must not be violated)
3. `time ./target/zap-runner --version` reports under 100 ms cold start on Linux
4. `./target/zap-runner --help` prints the help text and exits 0
5. `PlatformDirsTest` verifies correct paths on Linux (XDG), macOS, and Windows (mocked via env var override)
6. `ConfigLoaderTest` verifies defaults when no config file exists and correct parsing when it does
7. `TrackingRepositoryTest` verifies schema creation on a temp SQLite file
8. GitHub Actions CI passes with both JVM and native builds

---

## Phase 2 — Command Execution Engine and Filter Architecture

### Goal

By the end of Phase 2, `zap git status` must work correctly end-to-end on a real git repository. The command execution engine must spawn the real `git` binary, capture its output, apply the git status filter, log the token savings to SQLite, and print the filtered result. The full filter strategy framework must be in place so that adding the remaining 41 command filters in Phase 3 is purely mechanical — no architectural decisions left to make.

### Process Execution Engine (`CommandExecutor`)

The `CommandExecutor` must be an `@ApplicationScoped` CDI bean. Its primary method signature is:

```java
public ExecutionResult execute(List<String> args, Duration timeout) throws IOException, InterruptedException
```

Where `ExecutionResult` is a record:

```java
public record ExecutionResult(
    int exitCode,
    String stdout,
    String stderr,
    long durationMs
) {}
```

The implementation must use `ProcessBuilder` with `redirectErrorStream(false)` — stdout and stderr must be captured separately because many filter strategies (`ErrorOnlyFilter`, `GitDiffFilter`) treat them differently.

**The deadlock problem and its solution**: Java's `ProcessBuilder` will deadlock if you read stdout synchronously while the child process is trying to write to stderr (or vice versa) and the OS pipe buffer fills up. The canonical solution is to read stdout and stderr on two separate threads concurrently, joining them after the process exits. With Java 21 virtual threads, this is clean:

```java
var stdoutFuture = Thread.ofVirtual().start(() -> captureStream(process.getInputStream()));
var stderrFuture = Thread.ofVirtual().start(() -> captureStream(process.getErrorStream()));
```

The timeout must be enforced via `process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)`. If the process exceeds the timeout, it must be forcibly destroyed and `ExecutionResult` must be returned with exit code `-1` and a stderr message indicating the timeout.

The `CommandExecutor` must resolve the real binary path using `ProcessBuilder` path lookup — specifically, it must NOT use `which` at runtime (that's a fork overhead). Instead, set `ProcessBuilder` working directory to the current JVM working directory (`Path.of("").toAbsolutePath()`) and let the OS PATH resolution happen natively.

**Infinite loop prevention**: When Zap is installed as a hook, the hook rewrites `git status` to `zap git status`. If Zap then executes `git status` internally, and Zap itself is on PATH as `git` (unlikely but possible in misconfigured environments), this would loop infinitely. The `CommandExecutor` must detect if the resolved binary path is itself (by comparing to `ProcessHandle.current().info().command()`) and throw a descriptive exception rather than forking into infinity.

### Token Counting Utility

Token counting is central to the analytics engine. The implementation must approximate GPT/Claude tokenisation without depending on a tokeniser library (which would add significant native image complexity). The approximation used is: **1 token ≈ 4 characters**. This matches the README's reported token numbers closely enough for analytics purposes.

```java
public final class TokenCounter {
    private TokenCounter() {}
    public static int count(String text) {
        return (text == null || text.isEmpty()) ? 0 : (text.length() + 3) / 4;
    }
}
```

This class must be in `com.zapproxy.core` and must have a unit test verifying the approximation against the token counts in the README (e.g., "git status ~600 tokens filtered" maps to ~2400 chars of output).

### Filter Strategy Interface and Registry

```java
public interface FilterStrategy {
    /**
     * @param command  The full command string (e.g., "git status")
     * @param result   The raw execution result from CommandExecutor
     * @param config   The ZapConfig (for user overrides)
     * @param verbose  Verbosity level (0, 1, 2, 3)
     * @param ultraCompact  Whether -u flag was passed
     * @return FilterResult with compressed output and token metadata
     */
    FilterResult apply(
        String command,
        ExecutionResult result,
        ZapConfig config,
        int verbose,
        boolean ultraCompact
    );
}

public record FilterResult(
    String output,
    int rawTokens,
    int outTokens,
    boolean wasFiltered
) {}
```

The `StrategyRegistry` must be an `@ApplicationScoped` CDI bean. It must maintain a `Map<String, FilterStrategy>` where keys are command name prefixes (e.g., `"git status"`, `"git diff"`, `"git log"`, `"cargo test"`, `"pytest"`). Registration happens via CDI instance injection — every `FilterStrategy` implementation must be a CDI bean annotated with a `@CommandFilter("git status")` qualifier:

```java
@Qualifier
@Retention(RUNTIME)
@Target({TYPE, FIELD, PARAMETER})
public @interface CommandFilter {
    String value();
}
```

The `StrategyRegistry` must inject `Instance<FilterStrategy>` and iterate to build its map at `@PostConstruct`. This is the extensibility mechanism: adding a new filter in Phase 3 means adding a new CDI bean with `@CommandFilter("new command")`. No changes to `StrategyRegistry` itself.

The lookup logic must match the longest prefix. Given `["git", "status"]` as args, it must try `"git status"` first, then `"git"`, then fall back to `PassthroughStrategy`.

The `PassthroughStrategy` must be the fallback for commands with no registered filter. It executes the command, returns the raw output unmodified, and logs `rawTokens == outTokens` to the analytics store (0% savings).

### The First Real Filter: `GitStatusFilter`

This is the proof-of-concept filter that validates the entire architecture works end-to-end. It must be implemented fully in Phase 2 (not deferred to Phase 3) because it is the integration test vehicle.

`GitStatusFilter` must be annotated `@CommandFilter("git status")` and implement `FilterStrategy`. Its logic:

1. Check exit code. Non-zero → pass through stderr, do not filter.
2. Split stdout into lines.
3. Detect the "nothing to commit" case → emit `"✓ clean"`.
4. Count staged files (lines starting with `M`, `A`, `D` in first column), modified files (second column), untracked files (lines starting with `??`).
5. Emit: `"staged: N | modified: N | untracked: N"` plus branch name extracted from the `"On branch ..."` line.
6. If `ultraCompact`, emit a single line with ASCII icons: `"↑S:N M:N ?:N"`.
7. If `verbose >= 2`, include the full list of changed files below the summary.

The branch name must be extracted with a compiled `Pattern`: `Pattern.compile("^On branch (.+)$", Pattern.MULTILINE)`.

### Integration with TrackingRepository

After every filter application, the `CommandExecutor` (or a thin orchestration layer above it) must call `TrackingRepository.insert()` with:
- `ts`: current epoch seconds
- `command`: the command string
- `project`: SHA-256 of `System.getProperty("user.dir")` truncated to 12 hex chars (project fingerprint)
- `cwd`: absolute path of working directory
- `rawTokens` and `outTokens` from `FilterResult`
- `execMs` from `ExecutionResult.durationMs()`

The `TrackingRepository.insert()` method must be fully implemented in Phase 2 (it was a stub in Phase 1).

### Tee Writer (`TeeWriter`)

The `TeeWriter` must be an `@ApplicationScoped` CDI bean. Its `maybeDump()` method checks `TeeConfig.mode` and `ExecutionResult.exitCode()`. If mode is `FAILURES` and exit code is non-zero, or mode is `ALWAYS`, it writes the raw stdout+stderr to a temp file in `PlatformDirs.getDataDir()/tee/`. The filename must be `{command-hash}-{timestamp}.txt` where command-hash is the first 8 chars of SHA-256 of the command string.

The `TeeWriter` must append a line to the filtered output: `"[raw output saved to: /path/to/file]"`. This is how the AI reads the full output without re-executing the command.

### Root Command Passthrough Routing

The `ZapRootCommand` must now be wired to actually dispatch commands. When the user types `zap git status`:

1. picocli matches `"git"` as the first token
2. If a `GitCommand` picocli subcommand exists (Phase 3), it delegates there
3. If no subcommand matches, the root command's `@Parameters(index = "0..*") String[] args` captures all tokens
4. The root command calls `StrategyRegistry.lookup(args)` to get the right filter
5. Calls `CommandExecutor.execute(args)` to run the real command
6. Applies the filter
7. Writes to `TrackingRepository`
8. Calls `TeeWriter.maybeDump()`
9. Prints filtered output to stdout
10. Exits with the original process's exit code

The exit code propagation is critical. If `git status` exits 128 (not a git repo), Zap must exit 128 — not 0. AI tools interpret exit codes to decide if a command succeeded.

### Phase 2 Exit Criteria

1. `zap git status` on a real git repository prints a compressed summary (not raw git output)
2. `zap git status` on a clean repo prints `"✓ clean"`
3. `zap someunknowncommand` passes through to the real binary unchanged
4. After running `zap git status`, `~/.local/share/zap/zap.db` contains one row with non-zero `raw_tokens` and `out_tokens`
5. `zap git status` on a non-git directory exits with the same exit code as `git status` does
6. All unit tests pass, including `GitStatusFilterTest` with fixture inputs from `src/test/resources/fixtures/git-status-clean.txt`, `git-status-modified.txt`, `git-status-untracked.txt`
7. Native image build still passes (no new reflection issues introduced)

---

## Phase 3 — All 42 Command Filter Modules

### Goal

By the end of Phase 3, every command filter documented in the README is implemented, has fixture-based unit tests, and the native image build passes. This is the largest phase by volume of code but the lowest architectural complexity — Phase 2 established the framework, Phase 3 is execution.

### Implementation Order

Filters must be implemented in the following order, grouped by the filtering strategy they exercise. This order ensures that each new filter tests a new strategy, so bugs in the strategy implementation are caught early against simple examples before being relied upon by more complex filters.

**Group A: Stats Extraction Filters** (implement first — simplest output structure)
- `GitDiffFilter` (@CommandFilter("git diff")) — parse `+N/-N lines changed` from diff header
- `GitLogFilter` (@CommandFilter("git log")) — extract commit hash + first line of message per entry
- `GitPushFilter` (@CommandFilter("git push")) — extract branch name from push output, emit "ok {branch}"
- `GitAddFilter` (@CommandFilter("git add")) — extract count of added files from output or return "ok"
- `GitCommitFilter` (@CommandFilter("git commit")) — extract commit hash and message

**Group B: Failure Focus Filters** (test state machine logic)
- `CargoTestFilter` (@CommandFilter("cargo test")) — parse JUnit XML from `target/nextest/` or text output: collect FAILED lines, emit failure count + names only
- `PytestFilter` (@CommandFilter("pytest")) — state machine: HEADER → COLLECTING → RUNNING → SUMMARY; emit only failures + final line
- `GoTestFilter` (@CommandFilter("go test")) — NDJSON streaming: aggregate Action=pass/fail/skip counts from `go test -json` events
- `JestFilter` (@CommandFilter("jest")) / `VitestFilter` (@CommandFilter("vitest")) — invoke with `--json` flag when available; parse JSON result

**Group C: Grouping + Deduplication Filters** (test frequency map logic)
- `ESLintFilter` (@CommandFilter("eslint")) / `ESLintFilter` for `npx eslint` — group by rule name, sort by frequency descending
- `TscFilter` (@CommandFilter("tsc")) — group errors by file, show count per file
- `RuffFilter` (@CommandFilter("ruff check")) — group by rule code
- `CargoClippyFilter` (@CommandFilter("cargo clippy")) — deduplicate warning messages, group by lint name
- `GolangciLintFilter` (@CommandFilter("golangci-lint run")) — group by linter name

**Group D: Tree Compression Filters** (test recursive tree building)
- `LsFilter` (@CommandFilter("ls")) — convert flat file list to indented tree with directory counts
- `TreeFilter` (@CommandFilter("tree")) — strip the raw tree output and re-emit with compression
- `FindFilter` (@CommandFilter("find")) — filter to relevant extensions, emit count + sample

**Group E: JSON Structure Filters**
- `GrepFilter` (@CommandFilter("grep")) / `RgFilter` (@CommandFilter("rg")) — emit match count per file
- `CatFilter` (@CommandFilter("cat")) — if output is JSON: emit structure-only; if text: smart truncation
- `ReadFilter` (@CommandFilter("read")) — alias for CatFilter behavior

**Group F: Progress + ANSI Filters**
- `CargoInstallFilter` (@CommandFilter("cargo install")) — strip progress bars, emit final result line
- `NpmInstallFilter` (@CommandFilter("npm install")) — strip progress, emit "N packages installed"
- `PipInstallFilter` (@CommandFilter("pip install")) — strip download progress, emit summary
- `DockerBuildFilter` (@CommandFilter("docker build")) — strip layer pull progress, emit final image ID

**Group G: Cloud CLI Filters**
- `DockerPsFilter` (@CommandFilter("docker ps")) — compact table: container ID + name + status only
- `KubectlFilter` (@CommandFilter("kubectl")) — varies by subcommand (pods: name+status; logs: tail only)
- `AwsFilter` (@CommandFilter("aws")) — strip response metadata, emit key fields only

**Group H: Remaining Filters**
- `LintFilter` (@CommandFilter("lint")) — ESLint shorthand
- `GoFilter` (@CommandFilter("go test")) — alias routing to GoTestFilter

### Shared Filter Strategy Implementations

Each group above relies on one or more shared strategy classes in `com.zapproxy.filters`. These are not CDI beans — they are pure utility classes called by the command-specific filters above:

**`DeduplicationStrategy`** — takes a `List<String>` of lines, returns a `List<String>` where identical consecutive lines are replaced by the first occurrence + `" (×N)"` suffix. Must handle non-consecutive duplicates within a window of 50 lines (log files often have repeated errors separated by stack traces).

**`GroupingStrategy`** — takes a `List<String>` of lines and a `Pattern` that extracts the group key from each line. Returns a `LinkedHashMap<String, Integer>` of key → count, sorted by count descending. Must handle lines that don't match the pattern (they are either discarded or placed in an "other" bucket depending on a parameter).

**`StateMachineStrategy`** — a general-purpose line-state-machine. Takes a list of `StateTransition` records defining `(fromState, linePattern, toState, action)`. Actions are `EMIT`, `DISCARD`, `COLLECT`. This is what powers `PytestFilter` and similar.

**`JsonStructureStrategy`** — uses Jackson's `JsonNode` to traverse a parsed JSON tree and produce a schema skeleton: replace string values with `"<string>"`, numbers with `0`, booleans with `false`, arrays with `[<type>, ...]`. Recursion must be depth-limited to 5 levels to prevent stack overflow on deeply nested API responses.

**`AnsiStripStrategy`** — a single static method `String strip(String input)` applying `\x1B\[[0-9;]*[mGKHF]` regex to strip all ANSI escape sequences. Must also handle carriage-return-overwritten progress lines (lines ending in `\r` with no following `\n` are discarded as progress updates).

**`TreeCompressionStrategy`** — converts a flat list of file paths into a tree structure. Groups paths by common prefix directories, counts files per directory, emits an indented tree string. For directories with more than 10 files, emits `"  └── (N files)"` instead of listing each one.

### Fixture-Based Testing Approach

Every filter must have a corresponding test class in `src/test/java/com/zapproxy/filters/`. Every test class must load its input from `src/test/resources/fixtures/{command}/input.txt` and assert against `src/test/resources/fixtures/{command}/expected.txt`. This golden-file approach means:

1. Fixtures can be collected from real command runs (`git status`, `cargo test`, etc.)
2. Regressions are caught immediately when filter logic changes
3. New contributors can add test cases by adding fixture files, not writing Java

The `FixtureTestSupport` base class must handle loading both files and provide an `assertFilterOutput()` helper that diffs actual vs expected with a clear error message showing the diff.

For each filter, at least three fixtures must exist:
- `typical/` — normal successful run
- `empty/` — command with no output (e.g., `git status` on clean repo)
- `failure/` — non-zero exit code scenario

### Phase 3 Exit Criteria

1. All 42 filters are implemented with at least 3 fixture tests each (126+ tests total)
2. `mvn verify` passes with zero failures
3. `mvn package -Pnative` passes — native image compiles with all 42 filters included
4. `zap cargo test` on a Rust project with test failures shows only the failure names
5. `zap pytest` on a Python project shows only failures + summary line
6. `zap ls .` shows a compressed tree, not a raw file list
7. Token savings are logged to SQLite for every command
8. `zap docker ps` shows a compact table

---

## Phase 4 — Analytics Engine, Hook Installer, and Configuration Polish

### Goal

By the end of Phase 4, `zap gain` shows real token savings statistics, `zap init -g` installs working hooks into Claude Code and Cursor, and the configuration system is fully complete. The product is feature-complete at this point — Phase 5 is purely about packaging and release.

### Analytics Engine (`GainCommand`)

The `GainCommand` must be a picocli `@Command(name = "gain")` registered as a subcommand of the root. It is the reporting face of the `TrackingRepository`. All queries must be implemented as explicit SQL — no ORM, no JPQL. The queries are simple enough that raw JDBC is cleaner and more native-image friendly.

The following options must be supported, matching the README exactly:

- `--graph` — renders an ASCII bar chart of daily token savings over the last 30 days
- `--history [N]` — shows the last N (default 20) command executions with savings %
- `--scope global|project` — global queries all rows; project filters by the SHA of current working directory
- `--daily` — day-by-day breakdown table
- `--weekly` — week-by-week breakdown table
- `--top N` — top N commands by total tokens saved
- `--since N` — restrict to last N days
- `--format text|json` — text (default) or machine-readable JSON
- `--all` — all-time stats ignoring date filters

The **default output** (no flags) must display:
```
Zap Token Savings (Global Scope)
════════════════════════════════════════════════════════════

Total commands:    127
Input tokens:      48,302
Output tokens:     9,142
Tokens saved:      39,160 (81.1%)
Total exec time:   612ms (avg 4ms)
Efficiency meter: ████████████████████░░░░ 81.1%
```

The `AsciiGraphRenderer` must render bar charts using Unicode block characters (`█`, `▌`, `░`) for the efficiency meter, and standard ASCII `#` characters for the 30-day graph (for maximum terminal compatibility). Each bar in the 30-day graph must represent one day, with the bar height proportional to tokens saved that day relative to the maximum day.

The `--history` output must show rows with leading icons:
- `▲` if savings >= 80%
- `■` if savings 40–79%
- `•` if savings < 40%

The `--format json` output must use Jackson to serialize a `GainReport` record to JSON, with all fields using snake_case naming (configure `MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES` and `PropertyNamingStrategies.SNAKE_CASE`).

### Hook Installer (`HookInstaller`, `HookTemplate`)

The `HookInstaller` must be an `@ApplicationScoped` CDI bean. The `zap init` picocli subcommand must delegate to it.

Hook templates for each supported AI tool must be stored as classpath resources at `src/main/resources/hooks/{tool-name}/hook.sh` (or `hook.json` for tools that use JSON-format hooks). These files must be bundled in the native image via the `resource-config.json` include pattern `hooks/**`.

The supported tools and their hook locations are:

| Tool | Hook Location | Hook Format |
|---|---|---|
| Claude Code | `~/.claude/hooks/` | Shell script |
| Cursor | `~/.cursor/hooks/` | Shell script |
| Gemini CLI | `~/.gemini/hooks/` | Shell script |
| Copilot | VSCode extension config dir | JSON |
| Windsurf | `~/.codeium/windsurf/` | Shell script |
| Cline | VSCode extension config | JSON |

For global install (`zap init -g`), the hook content must wrap every shell command: before execution, if the command matches any registered `zap` command prefix (git, cargo, pytest, etc.) and is not in `config.hooks.exclude_commands`, rewrite it to `zap {original command}`.

The shell hook template for Claude Code must look like:
```bash
#!/usr/bin/env bash
# Installed by: zap init -g
# Do not edit manually — run `zap init -g` to reinstall
ZAP_COMMANDS="git cargo pytest go npm docker kubectl aws ls grep rg find cat"
cmd="${1%% *}"
if echo "$ZAP_COMMANDS" | grep -qw "$cmd"; then
  exec zap "$@"
fi
exec "$@"
```

The `zap init --show` subcommand must detect which hooks are installed by checking for the `# Installed by: zap init` sentinel in existing hook files.

The `zap init --remove` subcommand must delete only Zap-managed hook files (identified by the sentinel), not any other user hook files.

### Configuration System Completion

The configuration system introduced in Phase 1 must be completed:

1. `zap config --set hooks.exclude_commands=curl,playwright` — write a key into the config TOML file
2. `zap config --get tee.mode` — read and print a config key
3. `zap config --list` — print the full config as TOML
4. `zap config --reset` — restore defaults

The TOML serialisation (writing back) must use `jackson-dataformat-toml`'s `ObjectMapper` in write mode. The file must be written atomically: write to a temp file, then `Files.move(..., StandardCopyOption.ATOMIC_MOVE)`.

### Phase 4 Exit Criteria

1. `zap gain` shows correct statistics after running several commands in Phase 2/3 testing
2. `zap gain --graph` renders an ASCII bar chart without errors
3. `zap gain --format json` produces valid JSON parseable by `jq`
4. `zap init -g` installs the Claude Code hook and prints "✓ Installed hook for Claude Code"
5. `zap init --show` correctly reports installed/not-installed status
6. `zap config --list` prints the current config as valid TOML
7. All new code has unit tests; `mvn verify` passes
8. Native image build still passes
9. End-to-end smoke test: install hook → run `git status` via hook → verify `zap gain` shows a new row

---

## Phase 5 — Packaging, Cross-Platform Builds, and Release

### Goal

By the end of Phase 5, GraalVM native binaries exist for Linux x64, Linux aarch64, macOS x64, and macOS aarch64. An `install.sh` script downloads the correct binary for the detected platform. GitHub Releases contain all binaries with SHA-256 checksums. The project README matches the original Zap README's install instructions, adapted for the Java port.

### GraalVM Native Image Final Hardening

Before building release binaries, the native image build must be audited against three criteria:

**No fallback**: The `--no-fallback` flag must be present and the build must not produce warnings about falling back to JVM. If any class cannot be resolved at build time, it must be explicitly excluded or its reflection config must be added.

**Static linking on Linux**: For Linux binaries, use `--static --libc=musl` to produce a fully static binary with no glibc dependency. This ensures the binary runs on Alpine Linux, Ubuntu 18.04, Debian Stretch, and any other Linux distribution. The Quarkus `native` profile must include `-Dquarkus.native.additional-build-args=--static --libc=musl` when building on Linux.

**Startup time verification**: Every release candidate must be benchmarked: `for i in {1..10}; do time ./zap --version; done`. The median must be under 100 ms on Linux (target: <80 ms) and under 150 ms on macOS (target: <120 ms, due to macOS security validation overhead on first run).

### GitHub Actions Release Matrix

The `.github/workflows/release.yml` must define a matrix build triggered on `git tag v*`:

```yaml
strategy:
  matrix:
    include:
      - os: ubuntu-latest
        target: linux-x64
        native_args: "--static --libc=musl"
      - os: ubuntu-24.04-arm64
        target: linux-aarch64
        native_args: "--static --libc=musl"
      - os: macos-14
        target: macos-aarch64
        native_args: ""
      - os: macos-13
        target: macos-x64
        native_args: ""
```

Each matrix job must:
1. Install GraalVM 21 via `graalvm/setup-graalvm@v1`
2. Run `mvn package -Pnative -Dquarkus.native.additional-build-args="${{ matrix.native_args }}"`
3. Rename the binary to `zap-{target}` (e.g., `zap-linux-x64`, `zap-macos-aarch64`)
4. Generate `zap-{target}.sha256` via `sha256sum`
5. Upload both files to the GitHub Release created by the trigger

### Install Script (`install.sh`)

The `install.sh` must detect the OS (`uname -s`) and architecture (`uname -m`) and download the appropriate binary from the GitHub Releases API. It must:

1. Detect platform and map to the correct binary name
2. Download the binary and its `.sha256` file
3. Verify the SHA-256 checksum before installing
4. Place the binary at `~/.local/bin/zap` on Linux, `/usr/local/bin/zap` on macOS (with sudo if needed)
5. Print clear error messages if any step fails
6. Print the version of the installed binary to confirm success

### Package Formats

For Linux, generate `.deb` and `.rpm` packages using `jpackage` (bundled in JDK 21). These packages must place the native binary at `/usr/bin/zap` with mode `755` and install a man page (generated from picocli's `@Command` annotations via `picocli-codegen`).

### Documentation

The `README.md` must be updated to match the original Zap README in structure, with the installation section updated to show:

```
curl -fsSL https://raw.githubusercontent.com/.../install.sh | sh
```

as the primary install path. The "build from source" path must document Java 21 + GraalVM as prerequisites.

A `CONTRIBUTING.md` must explain how to add a new command filter:
1. Create `src/main/java/com/zapproxy/commands/YourCommand.java` implementing `FilterStrategy`
2. Annotate with `@CommandFilter("your command")`
3. Add fixture files in `src/test/resources/fixtures/your-command/`
4. Add a TOML recipe in `src/main/resources/filters/your-command.toml` if using the declarative engine
5. Run `mvn verify` and submit PR

### Phase 5 Exit Criteria

1. All four platform native binaries build successfully in GitHub Actions
2. `install.sh` downloads, verifies, and installs the correct binary on Linux and macOS
3. Installed binary passes `zap --version`, `zap --help`, `zap git status` on each platform
4. `.deb` package installs cleanly on Ubuntu 22.04 and Ubuntu 24.04
5. Binary sizes are under 60 MB on all platforms
6. Cold start times meet targets (<100 ms Linux, <150 ms macOS)
7. All 126+ unit tests pass in CI
8. GitHub Release v0.1.0 is published with all artifacts and checksums

---

# Phase 1 — AI Agent Coding Prompt

> Copy everything below this line and paste it as the first message to your coding agent. It is self-contained. The agent needs no prior context — everything is specified below.

---

## AGENT INSTRUCTIONS: Zap Java Port — Phase 1 Implementation

You are implementing Phase 1 of a Java + GraalVM port of the Rust CLI tool `bitan-del/zap`. Phase 1 covers project scaffolding, build pipeline, GraalVM native image baseline, config system, SQLite schema, and platform directory resolution. No command filtering logic is written in this phase. The goal is a native binary that starts in under 100 ms and responds to `--version` and `--help`.

Read every section of this prompt before writing any code. Do not skip ahead.

---

### Environment Prerequisites

Assume the following are installed and on PATH:
- GraalVM JDK 21 (with `native-image` available). `GRAALVM_HOME` is set.
- Maven 3.9+
- Git

---

### Step 1: Initialize the Quarkus Project

Run the following command to scaffold the project. Do NOT use Spring Boot or plain Maven — Quarkus is required for its native image integration:

```bash
mvn io.quarkus.platform:quarkus-maven-plugin:3.11.0:create \
    -DprojectGroupId=com.zapproxy \
    -DprojectArtifactId=zap \
    -DprojectVersion=0.1.0 \
    -Dextensions="quarkus-picocli,quarkus-jackson" \
    -DnoCode
```

This creates a `zap/` directory. All subsequent work is inside this directory.

---

### Step 2: Add Dependencies to `pom.xml`

Open `zap/pom.xml`. Add the following dependencies inside the `<dependencies>` block. Pin every version exactly as shown — do not use property placeholders for versions at this stage:

```xml
<!-- SQLite -->
<dependency>
  <groupId>org.xerial</groupId>
  <artifactId>sqlite-jdbc</artifactId>
  <version>3.45.3.0</version>
</dependency>

<!-- TOML parsing -->
<dependency>
  <groupId>com.fasterxml.jackson.dataformat</groupId>
  <artifactId>jackson-dataformat-toml</artifactId>
  <version>2.17.1</version>
</dependency>

<!-- Test -->
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-junit5</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.assertj</groupId>
  <artifactId>assertj-core</artifactId>
  <version>3.26.0</version>
  <scope>test</scope>
</dependency>
```

Ensure the native profile in `pom.xml` includes:
```xml
<profile>
  <id>native</id>
  <activation>
    <property>
      <name>native</name>
    </property>
  </activation>
  <properties>
    <quarkus.native.enabled>true</quarkus.native.enabled>
  </properties>
</profile>
```

---

### Step 3: Application Properties

Replace the contents of `src/main/resources/application.properties` with:

```properties
quarkus.log.level=WARN
quarkus.log.console.enable=true
quarkus.log.console.format=%s%n
quarkus.native.resources.includes=**/*.toml,**/*.properties,hooks/**
quarkus.banner.enabled=false
```

---

### Step 4: Version Properties File

Create `src/main/resources/com/zapproxy/version.properties`:

```properties
version=0.1.0
```

---

### Step 5: Create the Package Structure

Create the following empty directories (add a `.gitkeep` in each):

```
src/main/java/com/zapproxy/commands/
src/main/java/com/zapproxy/core/
src/main/java/com/zapproxy/filters/
src/main/java/com/zapproxy/hooks/
src/main/java/com/zapproxy/analytics/
src/main/resources/META-INF/native-image/
src/main/resources/filters/
src/main/resources/hooks/
src/test/resources/fixtures/
```

---

### Step 6: Implement `PlatformDirs.java`

Create `src/main/java/com/zapproxy/core/PlatformDirs.java`:

```java
package com.zapproxy.core;

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@ApplicationScoped
public class PlatformDirs {

    private static final Logger log = LoggerFactory.getLogger(PlatformDirs.class);

    public Path getConfigDir() {
        return ensureDir(resolveConfigDir());
    }

    public Path getDataDir() {
        return ensureDir(resolveDataDir());
    }

    public Path getConfigFile() {
        return getConfigDir().resolve("config.toml");
    }

    public Path getDatabaseFile() {
        return getDataDir().resolve("zap.db");
    }

    private Path resolveConfigDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return Path.of(System.getProperty("user.home"), "Library", "Application Support", "zap");
        }
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return appData != null
                ? Path.of(appData, "zap")
                : Path.of(System.getProperty("user.home"), "AppData", "Roaming", "zap");
        }
        // Linux / Unix: XDG
        String xdgConfig = System.getenv("XDG_CONFIG_HOME");
        return xdgConfig != null && !xdgConfig.isBlank()
            ? Path.of(xdgConfig, "zap")
            : Path.of(System.getProperty("user.home"), ".config", "zap");
    }

    private Path resolveDataDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return Path.of(System.getProperty("user.home"), "Library", "Application Support", "zap");
        }
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return appData != null
                ? Path.of(appData, "zap")
                : Path.of(System.getProperty("user.home"), "AppData", "Roaming", "zap");
        }
        String xdgData = System.getenv("XDG_DATA_HOME");
        return xdgData != null && !xdgData.isBlank()
            ? Path.of(xdgData, "zap")
            : Path.of(System.getProperty("user.home"), ".local", "share", "zap");
    }

    private Path ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("Could not create directory {}: {}", dir, e.getMessage());
        }
        return dir;
    }
}
```

---

### Step 7: Implement `ZapConfig.java` and `ConfigLoader.java`

Create `src/main/java/com/zapproxy/core/ZapConfig.java`:

```java
package com.zapproxy.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ZapConfig(HooksConfig hooks, TeeConfig tee) {

    public static ZapConfig defaults() {
        return new ZapConfig(
            new HooksConfig(List.of()),
            new TeeConfig(true, TeeMode.FAILURES)
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HooksConfig(List<String> excludeCommands) {
        public HooksConfig() { this(List.of()); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TeeConfig(boolean enabled, TeeMode mode) {
        public TeeConfig() { this(true, TeeMode.FAILURES); }
    }
}
```

Create `src/main/java/com/zapproxy/core/TeeMode.java`:

```java
package com.zapproxy.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TeeMode {
    FAILURES, ALWAYS, NEVER;

    @JsonCreator
    public static TeeMode fromString(String value) {
        if (value == null) return FAILURES;
        return switch (value.toUpperCase()) {
            case "ALWAYS" -> ALWAYS;
            case "NEVER" -> NEVER;
            default -> FAILURES;
        };
    }

    @JsonValue
    public String toValue() {
        return name().toLowerCase();
    }
}
```

Create `src/main/java/com/zapproxy/core/ConfigLoader.java`:

```java
package com.zapproxy.core;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@ApplicationScoped
public class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    private static final TomlMapper TOML = new TomlMapper();

    @Inject
    PlatformDirs platformDirs;

    private ZapConfig cached;

    public ZapConfig load() {
        if (cached != null) return cached;
        Path configFile = platformDirs.getConfigFile();
        if (!Files.exists(configFile)) {
            log.debug("No config file at {}; using defaults", configFile);
            cached = ZapConfig.defaults();
            return cached;
        }
        try {
            cached = TOML.readValue(configFile.toFile(), ZapConfig.class);
            return cached;
        } catch (IOException e) {
            log.warn("Failed to parse config at {}: {}; using defaults", configFile, e.getMessage());
            cached = ZapConfig.defaults();
            return cached;
        }
    }
}
```

---

### Step 8: Implement `TrackingRepository.java` (schema + stub methods)

Create `src/main/java/com/zapproxy/core/TrackingRepository.java`:

```java
package com.zapproxy.core;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

@ApplicationScoped
public class TrackingRepository {

    private static final Logger log = LoggerFactory.getLogger(TrackingRepository.class);

    @Inject
    PlatformDirs platformDirs;

    private Connection connection;

    private Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            String url = "jdbc:sqlite:" + platformDirs.getDatabaseFile().toAbsolutePath();
            connection = DriverManager.getConnection(url);
            initSchema(connection);
        }
        return connection;
    }

    private void initSchema(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS commands (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts         INTEGER NOT NULL,
                    command    TEXT NOT NULL,
                    project    TEXT,
                    cwd        TEXT,
                    raw_tokens INTEGER NOT NULL,
                    out_tokens INTEGER NOT NULL,
                    exec_ms    INTEGER NOT NULL
                )
                """);
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_commands_ts ON commands(ts)");
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_commands_project ON commands(project)");
        }
    }

    /**
     * Insert a command record. Fails silently on error — analytics must never
     * break the primary proxy functionality.
     */
    public void insert(String command, String project, String cwd,
                       int rawTokens, int outTokens, long execMs) {
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO commands(ts, command, project, cwd, raw_tokens, out_tokens, exec_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
                ps.setLong(1, System.currentTimeMillis() / 1000);
                ps.setString(2, command);
                ps.setString(3, project);
                ps.setString(4, cwd);
                ps.setInt(5, rawTokens);
                ps.setInt(6, outTokens);
                ps.setLong(7, execMs);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("Failed to record command analytics: {}", e.getMessage());
        }
    }

    public long countAll() {
        try {
            try (var stmt = getConnection().createStatement();
                 var rs = stmt.executeQuery("SELECT COUNT(*) FROM commands")) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            log.warn("Failed to query command count: {}", e.getMessage());
            return 0;
        }
    }

    @PreDestroy
    void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("Failed to close SQLite connection: {}", e.getMessage());
            }
        }
    }
}
```

---

### Step 9: Implement `VersionProvider.java`

Create `src/main/java/com/zapproxy/VersionProvider.java`:

```java
package com.zapproxy;

import picocli.CommandLine.IVersionProvider;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class VersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() throws Exception {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/com/zapproxy/version.properties")) {
            if (in == null) return new String[]{"zap unknown"};
            props.load(in);
        } catch (IOException e) {
            return new String[]{"zap unknown"};
        }
        return new String[]{"zap " + props.getProperty("version", "unknown")};
    }
}
```

---

### Step 10: Implement `ZapRootCommand.java`

Create `src/main/java/com/zapproxy/ZapRootCommand.java`:

```java
package com.zapproxy;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "zap",
    mixinStandardHelpOptions = true,
    versionProvider = VersionProvider.class,
    description = "High-performance CLI proxy that filters command output to save 60-90%% AI tokens.",
    footer = "%nRun 'zap --help' for usage. Run 'zap gain' to see token savings statistics."
)
public class ZapRootCommand implements Runnable {

    @Spec
    CommandSpec spec;

    @Option(names = {"-v", "--verbose"}, description = "Increase verbosity (-v, -vv, -vvv)")
    boolean[] verbose = new boolean[0];

    @Option(names = {"-u", "--ultra-compact"}, description = "Maximum compression mode")
    boolean ultraCompact;

    @Override
    public void run() {
        // When invoked with no subcommand and no args, print help.
        spec.commandLine().usage(System.out);
    }

    public int verbosityLevel() {
        return verbose == null ? 0 : verbose.length;
    }
}
```

---

### Step 11: Implement `ZapMain.java`

Create `src/main/java/com/zapproxy/ZapMain.java`:

```java
package com.zapproxy;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import picocli.CommandLine;

@QuarkusMain
public class ZapMain implements QuarkusApplication {

    @Inject
    CommandLine.IFactory factory;

    @Override
    public int run(String... args) {
        return new CommandLine(new ZapRootCommand(), factory)
            .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                cmd.getErr().println("Error: " + ex.getMessage());
                return 1;
            })
            .execute(args);
    }
}
```

---

### Step 12: GraalVM Native Image Configuration Files

Create `src/main/resources/META-INF/native-image/native-image.properties`:

```properties
Args = --no-fallback \
       -H:+ReportExceptionStackTraces \
       --initialize-at-build-time=org.slf4j \
       -H:IncludeResources=com/zapproxy/version.properties \
       -H:IncludeResources=filters/.* \
       -H:IncludeResources=hooks/.*
```

Create `src/main/resources/META-INF/native-image/resource-config.json`:

```json
{
  "resources": {
    "includes": [
      { "pattern": "com/zapproxy/version.properties" },
      { "pattern": "filters/.*\\.toml" },
      { "pattern": "hooks/.*" },
      { "pattern": "application.properties" }
    ]
  }
}
```

Create `src/main/resources/META-INF/native-image/reflect-config.json`:

```json
[
  {
    "name": "org.sqlite.JDBC",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "org.sqlite.core.DB",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "org.sqlite.core.NativeDB",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.zapproxy.core.ZapConfig",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.zapproxy.core.ZapConfig$HooksConfig",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.zapproxy.core.ZapConfig$TeeConfig",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.zapproxy.core.TeeMode",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  }
]
```

---

### Step 13: Write Unit Tests

Create `src/test/java/com/zapproxy/core/PlatformDirsTest.java`:

```java
package com.zapproxy.core;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class PlatformDirsTest {

    @Inject
    PlatformDirs platformDirs;

    @Test
    void configDirIsCreatedOnAccess() {
        var dir = platformDirs.getConfigDir();
        assertThat(dir).isNotNull();
        assertThat(Files.isDirectory(dir)).isTrue();
    }

    @Test
    void dataDirIsCreatedOnAccess() {
        var dir = platformDirs.getDataDir();
        assertThat(dir).isNotNull();
        assertThat(Files.isDirectory(dir)).isTrue();
    }

    @Test
    void configFilePathHasCorrectName() {
        assertThat(platformDirs.getConfigFile().getFileName().toString())
            .isEqualTo("config.toml");
    }

    @Test
    void databaseFilePathHasCorrectName() {
        assertThat(platformDirs.getDatabaseFile().getFileName().toString())
            .isEqualTo("zap.db");
    }
}
```

Create `src/test/java/com/zapproxy/core/ConfigLoaderTest.java`:

```java
package com.zapproxy.core;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ConfigLoaderTest {

    @Inject
    ConfigLoader configLoader;

    @Test
    void returnsDefaultsWhenNoConfigFileExists() {
        // In the test environment, no config file should exist
        var config = configLoader.load();
        assertThat(config).isNotNull();
        assertThat(config.tee().enabled()).isTrue();
        assertThat(config.tee().mode()).isEqualTo(TeeMode.FAILURES);
        assertThat(config.hooks().excludeCommands()).isNotNull();
    }
}
```

Create `src/test/java/com/zapproxy/core/TrackingRepositoryTest.java`:

```java
package com.zapproxy.core;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class TrackingRepositoryTest {

    @Inject
    TrackingRepository repo;

    @Test
    void schemaCreatesSuccessfully() {
        // countAll() will trigger schema creation
        long count = repo.countAll();
        assertThat(count).isGreaterThanOrEqualTo(0);
    }

    @Test
    void insertAndCountRow() {
        long before = repo.countAll();
        repo.insert("git status", "proj123", "/tmp/test", 500, 100, 45);
        long after = repo.countAll();
        assertThat(after).isEqualTo(before + 1);
    }
}
```

Create `src/test/java/com/zapproxy/VersionProviderTest.java`:

```java
package com.zapproxy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionProviderTest {

    @Test
    void versionStringStartsWithZap() throws Exception {
        var provider = new VersionProvider();
        var version = provider.getVersion();
        assertThat(version).hasSize(1);
        assertThat(version[0]).startsWith("zap ");
        assertThat(version[0]).contains("0.1.0");
    }
}
```

---

### Step 14: Add `.gitignore`

Create `.gitignore` at the project root:

```
target/
*.db
*.db-journal
native-image-agent-output/
.idea/
*.iml
.DS_Store
```

---

### Step 15: Create `README.md`

Create `README.md`:

```markdown
# Zap (Java + GraalVM Port)

A Java + GraalVM Native Image port of [bitan-del/zap](https://github.com/bitan-del/zap).

## Prerequisites

- GraalVM JDK 21 with `native-image` on PATH
- Maven 3.9+

## Build

### JVM mode (for development)
\`\`\`
mvn verify
\`\`\`

### Native binary
\`\`\`
mvn package -Pnative
./target/zap-runner --version
\`\`\`

## Usage

\`\`\`
zap --help
zap --version
\`\`\`
```

---

### Step 16: Verify the Build

Run the following commands in order. All must succeed:

```bash
# 1. JVM build and tests
mvn verify

# 2. Confirm test count (should show PlatformDirsTest, ConfigLoaderTest,
#    TrackingRepositoryTest, VersionProviderTest all passing)
mvn test | grep -E "Tests run|FAILED|ERROR"

# 3. Native image build
mvn package -Pnative

# 4. Smoke tests on native binary
./target/zap-runner --version
./target/zap-runner --help

# 5. Startup time benchmark
for i in {1..5}; do time ./target/zap-runner --version; done
```

**The Phase 1 implementation is complete when:**
- `mvn verify` shows 0 failures
- `mvn package -Pnative` produces `target/zap-runner` with no fallback warning
- `./target/zap-runner --version` prints `zap 0.1.0`
- `./target/zap-runner --help` prints the command description
- Median startup time from the benchmark loop is under 100 ms

---

### Troubleshooting Guide

**`native-image` build fails with "could not find method"**
Run `mvn package -Pnative -Dquarkus.native.additional-build-args=--verbose` to see which class is failing. Add the missing class to `reflect-config.json`.

**SQLite `UnsatisfiedLinkError` in native binary**
The sqlite-jdbc native library extraction fails at runtime. Add `org.sqlite.core.NativeDB` and all `org.sqlite.core.*` classes to `reflect-config.json`. Also add a JNI config entry for the native library method signatures. Run the agent to regenerate configs: `java -agentlib:native-image-agent=config-output-dir=native-agent-output -jar target/quarkus-app/quarkus-run.jar --version` and merge the generated `jni-config.json` into `src/main/resources/META-INF/native-image/`.

**Startup time over 100 ms**
Check if any `@ApplicationScoped` bean is doing I/O in its constructor or `@PostConstruct`. Move expensive operations to lazy init (initialize on first method call, not on CDI activation). The `TrackingRepository` already does this — apply the same pattern to `ConfigLoader`.

**`VersionProvider` returns "unknown" in native binary**
The `version.properties` file is not being included in the native image. Verify `resource-config.json` lists `com/zapproxy/version.properties` and that the file exists at that exact classpath path.

---

*Phase 1 complete. Proceed to Phase 2: Command Execution Engine and GitStatusFilter.*
