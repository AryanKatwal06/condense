# Zap Java Port — Phase 2 Audit, Fix & Polish Prompt

> You are a senior Java engineer conducting the mandatory pre-Phase-3 audit of the
> Zap Java + GraalVM port. Your job is twofold: (1) audit every Phase 2 deliverable
> against its specification, and (2) implement everything that is missing, incorrect,
> incomplete, or substandard — so the repository scores 10/10 on every criterion
> before Phase 3 begins. Work through this document top to bottom, in order.
> Do not jump ahead to Phase 3 logic. Do not skip sections.

---

## What Phase 2 Was Required to Produce

Phase 2 must have added the following to a passing Phase 1 baseline:

**11 new source files**
```
src/main/java/com/zapproxy/annotation/CommandFilter.java
src/main/java/com/zapproxy/annotation/CommandFilters.java
src/main/java/com/zapproxy/core/ExecutionResult.java
src/main/java/com/zapproxy/core/FilterResult.java
src/main/java/com/zapproxy/core/FilterStrategy.java
src/main/java/com/zapproxy/core/CommandExecutor.java
src/main/java/com/zapproxy/core/TokenCounter.java
src/main/java/com/zapproxy/core/ProjectFingerprint.java
src/main/java/com/zapproxy/core/TeeWriter.java
src/main/java/com/zapproxy/core/StrategyRegistry.java
src/main/java/com/zapproxy/core/PassthroughStrategy.java
src/main/java/com/zapproxy/filter/git/GitStatusFilter.java
```

**2 modified source files**
```
src/main/java/com/zapproxy/ZapRootCommand.java     (passthrough dispatch added)
src/main/java/com/zapproxy/ZapMain.java            (exit code propagation added)
src/main/resources/META-INF/native-image/reflect-config.json  (new entries added)
```

**6 new test files**
```
src/test/java/com/zapproxy/core/CommandExecutorTest.java
src/test/java/com/zapproxy/core/TokenCounterTest.java
src/test/java/com/zapproxy/core/ProjectFingerprintTest.java
src/test/java/com/zapproxy/core/TeeWriterTest.java
src/test/java/com/zapproxy/core/StrategyRegistryTest.java
src/test/java/com/zapproxy/filter/git/GitStatusFilterTest.java
```

**6 new fixture files**
```
src/test/resources/fixtures/git-status/clean.txt
src/test/resources/fixtures/git-status/modified.txt
src/test/resources/fixtures/git-status/staged.txt
src/test/resources/fixtures/git-status/untracked.txt
src/test/resources/fixtures/git-status/mixed.txt
src/test/resources/fixtures/git-status/detached-head.txt
```

**End-to-end behaviour that must work**
- `zap git status` on a real repo prints a compact summary, not raw git output
- `zap git status` on a clean repo prints `[branch] ✓ clean`
- `zap -u git status` prints icon-format single line
- `zap -vv git status` prints summary + file list
- `zap echo hello` passes through unchanged
- Non-zero exit codes are propagated to the OS exactly
- Every `zap git status` call inserts a row into SQLite
- Failed commands write a tee file (default config: mode=failures)
- Native image compiles without fallback and starts in under 100ms

---

## PRE-AUDIT: Baseline Check

Run these commands first. If any fail, fix Phase 1 before auditing Phase 2.

```bash
# Phase 1 baseline must still be green
mvn verify 2>&1 | tail -5
./target/zap-runner --version 2>/dev/null || echo "BINARY MISSING — rebuild with: mvn package -Pnative -DskipTests"
```

If `mvn verify` fails and the failures are in Phase 1 test classes
(`PlatformDirsTest`, `ConfigLoaderTest`, `TrackingRepositoryTest`,
`VersionProviderTest`, `TeeModeTest`), fix those first. Phase 2 cannot pass if
Phase 1 regressed.

---

## SECTION 1 — Annotation Package

### 1.1 `CommandFilter.java`

Check the file exists at
`src/main/java/com/zapproxy/annotation/CommandFilter.java`.

Verify ALL of the following. Fix anything that is not true:

- Package declaration is `package com.zapproxy.annotation;`
- Annotated with `@jakarta.inject.Qualifier`
- Annotated with `@Retention(RetentionPolicy.RUNTIME)` — RUNTIME is mandatory; CLASS
  or SOURCE will cause CDI discovery to fail silently
- Annotated with `@Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})`
- Contains `String value();` as the only member
- Has a complete Javadoc comment explaining its purpose and usage example

If missing or incorrect, write the correct implementation:

```java
package com.zapproxy.annotation;

import jakarta.inject.Qualifier;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CDI qualifier that registers a {@link com.zapproxy.core.FilterStrategy}
 * implementation for one or more shell command prefixes.
 *
 * <p>Annotate any {@code FilterStrategy} CDI bean with this qualifier to register
 * it in {@link com.zapproxy.core.StrategyRegistry}:
 *
 * <pre>{@code
 * @CommandFilter("git status")
 * @ApplicationScoped
 * public class GitStatusFilter implements FilterStrategy { ... }
 * }</pre>
 *
 * <p>The {@code value} must be the command prefix in lowercase, space-separated
 * (e.g. {@code "git status"}, {@code "cargo test"}, {@code "pytest"}).
 * The registry performs longest-prefix matching, so {@code "git status"} takes
 * precedence over {@code "git"} when the full command is {@code "git status --short"}.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
public @interface CommandFilter {
    /** The command prefix this strategy handles (lowercase, space-separated). */
    String value();
}
```

### 1.2 `CommandFilters.java`

Check the file exists at
`src/main/java/com/zapproxy/annotation/CommandFilters.java`.

This is the repeatable-annotation container. Verify:
- Same `@Retention(RetentionPolicy.RUNTIME)` and `@Target` as `CommandFilter`
- Contains `CommandFilter[] value();`

If missing:

```java
package com.zapproxy.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container annotation enabling {@link CommandFilter} to be repeated on a single type.
 *
 * <p>Used when a single {@link com.zapproxy.core.FilterStrategy} implementation
 * handles multiple command prefixes:
 *
 * <pre>{@code
 * @CommandFilter("npm install")
 * @CommandFilter("npm ci")
 * @ApplicationScoped
 * public class NpmInstallFilter implements FilterStrategy { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
public @interface CommandFilters {
    CommandFilter[] value();
}
```

---

## SECTION 2 — Core Data Records

### 2.1 `ExecutionResult.java`

Verify the file exists and contains ALL of the following:

- `package com.zapproxy.core;`
- Declared as `public record ExecutionResult(int exitCode, String stdout, String stderr, long durationMs)`
- Method `public boolean succeeded()` — returns `exitCode == 0`
- Method `public boolean hasStderr()` — returns `stderr != null && !stderr.isBlank()`
- Method `public String combined()` — returns stdout if stderr blank, stderr if stdout
  blank, `stdout + "\n" + stderr` otherwise
- Full Javadoc on the record and each component

If the file is missing or any method is absent, write the full correct version:

```java
package com.zapproxy.core;

/**
 * The raw result of executing a shell command via {@link CommandExecutor}.
 *
 * <p>Both {@code stdout} and {@code stderr} are always non-null — they are
 * empty strings when the process produced no output on that stream.
 *
 * @param exitCode   the process exit code; {@code -1} if the process was
 *                   killed due to timeout
 * @param stdout     captured standard output (UTF-8), never null
 * @param stderr     captured standard error (UTF-8), never null
 * @param durationMs wall-clock time from process start to exit, in milliseconds
 */
public record ExecutionResult(
    int exitCode,
    String stdout,
    String stderr,
    long durationMs
) {

    /** Returns {@code true} if the command exited with code 0. */
    public boolean succeeded() {
        return exitCode == 0;
    }

    /** Returns {@code true} if stderr contains any non-whitespace content. */
    public boolean hasStderr() {
        return stderr != null && !stderr.isBlank();
    }

    /**
     * Returns the combined stdout and stderr, suitable for passthrough output
     * and tee file content.
     *
     * <p>If only one stream has content, returns that stream alone (no trailing
     * newline added). If both have content, they are joined with a single newline.
     */
    public String combined() {
        boolean hasOut = stdout != null && !stdout.isBlank();
        boolean hasErr = stderr != null && !stderr.isBlank();
        if (!hasOut) return hasErr ? stderr : "";
        if (!hasErr) return stdout;
        return stdout + "\n" + stderr;
    }
}
```

### 2.2 `FilterResult.java`

Verify the file exists and contains ALL of the following:

- Declared as `public record FilterResult(String output, int rawTokens, int outTokens, boolean wasFiltered)`
- Method `public int savingsPct()` — returns `0` when `rawTokens == 0`,
  otherwise `(int)(100L * (rawTokens - outTokens) / rawTokens)`
- Static factory `public static FilterResult passthrough(String output)` —
  sets `rawTokens == outTokens`, `wasFiltered == false`
- Static factory `public static FilterResult of(String rawInput, String filteredOutput)` —
  computes both token counts via `TokenCounter.count()`, `wasFiltered == true`
- Full Javadoc

If missing or incorrect:

```java
package com.zapproxy.core;

/**
 * The output produced by a {@link FilterStrategy} after compressing a command's
 * raw output.
 *
 * <p>Use the static factories rather than the record constructor directly:
 * <ul>
 *   <li>{@link #passthrough(String)} — no compression applied</li>
 *   <li>{@link #of(String, String)} — compression applied, both sides provided</li>
 * </ul>
 *
 * @param output      the compressed output string to print to stdout; never null
 * @param rawTokens   estimated token count of the original command output
 * @param outTokens   estimated token count of {@code output}
 * @param wasFiltered {@code true} if any compression was applied
 */
public record FilterResult(
    String output,
    int rawTokens,
    int outTokens,
    boolean wasFiltered
) {

    /**
     * Returns the percentage of tokens saved by filtering, 0–100.
     * Returns 0 when {@code rawTokens} is 0 (nothing to compress).
     */
    public int savingsPct() {
        if (rawTokens == 0) return 0;
        return (int) (100L * (rawTokens - outTokens) / rawTokens);
    }

    /**
     * Creates a {@code FilterResult} representing no compression.
     * {@code rawTokens == outTokens} and {@code wasFiltered == false}.
     *
     * <p>Use this as the safe fallback in every {@link FilterStrategy} when
     * parsing fails or exit code is non-zero.
     *
     * @param output the raw output to pass through; must not be null
     */
    public static FilterResult passthrough(String output) {
        String safe = output == null ? "" : output;
        int tokens = TokenCounter.count(safe);
        return new FilterResult(safe, tokens, tokens, false);
    }

    /**
     * Creates a {@code FilterResult} where compression was applied.
     * Token counts are computed automatically.
     *
     * @param rawInput       the original command output before filtering
     * @param filteredOutput the compressed result to emit
     */
    public static FilterResult of(String rawInput, String filteredOutput) {
        String safeRaw = rawInput == null ? "" : rawInput;
        String safeOut = filteredOutput == null ? "" : filteredOutput;
        return new FilterResult(
            safeOut,
            TokenCounter.count(safeRaw),
            TokenCounter.count(safeOut),
            true
        );
    }
}
```

---

## SECTION 3 — Utility Classes

### 3.1 `TokenCounter.java`

Verify:
- `public final class` with `private TokenCounter() {}`
- `public static int count(String text)` returns `0` for null/empty,
  `(text.length() + 3) / 4` otherwise
- `public static int savingsPct(String raw, String filtered)` returns `0` when
  raw token count is 0, otherwise correct percentage
- Full Javadoc explaining the 1-token-≈-4-chars approximation and the rationale
  for not using a real tokeniser

If missing or incorrect:

```java
package com.zapproxy.core;

/**
 * Approximates GPT/Claude tokenisation for analytics purposes.
 *
 * <p><strong>Approximation:</strong> 1 token ≈ 4 characters (integer ceiling
 * division). This is close enough to OpenAI's cl100k_base and Anthropic's
 * tokeniser for the purposes of reporting percentage savings in {@code zap gain}.
 * A proper tokeniser library would add 5–15 MB to the native binary and significant
 * native-image complexity, with only marginal accuracy improvement for our use case.
 *
 * <p>This class is stateless and cannot be instantiated.
 */
public final class TokenCounter {

    private TokenCounter() {}

    /**
     * Estimates the number of tokens in {@code text}.
     *
     * @param text the text to count; {@code null} and empty strings return 0
     * @return estimated token count ≥ 0
     */
    public static int count(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (text.length() + 3) / 4;
    }

    /**
     * Estimates the percentage of tokens saved when {@code raw} was compressed
     * to {@code filtered}.
     *
     * @param raw      the original text before filtering
     * @param filtered the compressed text after filtering
     * @return integer percentage in [0, 100]; 0 if raw is empty
     */
    public static int savingsPct(String raw, String filtered) {
        int rawTokens = count(raw);
        if (rawTokens == 0) return 0;
        int outTokens = count(filtered);
        return (int) (100L * (rawTokens - outTokens) / rawTokens);
    }
}
```

### 3.2 `ProjectFingerprint.java`

Verify:
- `public final class` with `private` constructor
- `public static String of(String absolutePath)` returns `"000000000000"` for
  null/blank input; otherwise returns 12 lowercase hex chars (first 6 bytes of
  SHA-256 as 12 hex digits)
- `public static String ofCurrentDir()` delegates to `of(System.getProperty("user.dir"))`
- SHA-256 `NoSuchAlgorithmException` is re-thrown as `IllegalStateException`
- Full Javadoc

If missing or incorrect:

```java
package com.zapproxy.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Generates a stable 12-character hex fingerprint for a filesystem path.
 *
 * <p>The fingerprint is the first 12 lowercase hex digits of the SHA-256 hash of
 * the path string. It is used by the analytics engine to identify which project
 * directory a command ran in, without storing the full path in the database.
 *
 * <p>Example: {@code "/home/user/my-project"} → {@code "a3f9d2c1b407"}
 *
 * <p>This class is stateless and cannot be instantiated.
 */
public final class ProjectFingerprint {

    private static final String ZERO = "000000000000";

    private ProjectFingerprint() {}

    /**
     * Returns a 12-character hex fingerprint of {@code absolutePath}.
     *
     * @param absolutePath the path to fingerprint; null/blank returns
     *                     {@code "000000000000"}
     * @return 12 lowercase hex characters, always exactly 12 chars long
     */
    public static String of(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank()) return ZERO;
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(
                absolutePath.getBytes(StandardCharsets.UTF_8));
            // First 6 bytes → 12 hex characters
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", hash[i] & 0xFF));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the Java spec to be available
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Returns a fingerprint for the current JVM working directory
     * ({@code System.getProperty("user.dir")}).
     */
    public static String ofCurrentDir() {
        return of(System.getProperty("user.dir"));
    }
}
```

---

## SECTION 4 — Filter Framework Interfaces

### 4.1 `FilterStrategy.java`

Verify:
- `public interface FilterStrategy` (not abstract class, not annotation)
- Single method `FilterResult apply(String command, ExecutionResult result,
  ZapConfig config, int verbose, boolean ultraCompact)`
- Full Javadoc on the interface AND on every parameter
- Javadoc explicitly documents the three contracts: (1) non-zero exit → passthrough
  unless the filter specifically handles failures; (2) never throw; (3) never modify
  exit code

If missing or incorrect:

```java
package com.zapproxy.core;

/**
 * Contract for all command output filter implementations.
 *
 * <p>Each implementation is responsible for one or more shell commands and
 * compresses their raw output into a dense summary suitable for an AI context
 * window. Implementations are CDI {@code @ApplicationScoped} beans annotated
 * with {@link com.zapproxy.annotation.CommandFilter}.
 *
 * <h2>Implementation contracts</h2>
 * <ol>
 *   <li><strong>Non-fatal on non-zero exit:</strong> If {@code result.exitCode()}
 *       is non-zero and the command genuinely failed (e.g., {@code git status}
 *       returning 128), return the raw stderr via
 *       {@link FilterResult#passthrough(String)}. Exception: test runners
 *       ({@code cargo test}, {@code pytest}) where failure output IS the
 *       interesting content — those filters always process the output.</li>
 *   <li><strong>Never throw:</strong> Catch all parsing exceptions. Log a
 *       {@code WARN} and return {@link FilterResult#passthrough(String)} as the
 *       safe fallback. A broken filter must never break the proxied command.</li>
 *   <li><strong>Never modify exit code:</strong> The caller in
 *       {@link com.zapproxy.ZapRootCommand} propagates the original exit code
 *       from {@link ExecutionResult#exitCode()} to the OS. Filters have no
 *       mechanism to change it, and must not attempt to.</li>
 *   <li><strong>Stateless:</strong> A single instance is used for all invocations.
 *       All state must be local to this method.</li>
 * </ol>
 */
public interface FilterStrategy {

    /**
     * Applies this filter to the raw command output and returns a compressed result.
     *
     * @param command      the full command string (e.g. {@code "git status"}),
     *                     used for logging and tee file naming
     * @param result       the raw captured output from {@link CommandExecutor};
     *                     never null
     * @param config       the user's {@link ZapConfig}; never null
     * @param verbose      verbosity level: 0 = most compact, 1 = normal summary,
     *                     2 = summary + file list, 3 = maximum (show everything)
     * @param ultraCompact if {@code true}, emit the absolute minimum — a single
     *                     line with ASCII icons; overrides {@code verbose}
     * @return the filter result; never null
     */
    FilterResult apply(
        String command,
        ExecutionResult result,
        ZapConfig config,
        int verbose,
        boolean ultraCompact
    );
}
```

---

## SECTION 5 — Strategy Framework Classes

### 5.1 `PassthroughStrategy.java`

Verify:
- `@ApplicationScoped` annotation present
- Implements `FilterStrategy`
- Does NOT have `@CommandFilter` annotation (it is the fallback, not a registered handler)
- Uses `result.stdout()` when exit is 0, `result.combined()` when exit is non-zero
- Returns `FilterResult.passthrough(output)` — not `FilterResult.of(...)`
- Full Javadoc

If missing or any contract violated, write the correct version:

```java
package com.zapproxy.core;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Default {@link FilterStrategy} used when no registered filter matches the command.
 *
 * <p>Returns the raw command output unchanged. Records
 * {@code rawTokens == outTokens} in the analytics database (0% savings), which
 * accurately reflects that no compression was applied.
 *
 * <p>This class must NOT be annotated with
 * {@link com.zapproxy.annotation.CommandFilter}. It is injected directly into
 * {@link StrategyRegistry} as the explicit fallback, not discovered via CDI
 * qualifier scanning.
 */
@ApplicationScoped
public class PassthroughStrategy implements FilterStrategy {

    private static final Logger log = Logger.getLogger(PassthroughStrategy.class);

    @Override
    public FilterResult apply(
            String command,
            ExecutionResult result,
            ZapConfig config,
            int verbose,
            boolean ultraCompact) {

        log.debugf("No filter registered for '%s' — passing through", command);

        // When exit is non-zero, stderr is the important content.
        // When exit is 0, use stdout only (stderr may contain benign warnings).
        String output = result.succeeded() ? result.stdout() : result.combined();

        return FilterResult.passthrough(output);
    }
}
```

### 5.2 `StrategyRegistry.java`

This is the most likely class to have implementation bugs. Verify every detail:

**Check 1: Class-level annotations**
- `@ApplicationScoped` is present
- NOT `@Singleton` (causes proxy issues with picocli)

**Check 2: CDI injection**
- `@Inject @Any Instance<FilterStrategy> strategies` is present
- `@Inject PassthroughStrategy passthrough` is present as a separate field
- The `strategies` instance EXCLUDES `PassthroughStrategy` from iteration
  (otherwise passthrough gets registered as a filter for the empty key `""`)

**Check 3: `@PostConstruct build()` method**
- Iterates over `strategies`
- For each strategy, unwraps CDI proxy to get the actual class:
  checks if class name contains `"_Subclass"` or `"$Proxy"` and calls `.getSuperclass()`
- Gets `@CommandFilter` annotations via `cls.getAnnotationsByType(CommandFilter.class)`
- Registers the strategy under the `annotation.value().trim().toLowerCase()` key
- Logs at DEBUG each registered key
- Logs at INFO the final count: `"StrategyRegistry: N filter(s) registered"`

**Check 4: `lookup(String[] args)` method**
- Returns `passthrough` immediately for null/empty args
- Tries prefixes from longest to shortest:
  args=["git","status","--short"] → tries "git status --short", "git status", "git"
- Returns `passthrough` if no prefix matches
- Logs at DEBUG which key matched and which class will handle it

**Check 5: `hasFilter(String[] args)` method**
- Returns `lookup(args) != passthrough`

**Check 6: `registeredCommands()` method**
- Returns `registry.keySet().stream().sorted().toList()`

**Known CDI proxy problem and fix**: In Quarkus, CDI beans are wrapped in a
generated proxy class whose name ends in `_Subclass`. When you call
`strategy.getClass()`, you get the proxy class, not the annotated class. The proxy
class does NOT have `@CommandFilter` on it — only the real class does. Inspect the
class name and call `.getSuperclass()` if the name contains `_Subclass` or `$Proxy`.

**Alternative fix that is more robust** — use `Instance.handles()`:

```java
@PostConstruct
void build() {
    for (var handle : strategies.handles()) {
        // getBeanClass() always returns the actual implementation class,
        // regardless of CDI proxying
        Class<?> cls = handle.getBean().getBeanClass();

        // Skip PassthroughStrategy — it is not a registered handler
        if (cls == PassthroughStrategy.class) continue;

        CommandFilter[] annotations = cls.getAnnotationsByType(CommandFilter.class);
        if (annotations.length == 0) continue;

        FilterStrategy instance = handle.get();
        for (CommandFilter annotation : annotations) {
            String key = annotation.value().trim().toLowerCase();
            registry.put(key, instance);
            log.debugf("Registered '%s' → %s", key, cls.getSimpleName());
        }
    }
    log.infof("StrategyRegistry: %d filter(s) registered", registry.size());
}
```

Write the complete correct `StrategyRegistry.java`:

```java
package com.zapproxy.core;

import com.zapproxy.annotation.CommandFilter;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolves the correct {@link FilterStrategy} for a given shell command.
 *
 * <p>At CDI startup ({@link PostConstruct}), all {@code FilterStrategy} beans
 * annotated with {@link CommandFilter} are discovered and registered by their
 * declared command prefix. At runtime, {@link #lookup(String[])} performs
 * longest-prefix matching.
 *
 * <h2>Longest-prefix matching</h2>
 * For args {@code ["git", "status", "--short"]}, the registry tries:
 * <ol>
 *   <li>{@code "git status --short"} — no match</li>
 *   <li>{@code "git status"} — match → {@code GitStatusFilter}</li>
 * </ol>
 * Falls back to {@link PassthroughStrategy} if nothing matches.
 *
 * <h2>Extensibility</h2>
 * Adding a new filter in Phase 3 requires only:
 * <ol>
 *   <li>Create a new {@code @ApplicationScoped} class implementing
 *       {@link FilterStrategy}</li>
 *   <li>Annotate it with {@code @CommandFilter("your command")}</li>
 * </ol>
 * No changes to this class are needed.
 */
@ApplicationScoped
public class StrategyRegistry {

    private static final Logger log = Logger.getLogger(StrategyRegistry.class);

    private final Map<String, FilterStrategy> registry = new LinkedHashMap<>();

    @Inject
    @Any
    Instance<FilterStrategy> strategies;

    @Inject
    PassthroughStrategy passthrough;

    @PostConstruct
    void build() {
        for (var handle : strategies.handles()) {
            Class<?> cls = handle.getBean().getBeanClass();

            // The passthrough is the explicit fallback — never register it
            if (PassthroughStrategy.class.isAssignableFrom(cls)) continue;

            CommandFilter[] annotations = cls.getAnnotationsByType(CommandFilter.class);
            if (annotations.length == 0) continue;

            FilterStrategy instance = handle.get();
            for (CommandFilter annotation : annotations) {
                String key = annotation.value().trim().toLowerCase();
                if (key.isBlank()) {
                    log.warnf("Empty @CommandFilter value on %s — skipping",
                        cls.getSimpleName());
                    continue;
                }
                registry.put(key, instance);
                log.debugf("Registered '%s' → %s", key, cls.getSimpleName());
            }
        }
        log.infof("StrategyRegistry: %d filter(s) registered", registry.size());
    }

    /**
     * Returns the best matching {@link FilterStrategy} for the given arguments.
     *
     * <p>Tries prefixes from longest to shortest. Falls back to
     * {@link PassthroughStrategy} if no prefix matches.
     *
     * @param args the command tokens as passed to zap; may be null or empty
     * @return the matching strategy; never null
     */
    public FilterStrategy lookup(String[] args) {
        if (args == null || args.length == 0) return passthrough;

        for (int len = args.length; len >= 1; len--) {
            String prefix = Arrays.stream(args, 0, len)
                .collect(Collectors.joining(" "))
                .toLowerCase()
                .trim();

            FilterStrategy strategy = registry.get(prefix);
            if (strategy != null) {
                log.debugf("Matched '%s' → %s",
                    prefix, strategy.getClass().getSimpleName());
                return strategy;
            }
        }

        log.debugf("No filter for '%s' — passthrough", String.join(" ", args));
        return passthrough;
    }

    /**
     * Returns {@code true} if a non-passthrough filter is registered for
     * this command.
     */
    public boolean hasFilter(String[] args) {
        return lookup(args) != passthrough;
    }

    /**
     * Returns all registered command prefixes in sorted order.
     * Useful for diagnostics and the {@code zap init --show} command.
     */
    public List<String> registeredCommands() {
        return registry.keySet().stream().sorted().toList();
    }
}
```

---

## SECTION 6 — `CommandExecutor.java`

This is the most complex class. Audit every contract individually.

**Check 1: Class annotation**
`@ApplicationScoped` present. NOT `@Singleton`.

**Check 2: `DEFAULT_TIMEOUT` constant**
`public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);`

**Check 3: `MAX_STREAM_BYTES` constant**
`private static final int MAX_STREAM_BYTES = 10 * 1024 * 1024;` (10 MB)

**Check 4: Primary `execute(List<String>, Duration)` method**
- Throws `IllegalArgumentException` for null/empty args — checked first
- Calls `guardAgainstInfiniteLoop(args)` before `ProcessBuilder`
- `ProcessBuilder.redirectErrorStream(false)` — MUST be false
- Working directory set to `Path.of("").toAbsolutePath().toFile()`
- Two virtual threads started BEFORE `process.waitFor()`:
  one drains `process.getInputStream()`, one drains `process.getErrorStream()`
- `process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)` used (not blocking waitFor)
- On timeout: `process.destroyForcibly()`, both threads interrupted, returns
  `ExecutionResult(-1, partialStdout, "zap: command timed out after Ns", elapsed)`
- After `waitFor` returns true: joins both threads with 5-second timeout each
- Returns `ExecutionResult(exitCode, stdout, stderr, durationMs)` with stdout and
  stderr from the captured streams

**Check 5: Convenience overloads**
- `execute(List<String> args)` delegates to `execute(args, DEFAULT_TIMEOUT)`
- `execute(String... args)` delegates to `execute(Arrays.asList(args), DEFAULT_TIMEOUT)`

**Check 6: `guardAgainstInfiniteLoop(List<String>)`**
- Fast-path: if first arg is not "zap" (case-insensitive), return immediately
- Otherwise: check `ProcessHandle.current().info().command()`
- If command file name (from `Path.of(path).getFileName()`) equals "zap" or
  "zap-runner", throw `IllegalStateException` with clear message about infinite loop

**Check 7: `StreamCapture` inner class**
- `drain(InputStream)` reads in chunks of 8192 bytes
- Enforces `MAX_STREAM_BYTES` limit; sets `truncated = true` and stops reading
  if exceeded
- `toString()` converts buffer to UTF-8 string; appends truncation notice if
  `truncated == true`
- `size()` returns current buffer byte count

If ANY check fails, write the complete correct `CommandExecutor.java`:

```java
package com.zapproxy.core;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Executes shell commands as child processes, capturing stdout and stderr
 * separately via concurrent Java 21 virtual-thread readers.
 *
 * <h2>Deadlock prevention</h2>
 * {@link ProcessBuilder} will deadlock if one stream's OS pipe buffer fills
 * while the main thread is blocked reading the other stream. This class starts
 * two virtual threads — one per stream — before calling {@code process.waitFor()},
 * so both pipes drain concurrently regardless of output volume.
 *
 * <h2>Infinite-loop prevention</h2>
 * When Zap is installed as a shell hook, commands like {@code git status} are
 * rewritten to {@code zap git status}. If the first argument resolved to Zap
 * itself (due to PATH misconfiguration), it would fork indefinitely.
 * {@link #guardAgainstInfiniteLoop(List)} detects this and throws immediately.
 *
 * <h2>Timeout</h2>
 * Every execution is bounded by a configurable timeout (default: 60 seconds).
 * Timed-out processes are forcibly destroyed and exit code {@code -1} is returned.
 *
 * <h2>Output size limit</h2>
 * Each stream is capped at 10 MB. If a command produces more output, the buffer
 * is truncated and a notice is appended. This prevents OOM on runaway commands.
 */
@ApplicationScoped
public class CommandExecutor {

    private static final Logger log = Logger.getLogger(CommandExecutor.class);

    /** Default maximum time to wait for a command to complete. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /** Per-stream output buffer cap (10 MB). */
    private static final int MAX_STREAM_BYTES = 10 * 1024 * 1024;

    /**
     * Executes a command and captures its output.
     *
     * @param args    command tokens; first element is the binary name
     * @param timeout maximum wall-clock time to allow
     * @return the captured result; never null
     * @throws IOException              if the binary cannot be started
     * @throws InterruptedException     if the calling thread is interrupted
     * @throws IllegalArgumentException if {@code args} is null or empty
     * @throws IllegalStateException    if the command would loop back to Zap
     */
    public ExecutionResult execute(List<String> args, Duration timeout)
            throws IOException, InterruptedException {

        if (args == null || args.isEmpty()) {
            throw new IllegalArgumentException("args must not be null or empty");
        }

        guardAgainstInfiniteLoop(args);

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(Path.of("").toAbsolutePath().toFile());
        pb.redirectErrorStream(false); // stdout and stderr must be separate

        log.debugf("Executing: %s (timeout=%ds)", String.join(" ", args),
            timeout.toSeconds());

        long startMs = System.currentTimeMillis();
        Process process = pb.start();

        var stdoutCapture = new StreamCapture();
        var stderrCapture = new StreamCapture();

        // Start both drainer threads BEFORE waitFor — prevents pipe-buffer deadlock
        Thread stdoutThread = Thread.ofVirtual()
            .name("zap-stdout")
            .start(() -> stdoutCapture.drain(process.getInputStream()));
        Thread stderrThread = Thread.ofVirtual()
            .name("zap-stderr")
            .start(() -> stderrCapture.drain(process.getErrorStream()));

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        long elapsed = System.currentTimeMillis() - startMs;

        if (!finished) {
            process.destroyForcibly();
            stdoutThread.interrupt();
            stderrThread.interrupt();
            log.warnf("'%s' timed out after %dms", args.get(0), elapsed);
            return new ExecutionResult(
                -1,
                stdoutCapture.toString(),
                String.format("zap: command timed out after %ds", timeout.toSeconds()),
                elapsed
            );
        }

        // Drain threads finish naturally once the process closes its streams.
        // Join with a safety timeout to handle any edge cases.
        stdoutThread.join(5_000);
        stderrThread.join(5_000);

        int exitCode = process.exitValue();
        log.debugf("Done: exit=%d, stdout=%d bytes, stderr=%d bytes, elapsed=%dms",
            exitCode, stdoutCapture.size(), stderrCapture.size(), elapsed);

        return new ExecutionResult(
            exitCode,
            stdoutCapture.toString(),
            stderrCapture.toString(),
            elapsed
        );
    }

    /** Executes with the default 60-second timeout. */
    public ExecutionResult execute(List<String> args) throws IOException, InterruptedException {
        return execute(args, DEFAULT_TIMEOUT);
    }

    /** Varargs convenience overload with the default timeout. */
    public ExecutionResult execute(String... args) throws IOException, InterruptedException {
        return execute(Arrays.asList(args), DEFAULT_TIMEOUT);
    }

    // ── private ──────────────────────────────────────────────────────────────

    private void guardAgainstInfiniteLoop(List<String> args) {
        String first = args.get(0);
        if (!"zap".equalsIgnoreCase(first)) return;

        ProcessHandle.current().info().command().ifPresent(path -> {
            String binaryName = Path.of(path).getFileName().toString();
            if ("zap".equalsIgnoreCase(binaryName)
                    || "zap-runner".equalsIgnoreCase(binaryName)) {
                throw new IllegalStateException(
                    "zap: refusing to execute 'zap' as a subprocess — " +
                    "this would loop infinitely. Check your hook configuration."
                );
            }
        });
    }

    /** Thread-safe output buffer for a single process stream. */
    private static final class StreamCapture {

        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
        private volatile boolean truncated;

        void drain(InputStream in) {
            byte[] chunk = new byte[8192];
            try {
                int read;
                while ((read = in.read(chunk)) != -1) {
                    synchronized (buf) {
                        if (buf.size() + read > MAX_STREAM_BYTES) {
                            truncated = true;
                            break;
                        }
                        buf.write(chunk, 0, read);
                    }
                }
            } catch (IOException ignored) {
                // Stream closed when process exits — expected
            }
        }

        int size() {
            return buf.size();
        }

        @Override
        public String toString() {
            String text = buf.toString(StandardCharsets.UTF_8);
            return truncated ? text + "\n[zap: output truncated at 10 MB]" : text;
        }
    }
}
```

---

## SECTION 7 — `TeeWriter.java`

Verify:
- `@ApplicationScoped` present
- Injects `PlatformDirs` and `ConfigLoader`
- `maybeDump(String command, ExecutionResult result)` returns `null` when:
  - `tee.enabled()` is false, OR
  - `tee.mode() == NEVER`, OR
  - `tee.mode() == FAILURES` and `result.succeeded()`
- Returns non-null `Path` when: `tee.mode() == ALWAYS`, or
  `tee.mode() == FAILURES` and exit non-zero
- Tee file is written to `{dataDir}/tee/{8-char-hash}-{unix-ts}.txt`
- File content starts with `# zap tee dump`, contains `# command:`, `# exit:`,
  `# elapsed:`, `# timestamp:`, and `## stdout` / `## stderr` sections
- IO exceptions are caught and logged as WARN; `null` is returned on failure
- `dump()` and `buildContent()` are private methods

If any contract is violated, write the complete correct version (use the reference
implementation from the Phase 2 prompt). Key correctness: the tee file header must
start with `# zap tee dump` exactly — tests check for this string.

---

## SECTION 8 — `GitStatusFilter.java`

This filter has many edge cases. Audit every one.

**Check 1: Annotations**
- `@CommandFilter("git status")` present — value must be exactly `"git status"`,
  lowercase, with a space
- `@ApplicationScoped` present

**Check 2: Static Pattern fields**
All three patterns must be compiled as `static final`:
- `BRANCH_PATTERN`: `"^On branch (.+)$"` with `Pattern.MULTILINE`
- `DETACHED_PATTERN`: `"^HEAD detached at (.+)$"` with `Pattern.MULTILINE`
- `CLEAN_PATTERN`: `"nothing to commit"` with `Pattern.CASE_INSENSITIVE`

**Check 3: Non-zero exit handling**
If `!result.succeeded()`, return `FilterResult.passthrough(result.combined())` immediately.
Do NOT attempt to parse the output.

**Check 4: Clean repo detection**
After confirming exit 0, check `CLEAN_PATTERN` against stdout. If it matches, return
`prefix + "✓ clean"` (Unicode checkmark, not ASCII V).

**Check 5: File counting logic**
Iterate lines of stdout. For each line of length >= 2:
- `??` prefix → `untracked++`
- First char not space/`?` → `staged++` (index column has a change)
- Second char not space/`?` → `modified++` (work-tree column has a change)
- Note: a line like `MM` counts as BOTH staged AND modified — that is correct git
  behaviour (file staged and then further modified)

**Check 6: Normal output format**
`[branch] staged: N | modified: N | untracked: N` — only include counts that
are > 0. If staged is 0, omit `"staged: 0"`. Separator is ` | ` (space-pipe-space).

**Check 7: Ultra-compact format**
Single line: `[branch] ↑S:N M:N ?:N` — only include tokens for counts > 0.
`↑S:` for staged, `M:` for modified, `?:` for untracked.

**Check 8: Verbose mode (level >= 2)**
Append newline + indented file list below the summary. Each file on its own line
prefixed with two spaces.

**Check 9: Branch extraction from detached HEAD**
`HEAD detached at abc1234f` → branch string should be `"detached@abc1234f"`.

**Check 10: Exception safety**
The entire method body after the non-zero exit check must be wrapped in
try/catch(Exception). On any parse error, log WARN and return
`FilterResult.passthrough(result.stdout())`.

Write the complete correct `GitStatusFilter.java`:

```java
package com.zapproxy.filter.git;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.ExecutionResult;
import com.zapproxy.core.FilterResult;
import com.zapproxy.core.FilterStrategy;
import com.zapproxy.core.ZapConfig;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filters {@code git status} output into a compact single-line summary.
 *
 * <h2>Output examples</h2>
 * <pre>
 * Normal:       [main] staged: 2 | modified: 1 | untracked: 3
 * Clean:        [main] ✓ clean
 * Ultra-compact: [main] ↑S:2 M:1 ?:3
 * Verbose:      [main] staged: 2 | modified: 1 | untracked: 3
 *                 A src/main/java/com/zapproxy/NewFile.java
 *                 m pom.xml
 *                 ? notes.txt
 * </pre>
 *
 * <h2>Failure passthrough</h2>
 * Non-zero exit (e.g. exit 128: "fatal: not a git repository") is passed
 * through unchanged so the AI sees the actual git error.
 */
@CommandFilter("git status")
@ApplicationScoped
public class GitStatusFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(GitStatusFilter.class);

    private static final Pattern BRANCH_PATTERN =
        Pattern.compile("^On branch (.+)$", Pattern.MULTILINE);

    private static final Pattern DETACHED_PATTERN =
        Pattern.compile("^HEAD detached at (.+)$", Pattern.MULTILINE);

    private static final Pattern CLEAN_PATTERN =
        Pattern.compile("nothing to commit", Pattern.CASE_INSENSITIVE);

    @Override
    public FilterResult apply(
            String command,
            ExecutionResult result,
            ZapConfig config,
            int verbose,
            boolean ultraCompact) {

        // Non-zero exit: git error (not-a-repo, permission denied, etc.)
        if (!result.succeeded()) {
            log.debugf("git status exited %d — passing through", result.exitCode());
            return FilterResult.passthrough(result.combined());
        }

        try {
            return filter(result.stdout(), verbose, ultraCompact);
        } catch (Exception e) {
            log.warnf("GitStatusFilter parse error: %s — falling back to passthrough",
                e.getMessage());
            return FilterResult.passthrough(result.stdout());
        }
    }

    // ── private ──────────────────────────────────────────────────────────────

    private FilterResult filter(String stdout, int verbose, boolean ultraCompact) {
        String branch = extractBranch(stdout);
        String prefix = branch != null ? "[" + branch + "] " : "";

        // Clean working tree
        if (CLEAN_PATTERN.matcher(stdout).find()) {
            return FilterResult.of(stdout, prefix + "✓ clean");
        }

        int staged    = 0;
        int modified  = 0;
        int untracked = 0;
        List<String> changedFiles = new ArrayList<>();

        for (String line : stdout.lines().toList()) {
            if (line.length() < 2) continue;

            char idx  = line.charAt(0); // index (staged) column
            char work = line.charAt(1); // work-tree (unstaged) column

            if (idx == '?' && work == '?') {
                untracked++;
                if (line.length() > 3) changedFiles.add("? " + line.substring(3).trim());
                continue;
            }

            boolean isStaged   = (idx  != ' ' && idx  != '?');
            boolean isModified = (work != ' ' && work != '?');

            if (isStaged) {
                staged++;
                if (line.length() > 3) changedFiles.add(idx + " " + line.substring(3).trim());
            }
            if (isModified) {
                modified++;
                // Avoid double-adding files that appear in both columns (MM, AM, etc.)
                if (!isStaged && line.length() > 3) {
                    changedFiles.add("m " + line.substring(3).trim());
                }
            }
        }

        String summary = buildSummary(prefix, staged, modified, untracked, ultraCompact);

        if (verbose >= 2 && !changedFiles.isEmpty()) {
            StringBuilder sb = new StringBuilder(summary).append('\n');
            for (String file : changedFiles) {
                sb.append("  ").append(file).append('\n');
            }
            return FilterResult.of(stdout, sb.toString().stripTrailing());
        }

        return FilterResult.of(stdout, summary);
    }

    private String extractBranch(String stdout) {
        Matcher m = BRANCH_PATTERN.matcher(stdout);
        if (m.find()) return m.group(1).trim();
        Matcher d = DETACHED_PATTERN.matcher(stdout);
        if (d.find()) return "detached@" + d.group(1).trim();
        return null;
    }

    private String buildSummary(String prefix, int staged, int modified,
                                int untracked, boolean ultraCompact) {
        if (ultraCompact) {
            StringBuilder sb = new StringBuilder(prefix);
            if (staged    > 0) sb.append("↑S:").append(staged).append(' ');
            if (modified  > 0) sb.append("M:").append(modified).append(' ');
            if (untracked > 0) sb.append("?:").append(untracked).append(' ');
            String result = sb.toString().stripTrailing();
            // If all counts are 0 somehow (shouldn't happen if we skipped clean),
            // return a clean line as safety fallback
            return result.equals(prefix.stripTrailing()) ? prefix + "✓ clean" : result;
        }

        List<String> parts = new ArrayList<>(3);
        if (staged    > 0) parts.add("staged: "    + staged);
        if (modified  > 0) parts.add("modified: "  + modified);
        if (untracked > 0) parts.add("untracked: " + untracked);

        return parts.isEmpty()
            ? prefix + "✓ clean"
            : prefix + String.join(" | ", parts);
    }
}
```

---

## SECTION 9 — `ZapRootCommand.java` Modifications

The root command must have been modified in Phase 2 to add passthrough dispatch.
Verify every addition.

**Check 1: New injected fields**
These must all be present as `@Inject` fields:
- `CommandExecutor executor`
- `StrategyRegistry registry`
- `TrackingRepository tracking`
- `TeeWriter teeWriter`
- `ConfigLoader configLoader`

**Check 2: `@Parameters` field for passthrough args**
```java
@Parameters(index = "0..*", hidden = true,
    description = "Command and arguments to proxy")
String[] passthroughArgs;
```
This field must be `String[]`, not `List<String>`. It must be `hidden = true` so it
does not appear in the help output (the help footer's examples serve that purpose).

**Check 3: `run()` method logic**
- If `passthroughArgs` is null or empty → print help and return
- Otherwise: build `List<String>` from args, execute, apply filter, record analytics,
  maybe dump tee, print output, propagate exit code
- Exit code must be stored via `spec.commandLine().setExecutionResult(result.exitCode())`
- Exceptions must be caught, error printed to `System.err`, exit code set to 1

**Check 4: `ZapMain.run()` exit code extraction**
After `cmd.execute(args)`, retrieve `cmd.getExecutionResult()`, cast to `Integer` if
possible, and return that value. This is what makes the OS receive the real exit code
(128 for git-not-a-repo, etc.) rather than always receiving 0.

Write the complete correct versions of both files:

**`ZapRootCommand.java`:**

```java
package com.zapproxy;

import com.zapproxy.core.*;
import jakarta.inject.Inject;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Root picocli command — the entry point for all {@code zap} invocations.
 *
 * <p>When called with recognised subcommands (e.g. {@code zap gain},
 * {@code zap init}), picocli delegates to those subcommands. When called with
 * any other arguments (e.g. {@code zap git status}), the arguments are treated
 * as a proxied shell command: the real binary is executed, its output is
 * filtered, and the exit code is propagated verbatim.
 *
 * <p>When called with no arguments, the help text is printed.
 */
@Command(
    name = "zap",
    mixinStandardHelpOptions = true,
    versionProvider = VersionProvider.class,
    description = {
        "High-performance CLI proxy that filters command output to save 60-90%% AI tokens.",
        "",
        "Zap sits between your AI coding assistant and the shell, turning noisy",
        "command output into compact summaries — so the AI spends tokens on insight,",
        "not on walls of text.",
        "",
        "Run @|bold zap gain|@ to see how many tokens you have saved."
    },
    synopsisHeading = "%nUsage: ",
    descriptionHeading = "%nDescription:%n",
    optionListHeading = "%nOptions:%n",
    commandListHeading = "%nCommands:%n",
    footer = {
        "",
        "Examples:",
        "  zap git status          # Filtered git status",
        "  zap -u git status       # Ultra-compact (icon) mode",
        "  zap -vv git status      # Verbose: summary + file list",
        "  zap cargo test          # Test failures only",
        "  zap pytest              # Failures + summary line",
        "  zap gain                # Token savings report",
        "  zap init -g             # Install AI tool hooks",
        ""
    }
)
public class ZapRootCommand implements Runnable {

    @Spec
    CommandSpec spec;

    // ── Global options (apply to all proxied commands) ────────────────────────

    @Option(
        names = {"-v", "--verbose"},
        description = "Increase verbosity (-v, -vv, -vvv)."
    )
    boolean[] verbose = new boolean[0];

    @Option(
        names = {"-u", "--ultra-compact"},
        description = "Maximum compression: single-line ASCII icon output."
    )
    boolean ultraCompact;

    // ── Injected infrastructure ───────────────────────────────────────────────

    @Inject
    CommandExecutor executor;

    @Inject
    StrategyRegistry registry;

    @Inject
    TrackingRepository tracking;

    @Inject
    TeeWriter teeWriter;

    @Inject
    ConfigLoader configLoader;

    // ── Passthrough parameters ────────────────────────────────────────────────

    @Parameters(
        index = "0..*",
        hidden = true,
        description = "Command and arguments to proxy (e.g. git status)"
    )
    String[] passthroughArgs;

    // ── Entry point ───────────────────────────────────────────────────────────

    @Override
    public void run() {
        if (passthroughArgs == null || passthroughArgs.length == 0) {
            spec.commandLine().usage(System.out);
            return;
        }

        String commandStr = String.join(" ", passthroughArgs);

        try {
            // 1. Execute the real command
            ExecutionResult result = executor.execute(Arrays.asList(passthroughArgs));

            // 2. Find and apply the best filter
            FilterStrategy strategy = registry.lookup(passthroughArgs);
            ZapConfig config = configLoader.load();
            FilterResult filtered = strategy.apply(
                commandStr, result, config, verbosityLevel(), ultraCompact);

            // 3. Record analytics (never fatal — failures are logged internally)
            tracking.insert(
                commandStr,
                ProjectFingerprint.ofCurrentDir(),
                System.getProperty("user.dir"),
                filtered.rawTokens(),
                filtered.outTokens(),
                result.durationMs()
            );

            // 4. Maybe save raw output to tee file for AI retrieval
            Path teePath = teeWriter.maybeDump(commandStr, result);

            // 5. Print filtered output to stdout
            String output = filtered.output();
            System.out.print(output);
            if (!output.isEmpty() && !output.endsWith("\n")) System.out.println();

            // 6. Append tee path notice if a file was saved
            if (teePath != null) {
                System.out.println("[raw output saved to: " + teePath.toAbsolutePath() + "]");
            }

            // 7. Store the real exit code for ZapMain to propagate to the OS
            spec.commandLine().setExecutionResult(result.exitCode());

        } catch (IllegalStateException e) {
            // Infinite-loop guard triggered
            System.err.println(e.getMessage());
            spec.commandLine().setExecutionResult(1);

        } catch (Exception e) {
            System.err.println("zap: failed to execute '" + commandStr + "': " + e.getMessage());
            spec.commandLine().setExecutionResult(1);
        }
    }

    /** Returns verbosity level 0–3 from the {@code -v} boolean array. */
    public int verbosityLevel() {
        return verbose == null ? 0 : Math.min(verbose.length, 3);
    }
}
```

**`ZapMain.java`:**

```java
package com.zapproxy;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import picocli.CommandLine;

/**
 * Quarkus entry point. Delegates all argument processing to picocli via
 * {@link ZapRootCommand}.
 *
 * <p>After picocli execution, retrieves the exit code stored by
 * {@link ZapRootCommand#run()} via {@code setExecutionResult()} and returns it
 * as the process exit code. This ensures that the OS receives the real exit code
 * of the proxied command (e.g. 128 for git-not-a-repo), not always 0.
 */
@QuarkusMain
public class ZapMain implements QuarkusApplication {

    @Inject
    CommandLine.IFactory factory;

    @Override
    public int run(String... args) {
        CommandLine cmd = new CommandLine(new ZapRootCommand(), factory)
            .setExecutionExceptionHandler((ex, c, parseResult) -> {
                c.getErr().println("zap: error: " + ex.getMessage());
                return 1;
            })
            .setCaseInsensitiveEnumValuesAllowed(true);

        int picocliExitCode = cmd.execute(args);

        // If ZapRootCommand stored a passthrough exit code (from the proxied
        // command's actual exit code), use that instead of picocli's exit code.
        Object storedResult = cmd.getExecutionResult();
        if (storedResult instanceof Integer passthroughExit) {
            return passthroughExit;
        }

        return picocliExitCode;
    }
}
```

---

## SECTION 10 — GraalVM Configuration Update

Open `src/main/resources/META-INF/native-image/reflect-config.json` and verify the
following entries exist in addition to the Phase 1 entries. Add any that are missing:

```json
  {
    "name": "com.zapproxy.core.ExecutionResult",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.zapproxy.core.FilterResult",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.zapproxy.core.PassthroughStrategy",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.zapproxy.core.StrategyRegistry",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.zapproxy.filter.git.GitStatusFilter",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.zapproxy.annotation.CommandFilter",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  },
  {
    "name": "com.zapproxy.annotation.CommandFilters",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  }
```

---

## SECTION 11 — Fixture Files

Verify every fixture file exists at the exact path listed. Create any that are missing.
Content must match exactly — tests load these files from the classpath.

**`src/test/resources/fixtures/git-status/clean.txt`**
```
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

**`src/test/resources/fixtures/git-status/modified.txt`**
```
On branch main
Your branch is up to date with 'origin/main'.

Changes not staged for commit:
  (use "git add <file>..." to update what will be committed)
  (use "git restore <file>..." to discard changes in working directory)
	modified:   src/main/java/com/example/Foo.java
	modified:   src/main/java/com/example/Bar.java

no changes added to commit (use "git add" and/or "git commit -a")
```

**`src/test/resources/fixtures/git-status/staged.txt`**
```
On branch feature/new-filter
Your branch is ahead of 'origin/feature/new-filter' by 1 commit.

Changes to be committed:
  (use "git restore --staged <file>..." to unstage)
	new file:   src/main/java/com/example/NewFilter.java
	modified:   pom.xml

```

**`src/test/resources/fixtures/git-status/untracked.txt`**
```
On branch main

Untracked files:
  (use "git add <file>..." to include in what will be committed)
	notes.txt
	scratch/
	TODO.md

nothing added to commit but untracked files present (use "git add" to track)
```

**`src/test/resources/fixtures/git-status/mixed.txt`**
```
On branch develop
Your branch is behind 'origin/develop' by 3 commits, and can be fast-forwarded.

Changes to be committed:
  (use "git restore --staged <file>..." to unstage)
	modified:   README.md

Changes not staged for commit:
  (use "git add <file>..." to update what will be committed)
	modified:   pom.xml
	modified:   src/main/java/com/example/Core.java

Untracked files:
  (use "git add <file>..." to include in what will be committed)
	scratch.txt
	temp/

```

**`src/test/resources/fixtures/git-status/detached-head.txt`**
```
HEAD detached at abc1234f
nothing to commit, working tree clean
```

---

## SECTION 12 — Test Files

For each test file: check it exists, compiles, and tests are semantically correct.
Rewrite any test file that is missing, has wrong package declarations, or has
assertions that do not match the implementation contracts above.

### 12.1 `TokenCounterTest.java`

Must be at `src/test/java/com/zapproxy/core/TokenCounterTest.java`. Does NOT need
`@QuarkusTest` — it is a plain unit test with no CDI.

```java
package com.zapproxy.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TokenCounterTest {

    @Test
    void nullReturnsZero() {
        assertThat(TokenCounter.count(null)).isZero();
    }

    @Test
    void emptyStringReturnsZero() {
        assertThat(TokenCounter.count("")).isZero();
    }

    @ParameterizedTest(name = "count(''{0}'') == {1}")
    @CsvSource({
        "a,       1",
        "abcd,    1",
        "abcde,   2",
        "abcdefgh, 2",
        "abcdefghi, 3"
    })
    void ceilingDivisionByFour(String input, int expected) {
        assertThat(TokenCounter.count(input)).isEqualTo(expected);
    }

    @Test
    void typicalGitStatusOutputIsBetween40And200Tokens() {
        String output = "On branch main\n" +
            "Your branch is up to date with 'origin/main'.\n\n" +
            "Changes not staged for commit:\n" +
            "  modified:   src/main/java/com/example/Foo.java\n" +
            "  modified:   src/main/java/com/example/Bar.java\n";
        assertThat(TokenCounter.count(output)).isBetween(40, 200);
    }

    @Test
    void savingsPct_ninetyPercentCompression() {
        String raw      = "a".repeat(400); // 100 tokens
        String filtered = "a".repeat(40);  // 10 tokens
        assertThat(TokenCounter.savingsPct(raw, filtered)).isEqualTo(90);
    }

    @Test
    void savingsPct_zeroWhenRawIsEmpty() {
        assertThat(TokenCounter.savingsPct("", "anything")).isZero();
    }

    @Test
    void savingsPct_zeroWhenNoCompression() {
        String text = "same text";
        assertThat(TokenCounter.savingsPct(text, text)).isZero();
    }
}
```

### 12.2 `ProjectFingerprintTest.java`

Does NOT need `@QuarkusTest`.

```java
package com.zapproxy.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectFingerprintTest {

    @Test
    void outputIs12LowercaseHexChars() {
        String fp = ProjectFingerprint.of("/home/user/myproject");
        assertThat(fp)
            .hasSize(12)
            .matches("[0-9a-f]{12}");
    }

    @Test
    void sameInputProducesSameOutput() {
        assertThat(ProjectFingerprint.of("/tmp/test"))
            .isEqualTo(ProjectFingerprint.of("/tmp/test"));
    }

    @Test
    void differentPathsProduceDifferentFingerprints() {
        assertThat(ProjectFingerprint.of("/home/alice/project"))
            .isNotEqualTo(ProjectFingerprint.of("/home/bob/project"));
    }

    @Test
    void nullReturnsAllZeros() {
        assertThat(ProjectFingerprint.of(null)).isEqualTo("000000000000");
    }

    @Test
    void blankReturnsAllZeros() {
        assertThat(ProjectFingerprint.of("   ")).isEqualTo("000000000000");
    }

    @Test
    void ofCurrentDirReturns12HexChars() {
        assertThat(ProjectFingerprint.ofCurrentDir())
            .isNotNull()
            .hasSize(12)
            .matches("[0-9a-f]{12}");
    }
}
```

### 12.3 `CommandExecutorTest.java`

Needs `@QuarkusTest`. Tests are annotated `@DisabledOnOs(OS.WINDOWS)` for POSIX-only
commands.

```java
package com.zapproxy.core;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class CommandExecutorTest {

    @Inject
    CommandExecutor executor;

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void capturesStdout() throws Exception {
        ExecutionResult r = executor.execute("echo", "hello zap");
        assertThat(r.exitCode()).isZero();
        assertThat(r.stdout().trim()).isEqualTo("hello zap");
        assertThat(r.stderr()).isBlank();
        assertThat(r.succeeded()).isTrue();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void propagatesNonZeroExitCode() throws Exception {
        // 'false' is a POSIX builtin that always exits 1
        ExecutionResult r = executor.execute("false");
        assertThat(r.exitCode()).isEqualTo(1);
        assertThat(r.succeeded()).isFalse();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void capturesStderrSeparately() throws Exception {
        ExecutionResult r = executor.execute("sh", "-c", "echo err >&2");
        assertThat(r.exitCode()).isZero();
        assertThat(r.stdout()).isBlank();
        assertThat(r.stderr().trim()).isEqualTo("err");
        assertThat(r.hasStderr()).isTrue();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void capturesBothStreamsConcurrently() throws Exception {
        // Writes to both stdout and stderr — validates deadlock prevention
        ExecutionResult r = executor.execute("sh", "-c", "echo out; echo err >&2");
        assertThat(r.stdout().trim()).isEqualTo("out");
        assertThat(r.stderr().trim()).isEqualTo("err");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void durationMsIsPositive() throws Exception {
        ExecutionResult r = executor.execute("echo", "timing");
        assertThat(r.durationMs()).isPositive();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void timeoutKillsSlowProcess() throws Exception {
        ExecutionResult r = executor.execute(
            List.of("sleep", "30"), Duration.ofMillis(200));
        assertThat(r.exitCode()).isEqualTo(-1);
        assertThat(r.stderr()).contains("timed out");
        assertThat(r.durationMs()).isLessThan(5_000);
    }

    @Test
    void emptyArgsThrowsIllegalArgument() {
        assertThatThrownBy(() -> executor.execute(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullArgsThrowsIllegalArgument() {
        assertThatThrownBy(() -> executor.execute((List<String>) null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

### 12.4 `StrategyRegistryTest.java`

Needs `@QuarkusTest`.

```java
package com.zapproxy.core;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class StrategyRegistryTest {

    @Inject
    StrategyRegistry registry;

    @Inject
    PassthroughStrategy passthrough;

    @Test
    void atLeastOneFilterIsRegistered() {
        assertThat(registry.registeredCommands()).isNotEmpty();
    }

    @Test
    void gitStatusIsRegistered() {
        assertThat(registry.registeredCommands()).contains("git status");
    }

    @Test
    void lookupGitStatusReturnsGitStatusFilter() {
        FilterStrategy s = registry.lookup(new String[]{"git", "status"});
        assertThat(s).isNotInstanceOf(PassthroughStrategy.class);
        assertThat(s.getClass().getSimpleName()).isEqualTo("GitStatusFilter");
    }

    @Test
    void lookupWithExtraFlagsStillMatchesPrefix() {
        FilterStrategy s = registry.lookup(new String[]{"git", "status", "--short"});
        assertThat(s.getClass().getSimpleName()).isEqualTo("GitStatusFilter");
    }

    @Test
    void lookupUnknownCommandReturnsPassthrough() {
        FilterStrategy s = registry.lookup(new String[]{"unknowncmd", "--flag"});
        assertThat(s).isInstanceOf(PassthroughStrategy.class);
    }

    @Test
    void lookupEmptyArgsReturnsPassthrough() {
        assertThat(registry.lookup(new String[]{}))
            .isInstanceOf(PassthroughStrategy.class);
    }

    @Test
    void lookupNullReturnsPassthrough() {
        assertThat(registry.lookup(null))
            .isInstanceOf(PassthroughStrategy.class);
    }

    @Test
    void hasFilterTrueForGitStatus() {
        assertThat(registry.hasFilter(new String[]{"git", "status"})).isTrue();
    }

    @Test
    void hasFilterFalseForUnknown() {
        assertThat(registry.hasFilter(new String[]{"notacommand"})).isFalse();
    }

    @Test
    void registeredCommandsListIsSorted() {
        var cmds = registry.registeredCommands();
        assertThat(cmds).isSortedAccordingTo(String::compareTo);
    }
}
```

### 12.5 `TeeWriterTest.java`

Needs `@QuarkusTest`.

```java
package com.zapproxy.core;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class TeeWriterTest {

    @Inject
    TeeWriter teeWriter;

    @Test
    void returnsNullWhenCommandSucceeds_defaultConfig() {
        // Default config: mode=FAILURES, so exit 0 → no tee file
        var result = new ExecutionResult(0, "some output", "", 10L);
        assertThat(teeWriter.maybeDump("git status", result)).isNull();
    }

    @Test
    void returnsNullForNullOutput() {
        var result = new ExecutionResult(0, "", "", 1L);
        assertThat(teeWriter.maybeDump("echo", result)).isNull();
    }

    @Test
    void createsFileWhenCommandFails_defaultConfig() throws Exception {
        var result = new ExecutionResult(128, "", "fatal: not a git repository", 5L);
        Path p = teeWriter.maybeDump("git status", result);

        assertThat(p).isNotNull();
        assertThat(Files.exists(p)).isTrue();

        String content = Files.readString(p);
        assertThat(content).startsWith("# zap tee dump");
        assertThat(content).contains("# command: git status");
        assertThat(content).contains("# exit:    128");
        assertThat(content).contains("fatal: not a git repository");

        Files.deleteIfExists(p);
    }

    @Test
    void teeFileContainsBothStdoutAndStderr() throws Exception {
        var result = new ExecutionResult(1, "stdout content", "stderr content", 42L);
        Path p = teeWriter.maybeDump("some command", result);

        assertThat(p).isNotNull();
        String content = Files.readString(p);
        assertThat(content).contains("## stdout");
        assertThat(content).contains("stdout content");
        assertThat(content).contains("## stderr");
        assertThat(content).contains("stderr content");

        Files.deleteIfExists(p);
    }

    @Test
    void teeFileNameHas8CharHashAndTimestamp() throws Exception {
        var result = new ExecutionResult(1, "", "error", 1L);
        Path p = teeWriter.maybeDump("git status", result);

        assertThat(p).isNotNull();
        String filename = p.getFileName().toString();
        // Format: {8hexchars}-{timestamp}.txt
        assertThat(filename).matches("[0-9a-f]{8}-\\d+\\.txt");

        Files.deleteIfExists(p);
    }
}
```

### 12.6 `GitStatusFilterTest.java`

Does NOT need `@QuarkusTest` — the filter is instantiated directly.

```java
package com.zapproxy.filter.git;

import com.zapproxy.core.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitStatusFilterTest {

    private GitStatusFilter filter;
    private ZapConfig config;

    @BeforeEach
    void setUp() {
        filter = new GitStatusFilter();
        config = ZapConfig.defaults();
    }

    // ── fixture loading ───────────────────────────────────────────────────────

    private String fixture(String name) throws IOException, URISyntaxException {
        URL url = getClass().getResource("/fixtures/git-status/" + name + ".txt");
        assertThat(url)
            .as("Fixture /fixtures/git-status/%s.txt must exist on classpath", name)
            .isNotNull();
        return Files.readString(Path.of(url.toURI()));
    }

    private FilterResult run(String fixtureName) throws Exception {
        String raw = fixture(fixtureName);
        return filter.apply("git status",
            new ExecutionResult(0, raw, "", 10L), config, 0, false);
    }

    // ── clean ─────────────────────────────────────────────────────────────────

    @Test
    void cleanRepo_outputContainsCheckmarkAndBranch() throws Exception {
        FilterResult r = run("clean");
        assertThat(r.output()).isEqualTo("[main] ✓ clean");
    }

    @Test
    void cleanRepo_wasFiltered() throws Exception {
        assertThat(run("clean").wasFiltered()).isTrue();
    }

    @Test
    void cleanRepo_positiveTokenSavings() throws Exception {
        FilterResult r = run("clean");
        assertThat(r.rawTokens()).isGreaterThan(r.outTokens());
    }

    // ── modified ──────────────────────────────────────────────────────────────

    @Test
    void modified_showsModifiedCount() throws Exception {
        FilterResult r = run("modified");
        assertThat(r.output()).contains("modified: 2").contains("[main]");
    }

    @Test
    void modified_doesNotShowZeroCountsForStagedOrUntracked() throws Exception {
        FilterResult r = run("modified");
        assertThat(r.output()).doesNotContain("staged:");
        assertThat(r.output()).doesNotContain("untracked:");
    }

    // ── staged ────────────────────────────────────────────────────────────────

    @Test
    void staged_showsStagedCountAndBranch() throws Exception {
        FilterResult r = run("staged");
        assertThat(r.output()).contains("staged: 2").contains("[feature/new-filter]");
    }

    // ── untracked ─────────────────────────────────────────────────────────────

    @Test
    void untracked_showsUntrackedCount() throws Exception {
        FilterResult r = run("untracked");
        assertThat(r.output()).contains("untracked: 3");
        assertThat(r.output()).doesNotContain("staged:").doesNotContain("modified:");
    }

    // ── mixed ─────────────────────────────────────────────────────────────────

    @Test
    void mixed_showsAllThreeCountsWithPipeSeparator() throws Exception {
        FilterResult r = run("mixed");
        assertThat(r.output())
            .contains("staged: 1")
            .contains("modified: 2")
            .contains("untracked: 2")
            .contains("[develop]")
            .contains("|");
    }

    // ── detached HEAD ─────────────────────────────────────────────────────────

    @Test
    void detachedHead_showsDetachedPrefixAndClean() throws Exception {
        FilterResult r = run("detached-head");
        assertThat(r.output()).contains("detached@abc1234f").contains("✓ clean");
    }

    // ── failure passthrough ───────────────────────────────────────────────────

    @Test
    void nonZeroExit_passesStderrThrough() {
        var failure = new ExecutionResult(
            128, "", "fatal: not a git repository", 3L);
        FilterResult r = filter.apply("git status", failure, config, 0, false);
        assertThat(r.wasFiltered()).isFalse();
        assertThat(r.output()).contains("fatal: not a git repository");
    }

    @Test
    void nonZeroExit_exitCodeNotModifiedByFilter() {
        // Filters must not touch exit codes — verify the result record
        var failure = new ExecutionResult(128, "", "fatal: error", 1L);
        FilterResult r = filter.apply("git status", failure, config, 0, false);
        // savingsPct of 0 confirms no filtering was applied
        assertThat(r.savingsPct()).isZero();
    }

    // ── ultra-compact ─────────────────────────────────────────────────────────

    @Test
    void ultraCompact_mixed_isSingleLine() throws Exception {
        String raw = fixture("mixed");
        FilterResult r = filter.apply("git status",
            new ExecutionResult(0, raw, "", 10L), config, 0, true);
        assertThat(r.output().lines().count()).isEqualTo(1L);
    }

    @Test
    void ultraCompact_mixed_usesIconFormat() throws Exception {
        String raw = fixture("mixed");
        FilterResult r = filter.apply("git status",
            new ExecutionResult(0, raw, "", 10L), config, 0, true);
        // Must NOT use human-readable words
        assertThat(r.output()).doesNotContain("staged:");
        assertThat(r.output()).doesNotContain("modified:");
        assertThat(r.output()).doesNotContain("untracked:");
        // Must use at least one icon token
        boolean hasIcon = r.output().contains("↑S:") || r.output().contains("M:")
            || r.output().contains("?:");
        assertThat(hasIcon).isTrue();
    }

    // ── verbose ───────────────────────────────────────────────────────────────

    @Test
    void verbose2_appendsFileListBelowSummary() throws Exception {
        String raw = fixture("mixed");
        FilterResult r = filter.apply("git status",
            new ExecutionResult(0, raw, "", 10L), config, 2, false);
        assertThat(r.output().lines().count()).isGreaterThan(1L);
    }

    @Test
    void verbose1_doesNotAppendFileList() throws Exception {
        String raw = fixture("modified");
        FilterResult r = filter.apply("git status",
            new ExecutionResult(0, raw, "", 10L), config, 1, false);
        assertThat(r.output().lines().count()).isEqualTo(1L);
    }

    // ── token savings ─────────────────────────────────────────────────────────

    @Test
    void allFixturesProducePositiveTokenSavings() throws Exception {
        for (String name : List.of("clean", "modified", "staged", "untracked", "mixed")) {
            FilterResult r = run(name);
            assertThat(r.savingsPct())
                .as("Expected positive savings for fixture '%s' but got %d%%",
                    name, r.savingsPct())
                .isPositive();
        }
    }
}
```

---

## SECTION 13 — CDI Beans XML (if needed)

If `StrategyRegistry` logs "0 filter(s) registered" during tests, CDI is not
discovering `GitStatusFilter`. The fix is to add a `beans.xml` that enables
bean discovery for the whole classpath:

Create `src/main/resources/META-INF/beans.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="https://jakarta.ee/xml/ns/jakartaee"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee
           https://jakarta.ee/xml/ns/jakartaee/beans_3_0.xsd"
       version="3.0"
       bean-discovery-mode="all">
</beans>
```

This forces Quarkus CDI to scan ALL classes, not only those with explicit CDI
annotations in packages it has already discovered. Required when the
`com.zapproxy.filter.git` package does not already have a CDI-annotated class
that triggers package scanning.

---

## SECTION 14 — Full Verification Gauntlet

Run every command in order. Do not proceed to Phase 3 until all pass.

### Step 1: Clean JVM build
```bash
mvn clean verify
```
Expected: `BUILD SUCCESS`. Zero failures. Zero errors. The Surefire report must list
all six new test classes passing: `CommandExecutorTest`, `TokenCounterTest`,
`ProjectFingerprintTest`, `TeeWriterTest`, `StrategyRegistryTest`,
`GitStatusFilterTest`.

### Step 2: Count total tests (must be ≥ 35)
```bash
mvn test 2>&1 | grep "Tests run:" | awk -F'[:,]' '{sum += $2} END {print "Total test methods:", sum}'
```
Expected: ≥ 35 total. (Phase 1 contributed ~20; Phase 2 adds ~20 more.)

### Step 3: Confirm StrategyRegistry found GitStatusFilter
```bash
mvn test 2>&1 | grep -i "StrategyRegistry"
```
Expected: a line matching `StrategyRegistry: 1 filter(s) registered`.
If it shows 0, CDI discovery is broken — see Section 13.

### Step 4: Native image build
```bash
mvn package -Pnative -DskipTests
```
Expected: `BUILD SUCCESS`. No line containing "fallback image" in output.
Binary: `target/zap-runner`.

### Step 5: Version and help smoke tests
```bash
./target/zap-runner --version
./target/zap-runner --help
```
Expected:
- `--version` prints three lines: `zap 0.1.0`, `Java ...`, `Built with GraalVM Native Image`
- `--help` prints help with description, options, and the Examples footer

### Step 6: Real git status — must compress
Run from inside a git repository (the project itself).
```bash
# First make sure there are some changes (or this repo itself has changes)
./target/zap-runner git status
```
Expected: compact output like `[main] staged: 1 | modified: 2 | untracked: 3`
or `[main] ✓ clean`. Must NOT be raw git output.

### Step 7: Ultra-compact mode
```bash
./target/zap-runner -u git status
```
Expected: single line with icon format, e.g. `[main] M:2 ?:1`

### Step 8: Verbose mode
```bash
./target/zap-runner -vv git status
```
Expected: summary line + indented file list. Multiple lines of output.

### Step 9: Passthrough for unknown command
```bash
./target/zap-runner echo "hello from zap"
```
Expected: `hello from zap` (unchanged, no filtering applied)

### Step 10: Exit code propagation
```bash
cd /tmp
/path/to/target/zap-runner git status
echo "Exit code was: $?"
cd -
```
Expected: `Exit code was: 128` (git's exit code for "not a git repository").
If you see `Exit code was: 0`, the exit code propagation in `ZapMain` is broken.

### Step 11: SQLite analytics row inserted
```bash
./target/zap-runner git status
sqlite3 ~/.local/share/zap/zap.db \
  "SELECT command, raw_tokens, out_tokens, exec_ms FROM commands ORDER BY ts DESC LIMIT 3;"
```
Expected: rows visible with `git status`, positive `raw_tokens`, smaller `out_tokens`,
positive `exec_ms`.

### Step 12: Tee file created on failure
```bash
cd /tmp
/path/to/target/zap-runner git status 2>/dev/null
ls ~/.local/share/zap/tee/
cd -
```
Expected: a `.txt` file with a name like `a3f9d2c1-1719400000.txt`. Its content must
start with `# zap tee dump`.

### Step 13: No Quarkus banner
```bash
./target/zap-runner --version 2>&1 | grep -i quarkus
./target/zap-runner git status   2>&1 | grep -i quarkus
```
Expected: no output from either command.

### Step 14: Startup time still ≤ 100ms
```bash
for i in {1..10}; do { time ./target/zap-runner --version; } 2>&1 | grep real; done
```
Expected: all values `0m0.0XXs`. Median must be under 100ms.

### Step 15: Complete file checklist
```bash
find src -name "*.java" | sort
```
Must include ALL of:
```
src/main/java/com/zapproxy/ZapMain.java
src/main/java/com/zapproxy/ZapRootCommand.java
src/main/java/com/zapproxy/VersionProvider.java
src/main/java/com/zapproxy/annotation/CommandFilter.java
src/main/java/com/zapproxy/annotation/CommandFilters.java
src/main/java/com/zapproxy/core/CommandExecutor.java
src/main/java/com/zapproxy/core/ConfigLoader.java
src/main/java/com/zapproxy/core/ExecutionResult.java
src/main/java/com/zapproxy/core/FilterResult.java
src/main/java/com/zapproxy/core/FilterStrategy.java
src/main/java/com/zapproxy/core/PassthroughStrategy.java
src/main/java/com/zapproxy/core/PlatformDirs.java
src/main/java/com/zapproxy/core/ProjectFingerprint.java
src/main/java/com/zapproxy/core/StrategyRegistry.java
src/main/java/com/zapproxy/core/TeeMode.java
src/main/java/com/zapproxy/core/TeeWriter.java
src/main/java/com/zapproxy/core/TokenCounter.java
src/main/java/com/zapproxy/core/TrackingRepository.java
src/main/java/com/zapproxy/core/ZapConfig.java
src/main/java/com/zapproxy/filter/git/GitStatusFilter.java
src/test/java/com/zapproxy/VersionProviderTest.java
src/test/java/com/zapproxy/core/CommandExecutorTest.java
src/test/java/com/zapproxy/core/ConfigLoaderTest.java
src/test/java/com/zapproxy/core/PlatformDirsTest.java
src/test/java/com/zapproxy/core/ProjectFingerprintTest.java
src/test/java/com/zapproxy/core/StrategyRegistryTest.java
src/test/java/com/zapproxy/core/TeeModeTest.java
src/test/java/com/zapproxy/core/TeeWriterTest.java
src/test/java/com/zapproxy/core/TokenCounterTest.java
src/test/java/com/zapproxy/core/TrackingRepositoryTest.java
src/test/java/com/zapproxy/filter/git/GitStatusFilterTest.java
```

---

## Phase 2 Sign-Off Table

Phase 2 is complete when ALL rows in this table pass simultaneously.

| # | Criterion | Verification |
|---|---|---|
| 1 | `mvn verify` exits 0 | `echo $?` → `0` |
| 2 | All Phase 1 tests still green | No regressions in Phase 1 classes |
| 3 | All Phase 2 tests pass (≥ 20 new tests) | Surefire report, 0 failures |
| 4 | Total test count ≥ 35 | Count via grep |
| 5 | StrategyRegistry logs "1 filter(s) registered" | Grep test output |
| 6 | Native image builds without fallback | No "fallback image" in build output |
| 7 | `zap git status` compresses output | Smoke test Step 6 |
| 8 | `zap -u git status` emits icon-format single line | Smoke test Step 7 |
| 9 | `zap echo hello` passes through unchanged | Smoke test Step 9 |
| 10 | Exit code 128 propagated from `/tmp` | Smoke test Step 10 |
| 11 | SQLite row inserted after `zap git status` | Step 11 sqlite3 query |
| 12 | Tee file written on failed command | Step 12 ls check |
| 13 | Cold start median ≤ 100ms | Benchmark loop Step 14 |
| 14 | All 31 Java files in checklist exist | `find` command Step 15 |
| 15 | No Quarkus banner in any output | Step 13 grep check |

When all 15 criteria pass simultaneously, respond with:

```
PHASE 2 COMPLETE
────────────────────────────────────────
Tests:          [N passing / 0 failing / 0 errors]
New tests:      [~20 in Phase 2 classes]
Native build:   PASS — no fallback
Cold start:     [Xms median]
Filters:        1 registered (git status → GitStatusFilter)
Analytics:      PASS — rows inserted to SQLite
Tee:            PASS — dump file created on failure
Exit codes:     PASS — 128 propagated from /tmp

Ready for Phase 3: All 42 Command Filter Modules.
```

---

## Troubleshooting Index

| Symptom | Section to consult |
|---|---|
| StrategyRegistry: 0 filter(s) registered | Section 5.2 CDI proxy fix + Section 13 beans.xml |
| Exit code always 0 | Section 9 ZapMain.run() exit code extraction |
| `zap git status` prints raw git output | Section 8 GitStatusFilter @CommandFilter annotation check |
| `UnsatisfiedLinkError` on SQLite in native | Phase 1 audit Section 3.4 JNI config |
| Native image fallback warning | Section 10 reflect-config + native-image.properties `--no-fallback` |
| TeeWriter creates file even on success | Section 7 — check `tee.mode()` switch statement has correct FAILURES branch |
| `zap -u git status` shows multiple lines | Section 8 Check 7 — ultraCompact path must return immediately, not fall through to verbose block |
| GitStatusFilter counts wrong number of files | Section 8 Check 5 — porcelain v1 parsing: `??` = untracked, col 0 = staged, col 1 = modified |
| `CommandExecutorTest.timeoutKillsSlowProcess` fails | Platform issue — increase timeout in test from 200ms to 500ms if CI is slow |

---

*Phase 2 Audit Prompt — version 1.0*
*Next: Phase 3 Agent Prompt — All 42 Command Filter Modules*
