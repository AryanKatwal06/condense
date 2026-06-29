# Zap Java Port — Phase 2: Command Execution Engine, Filter Architecture & GitStatusFilter

> You are a senior Java engineer implementing Phase 2 of the Zap Java + GraalVM port.
> Phase 1 is complete: the project scaffolds, compiles to native, and all infrastructure
> tests pass. Phase 2 ends when `zap git status` works end-to-end on a real git
> repository, writing compressed output to stdout and a row to SQLite. Read every
> section before writing any code. Do not skip ahead to Phase 3.

---

## Phase 1 Completion Contract (Verify Before Starting)

Before writing a single line of Phase 2 code, verify that Phase 1 left the repo in the
correct state. Run these commands. All must pass. If any fail, fix Phase 1 first.

```bash
# All tests green
mvn verify
# Native binary exists and starts under 100ms
./target/zap-runner --version     # prints "zap 0.1.0"
./target/zap-runner --help        # prints help with examples footer
# SQLite infrastructure present
ls src/main/java/com/zapproxy/core/TrackingRepository.java   # must exist
ls src/main/java/com/zapproxy/core/ConfigLoader.java         # must exist
ls src/main/java/com/zapproxy/core/PlatformDirs.java         # must exist
```

If `mvn verify` fails or the native binary does not exist, do not proceed. Fix Phase 1.

---

## What Phase 2 Adds

Phase 2 adds exactly these files and nothing else. Every file is listed here. If
something is not on this list, it does not belong in Phase 2.

**New source files:**
```
src/main/java/com/zapproxy/core/ExecutionResult.java
src/main/java/com/zapproxy/core/FilterResult.java
src/main/java/com/zapproxy/core/FilterStrategy.java
src/main/java/com/zapproxy/core/CommandExecutor.java
src/main/java/com/zapproxy/core/TokenCounter.java
src/main/java/com/zapproxy/core/ProjectFingerprint.java
src/main/java/com/zapproxy/core/TeeWriter.java
src/main/java/com/zapproxy/core/StrategyRegistry.java
src/main/java/com/zapproxy/core/PassthroughStrategy.java
src/main/java/com/zapproxy/annotation/CommandFilter.java
src/main/java/com/zapproxy/filter/git/GitStatusFilter.java
```

**Modified source files:**
```
src/main/java/com/zapproxy/ZapRootCommand.java   (add passthrough dispatch)
src/main/resources/META-INF/native-image/reflect-config.json  (add new classes)
```

**New test files:**
```
src/test/java/com/zapproxy/core/CommandExecutorTest.java
src/test/java/com/zapproxy/core/TokenCounterTest.java
src/test/java/com/zapproxy/core/ProjectFingerprintTest.java
src/test/java/com/zapproxy/core/TeeWriterTest.java
src/test/java/com/zapproxy/core/StrategyRegistryTest.java
src/test/java/com/zapproxy/filter/git/GitStatusFilterTest.java
```

**New fixture files:**
```
src/test/resources/fixtures/git-status/clean.txt
src/test/resources/fixtures/git-status/modified.txt
src/test/resources/fixtures/git-status/untracked.txt
src/test/resources/fixtures/git-status/staged.txt
src/test/resources/fixtures/git-status/mixed.txt
src/test/resources/fixtures/git-status/detached-head.txt
```

---

## Step 1 — Create the Data Records

These are pure data carriers with no logic. Create them first because everything else
depends on them.

### `ExecutionResult.java`

```java
package com.zapproxy.core;

/**
 * The raw result of executing a shell command via {@link CommandExecutor}.
 *
 * @param exitCode   the process exit code; -1 if the process timed out
 * @param stdout     captured standard output, never null (empty string if none)
 * @param stderr     captured standard error, never null (empty string if none)
 * @param durationMs wall-clock time from process start to exit, in milliseconds
 */
public record ExecutionResult(
    int exitCode,
    String stdout,
    String stderr,
    long durationMs
) {

    /** Returns true if the command exited with code 0. */
    public boolean succeeded() {
        return exitCode == 0;
    }

    /** Returns true if stderr contains any non-whitespace content. */
    public boolean hasStderr() {
        return stderr != null && !stderr.isBlank();
    }

    /** Returns the combined stdout + stderr for tee/passthrough scenarios. */
    public String combined() {
        if (stdout.isBlank()) return stderr;
        if (stderr.isBlank()) return stdout;
        return stdout + "\n" + stderr;
    }
}
```

### `FilterResult.java`

```java
package com.zapproxy.core;

/**
 * The output produced by a {@link FilterStrategy} after compressing a command's
 * raw output.
 *
 * @param output      the compressed output string to print to stdout
 * @param rawTokens   estimated token count of the original command output
 * @param outTokens   estimated token count of {@code output}
 * @param wasFiltered true if any compression was applied; false if output is
 *                    identical to raw (passthrough scenario)
 */
public record FilterResult(
    String output,
    int rawTokens,
    int outTokens,
    boolean wasFiltered
) {

    /** Percentage of tokens saved, 0–100. Returns 0 if rawTokens is 0. */
    public int savingsPct() {
        if (rawTokens == 0) return 0;
        return (int) (100L * (rawTokens - outTokens) / rawTokens);
    }

    /**
     * Convenience factory: build a FilterResult for a passthrough (no filtering).
     * rawTokens == outTokens, wasFiltered == false.
     */
    public static FilterResult passthrough(String output) {
        int tokens = TokenCounter.count(output);
        return new FilterResult(output, tokens, tokens, false);
    }

    /**
     * Convenience factory: build a FilterResult for a successfully compressed output.
     */
    public static FilterResult of(String rawInput, String filteredOutput) {
        return new FilterResult(
            filteredOutput,
            TokenCounter.count(rawInput),
            TokenCounter.count(filteredOutput),
            true
        );
    }
}
```

---

## Step 2 — `TokenCounter.java`

```java
package com.zapproxy.core;

/**
 * Approximates GPT/Claude tokenisation for analytics purposes.
 *
 * <p>The approximation is: <strong>1 token ≈ 4 characters</strong>.
 * This matches the token savings reported in the Zap README closely enough
 * for the {@code zap gain} analytics command. A proper tokeniser (tiktoken,
 * claude-tokenizer) would add significant native-image complexity for marginal
 * accuracy gain.
 *
 * <p>This class is stateless and has no public constructor — use the static
 * {@link #count(String)} method directly.
 */
public final class TokenCounter {

    private TokenCounter() {}

    /**
     * Estimates the number of tokens in {@code text}.
     *
     * @param text the text to count; null and empty strings return 0
     * @return estimated token count, always >= 0
     */
    public static int count(String text) {
        if (text == null || text.isEmpty()) return 0;
        // Integer ceiling division: (n + 3) / 4
        return (text.length() + 3) / 4;
    }

    /**
     * Estimates savings percentage between raw and filtered text.
     *
     * @return percentage saved, 0–100
     */
    public static int savingsPct(String raw, String filtered) {
        int rawTokens = count(raw);
        if (rawTokens == 0) return 0;
        int outTokens = count(filtered);
        return (int) (100L * (rawTokens - outTokens) / rawTokens);
    }
}
```

---

## Step 3 — `ProjectFingerprint.java`

The analytics database identifies which git repo a command ran in via a 12-character
hex fingerprint derived from the current working directory path. This lets `zap gain
--scope project` filter stats per repo.

```java
package com.zapproxy.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Generates a stable, short fingerprint for the current working directory.
 *
 * <p>The fingerprint is the first 12 hexadecimal characters of the SHA-256
 * hash of the absolute path string. This uniquely identifies a project
 * directory for the {@code zap gain --scope project} analytics filter
 * without storing the actual path in the database.
 */
public final class ProjectFingerprint {

    private ProjectFingerprint() {}

    /**
     * Returns a 12-character hex fingerprint of the given path string.
     *
     * @param absolutePath the absolute working directory path
     * @return 12 lowercase hex characters, e.g. {@code "a3f9d2c1b407"}
     */
    public static String of(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank()) return "000000000000";
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(absolutePath.getBytes(StandardCharsets.UTF_8));
            // Convert first 6 bytes to 12 hex chars
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present in all JVMs
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Returns a fingerprint for the current JVM working directory.
     */
    public static String ofCurrentDir() {
        return of(System.getProperty("user.dir"));
    }
}
```

---

## Step 4 — `FilterStrategy.java` Interface

```java
package com.zapproxy.core;

/**
 * Contract for all command output filter implementations.
 *
 * <p>Each implementation handles one or more shell commands (e.g., "git status",
 * "cargo test") and compresses their raw output into a dense summary for AI context
 * windows. Implementations are CDI beans annotated with
 * {@link com.zapproxy.annotation.CommandFilter}.
 *
 * <p>Implementations must be stateless — a single instance is used for all
 * invocations. All state must be local to the {@link #apply} method.
 */
public interface FilterStrategy {

    /**
     * Applies this filter to the raw command output.
     *
     * <p>Implementations must obey these contracts:
     * <ul>
     *   <li>If {@code result.exitCode()} is non-zero and the command genuinely
     *       failed, return the stderr verbatim (possibly via
     *       {@link FilterResult#passthrough}) unless the filter specifically
     *       handles failure output (e.g., test runners where failures ARE the
     *       interesting output).</li>
     *   <li>Never throw an exception — catch all errors, log a warning, and return
     *       {@link FilterResult#passthrough} as a safe fallback.</li>
     *   <li>Never modify the exit code — the caller propagates the original exit
     *       code from {@link ExecutionResult#exitCode()} to the OS.</li>
     * </ul>
     *
     * @param command      the full command string (e.g., "git status"),
     *                     used for logging and tee file naming
     * @param result       the raw output from {@link CommandExecutor}
     * @param config       the user's ZapConfig (for exclude lists, tee settings, etc.)
     * @param verbose      verbosity level: 0 = compact, 1 = normal, 2 = verbose,
     *                     3 = maximum verbosity (show everything)
     * @param ultraCompact if true, produce the absolute minimum output:
     *                     single-line ASCII icon format
     * @return a {@link FilterResult} with compressed output and token metadata;
     *         never null
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

## Step 5 — `@CommandFilter` Qualifier Annotation

```java
package com.zapproxy.annotation;

import jakarta.inject.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CDI qualifier that maps a {@link com.zapproxy.core.FilterStrategy} implementation
 * to one or more command prefixes.
 *
 * <p>Example usage:
 * <pre>{@code
 * @CommandFilter("git status")
 * @ApplicationScoped
 * public class GitStatusFilter implements FilterStrategy { ... }
 * }</pre>
 *
 * <p>The value must be the command prefix as the user types it — lowercase,
 * space-separated tokens (e.g., "git status", "cargo test", "pytest").
 * The {@link com.zapproxy.core.StrategyRegistry} performs longest-prefix matching,
 * so "git status" takes priority over "git" when the command is "git status --short".
 *
 * <p>Multiple commands that share the same filter logic should each be annotated
 * separately using {@link CommandFilters}.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
public @interface CommandFilter {
    String value();
}
```

Also create the repeatable container (needed if a future filter handles multiple
command prefixes):

```java
package com.zapproxy.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container annotation for {@link CommandFilter} when multiple command prefixes
 * map to the same filter implementation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
public @interface CommandFilters {
    CommandFilter[] value();
}
```

---

## Step 6 — `PassthroughStrategy.java`

The fallback for commands with no registered filter. It executes the command via
`CommandExecutor` and returns the raw output unchanged. It must NOT be annotated with
`@CommandFilter` — it is the default, not a registered handler.

```java
package com.zapproxy.core;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Default filter strategy used when no registered {@link FilterStrategy} matches
 * the command. Returns the raw command output unchanged.
 *
 * <p>Records rawTokens == outTokens (0% savings) in the analytics database, which
 * is correct — no compression was applied.
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

        // For passthrough: use combined stdout+stderr so the AI sees everything.
        // If exit is non-zero, stderr is the important content.
        String output = result.succeeded()
            ? result.stdout()
            : result.combined();

        return FilterResult.passthrough(output);
    }
}
```

---

## Step 7 — `StrategyRegistry.java`

This is the dispatcher. It builds a map from command prefix → FilterStrategy at startup
and performs longest-prefix lookup at runtime.

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
 * Resolves the correct {@link FilterStrategy} for a given command.
 *
 * <p>At startup, all CDI beans implementing {@link FilterStrategy} that are
 * annotated with {@link CommandFilter} are discovered and registered by their
 * command prefix. At runtime, {@link #lookup(String[])} performs longest-prefix
 * matching to find the best matching strategy.
 *
 * <p>Example: for args ["git", "status", "--short"], the registry tries:
 * <ol>
 *   <li>"git status --short" — no match</li>
 *   <li>"git status" — match → GitStatusFilter</li>
 * </ol>
 * Falls back to {@link PassthroughStrategy} if nothing matches.
 */
@ApplicationScoped
public class StrategyRegistry {

    private static final Logger log = Logger.getLogger(StrategyRegistry.class);

    /** Registered strategies, keyed by command prefix (lowercase, space-joined). */
    private final Map<String, FilterStrategy> registry = new LinkedHashMap<>();

    @Inject
    @Any
    Instance<FilterStrategy> strategies;

    @Inject
    PassthroughStrategy passthrough;

    @PostConstruct
    void build() {
        for (FilterStrategy strategy : strategies) {
            Class<?> cls = strategy.getClass();
            // Handle Quarkus CDI proxy wrappers: get the actual class
            if (cls.getName().contains("_Subclass") || cls.getName().contains("$Proxy")) {
                cls = cls.getSuperclass();
            }

            CommandFilter[] annotations = cls.getAnnotationsByType(CommandFilter.class);
            for (CommandFilter annotation : annotations) {
                String key = annotation.value().trim().toLowerCase();
                registry.put(key, strategy);
                log.debugf("Registered filter for '%s': %s", key, cls.getSimpleName());
            }
        }
        log.infof("StrategyRegistry: %d filter(s) registered", registry.size());
    }

    /**
     * Finds the best {@link FilterStrategy} for the given command arguments.
     *
     * <p>Performs longest-prefix matching. For ["git", "status", "--short"],
     * tries "git status --short", then "git status", then "git".
     *
     * @param args the command arguments as passed to zap (e.g., ["git", "status"])
     * @return the matching strategy, or {@link PassthroughStrategy} if none found
     */
    public FilterStrategy lookup(String[] args) {
        if (args == null || args.length == 0) return passthrough;

        // Build candidate prefixes from longest to shortest
        // e.g. ["git", "status"] → ["git status", "git"]
        for (int len = args.length; len >= 1; len--) {
            String prefix = Arrays.stream(args, 0, len)
                .collect(Collectors.joining(" "))
                .toLowerCase()
                .trim();

            FilterStrategy strategy = registry.get(prefix);
            if (strategy != null) {
                log.debugf("Matched '%s' → %s", prefix, strategy.getClass().getSimpleName());
                return strategy;
            }
        }

        log.debugf("No match for '%s' — using passthrough",
            String.join(" ", args));
        return passthrough;
    }

    /** Returns true if a non-passthrough filter is registered for this command. */
    public boolean hasFilter(String[] args) {
        return lookup(args) != passthrough;
    }

    /** Returns a sorted list of all registered command prefixes. For diagnostics. */
    public List<String> registeredCommands() {
        return registry.keySet().stream().sorted().toList();
    }
}
```

---

## Step 8 — `CommandExecutor.java`

This is the most critical class in Phase 2. Read every comment carefully.

```java
package com.zapproxy.core;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Executes shell commands as child processes, capturing stdout and stderr
 * separately via concurrent virtual-thread readers.
 *
 * <h2>Deadlock Prevention</h2>
 * Java's {@link ProcessBuilder} will deadlock if you read stdout on the main
 * thread while the child process fills the stderr pipe buffer (or vice versa).
 * This class uses two Java 21 virtual threads — one per stream — started before
 * {@code process.waitFor()}, so both pipes drain concurrently.
 *
 * <h2>Infinite Loop Prevention</h2>
 * When Zap is installed as a shell hook, commands like "git status" are rewritten
 * to "zap git status". If Zap's internal execution of "git" somehow resolved to
 * Zap itself, it would fork infinitely. This class detects that case by comparing
 * the resolved binary name against the current process command, and throws rather
 * than looping.
 *
 * <h2>Timeout</h2>
 * All executions are bounded by a configurable timeout (default: 60 seconds).
 * Timed-out processes are forcibly destroyed and an exit code of -1 is returned.
 */
@ApplicationScoped
public class CommandExecutor {

    private static final Logger log = Logger.getLogger(CommandExecutor.class);

    /** Default maximum time to wait for a command to complete. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /** Maximum bytes to buffer from a single stream. 10 MB is generous for CLI output. */
    private static final int MAX_STREAM_BYTES = 10 * 1024 * 1024;

    /**
     * Executes {@code args} as a child process and returns its captured output.
     *
     * @param args    the command and its arguments (e.g., ["git", "status", "--short"])
     * @param timeout maximum time to wait; use {@link #DEFAULT_TIMEOUT} if unsure
     * @return the execution result; never null
     * @throws IOException          if the process cannot be started (binary not found, etc.)
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws IllegalStateException if the command would create an infinite zap→zap loop
     */
    public ExecutionResult execute(List<String> args, Duration timeout)
            throws IOException, InterruptedException {

        if (args == null || args.isEmpty()) {
            throw new IllegalArgumentException("args must not be null or empty");
        }

        guardAgainstInfiniteLoop(args);

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(Path.of("").toAbsolutePath().toFile());
        pb.redirectErrorStream(false); // MUST be false — we read stdout/stderr separately

        log.debugf("Executing: %s", String.join(" ", args));

        long startMs = System.currentTimeMillis();
        Process process = pb.start();

        // Start two virtual threads to drain both streams concurrently.
        // This prevents the deadlock that occurs when one pipe's OS buffer fills
        // while we're blocked reading the other.
        var stdoutCapture = new StreamCapture();
        var stderrCapture = new StreamCapture();

        Thread stdoutThread = Thread.ofVirtual()
            .name("zap-stdout")
            .start(() -> stdoutCapture.drain(process.getInputStream()));
        Thread stderrThread = Thread.ofVirtual()
            .name("zap-stderr")
            .start(() -> stderrCapture.drain(process.getErrorStream()));

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);

        if (!finished) {
            process.destroyForcibly();
            stdoutThread.interrupt();
            stderrThread.interrupt();
            long elapsed = System.currentTimeMillis() - startMs;
            log.warnf("Command '%s' timed out after %dms", String.join(" ", args), elapsed);
            return new ExecutionResult(
                -1,
                stdoutCapture.toString(),
                String.format("zap: command timed out after %ds", timeout.toSeconds()),
                elapsed
            );
        }

        // Wait for stream threads to finish draining (they will, since the process exited)
        stdoutThread.join(5_000);
        stderrThread.join(5_000);

        long durationMs = System.currentTimeMillis() - startMs;
        int exitCode = process.exitValue();

        log.debugf("Completed in %dms, exit=%d, stdout=%d bytes, stderr=%d bytes",
            durationMs, exitCode,
            stdoutCapture.size(), stderrCapture.size());

        return new ExecutionResult(
            exitCode,
            stdoutCapture.toString(),
            stderrCapture.toString(),
            durationMs
        );
    }

    /**
     * Convenience overload with the default 60-second timeout.
     */
    public ExecutionResult execute(List<String> args) throws IOException, InterruptedException {
        return execute(args, DEFAULT_TIMEOUT);
    }

    /**
     * Convenience overload accepting a varargs array.
     */
    public ExecutionResult execute(String... args) throws IOException, InterruptedException {
        return execute(Arrays.asList(args), DEFAULT_TIMEOUT);
    }

    // ── private ──────────────────────────────────────────────────────────────

    /**
     * Guards against Zap executing itself, which would cause an infinite fork loop
     * when installed as a shell hook.
     *
     * <p>Compares the first token of {@code args} against the current process's
     * executable name. If they match (e.g., both are "zap"), throws to prevent
     * the loop.
     */
    private void guardAgainstInfiniteLoop(List<String> args) {
        String command = args.get(0).toLowerCase();
        if (!"zap".equals(command)) return; // Fast path: most commands are not "zap"

        Optional<String> currentCmd = ProcessHandle.current().info().command();
        currentCmd.ifPresent(path -> {
            String binaryName = Path.of(path).getFileName().toString().toLowerCase();
            if (binaryName.equals("zap") || binaryName.equals("zap-runner")) {
                throw new IllegalStateException(
                    "zap: refusing to execute 'zap' as a subprocess — this would loop infinitely. " +
                    "Check your hook configuration and ensure 'zap' is not in the command path."
                );
            }
        });
    }

    /**
     * Thread-safe output buffer for capturing a single process stream.
     * Enforces {@link #MAX_STREAM_BYTES} to prevent OOM on unexpectedly large output.
     */
    private static final class StreamCapture {

        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
        private volatile boolean truncated = false;

        void drain(InputStream in) {
            try {
                byte[] chunk = new byte[8192];
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
                // Stream closed when process exits — normal
            }
        }

        int size() {
            return buf.size();
        }

        @Override
        public String toString() {
            String result = buf.toString(java.nio.charset.StandardCharsets.UTF_8);
            if (truncated) {
                result += "\n[zap: output truncated at 10MB]";
            }
            return result;
        }
    }
}
```

---

## Step 9 — `TeeWriter.java`

```java
package com.zapproxy.core;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Saves raw command output to a file when the tee system is active.
 *
 * <p>When a command fails (or when {@code tee.mode = "always"} is configured),
 * the AI may need to read the complete unfiltered output. Rather than re-executing
 * the command, Zap saves the raw output to a file and appends the file path to
 * the filtered output. The AI can then read that file directly.
 *
 * <p>Tee files are stored at:
 * {@code {dataDir}/tee/{command-hash}-{unix-timestamp}.txt}
 *
 * <p>The command hash is the first 8 hex characters of the SHA-256 of the
 * command string, giving stable filenames for the same command across runs.
 */
@ApplicationScoped
public class TeeWriter {

    private static final Logger log = Logger.getLogger(TeeWriter.class);

    @Inject
    PlatformDirs platformDirs;

    @Inject
    ConfigLoader configLoader;

    /**
     * Possibly saves raw output to a tee file, based on config and exit code.
     *
     * <p>If saving occurs, returns the file path so the caller can append it to
     * the filtered output. If saving does not occur, returns {@code null}.
     *
     * @param command the command string for file naming and logging
     * @param result  the raw execution result whose output may be saved
     * @return the absolute path of the saved tee file, or {@code null} if not saved
     */
    public Path maybeDump(String command, ExecutionResult result) {
        ZapConfig config = configLoader.load();
        ZapConfig.TeeConfig tee = config.tee();

        if (!tee.enabled()) return null;

        boolean shouldSave = switch (tee.mode()) {
            case ALWAYS   -> true;
            case FAILURES -> !result.succeeded();
            case NEVER    -> false;
        };

        if (!shouldSave) return null;

        return dump(command, result);
    }

    // ── private ──────────────────────────────────────────────────────────────

    private Path dump(String command, ExecutionResult result) {
        try {
            Path teeDir = platformDirs.getDataDir().resolve("tee");
            Files.createDirectories(teeDir);

            String hash = ProjectFingerprint.of(command).substring(0, 8);
            long ts = Instant.now().getEpochSecond();
            String filename = hash + "-" + ts + ".txt";
            Path file = teeDir.resolve(filename);

            String content = buildContent(command, result);
            Files.writeString(file, content, StandardCharsets.UTF_8);

            log.debugf("Tee file written: %s (%d bytes)", file, content.length());
            return file;

        } catch (IOException e) {
            log.warnf("Failed to write tee file for '%s': %s", command, e.getMessage());
            return null;
        }
    }

    private String buildContent(String command, ExecutionResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("# zap tee dump\n");
        sb.append("# command: ").append(command).append('\n');
        sb.append("# exit:    ").append(result.exitCode()).append('\n');
        sb.append("# elapsed: ").append(result.durationMs()).append("ms\n");
        sb.append("# timestamp: ").append(Instant.now()).append('\n');
        sb.append("#\n");

        if (!result.stdout().isBlank()) {
            sb.append("## stdout\n");
            sb.append(result.stdout());
            if (!result.stdout().endsWith("\n")) sb.append('\n');
        }

        if (!result.stderr().isBlank()) {
            sb.append("## stderr\n");
            sb.append(result.stderr());
            if (!result.stderr().endsWith("\n")) sb.append('\n');
        }

        return sb.toString();
    }
}
```

---

## Step 10 — `GitStatusFilter.java`

This is the proof-of-concept filter. It exercises the complete path from execution to
analytics. Every behaviour is specified precisely below.

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
 * Filters the output of {@code git status} into a compact summary line.
 *
 * <h2>Normal output</h2>
 * <pre>
 * [main] staged: 2 | modified: 1 | untracked: 3
 * </pre>
 *
 * <h2>Clean repo</h2>
 * <pre>
 * [main] ✓ clean
 * </pre>
 *
 * <h2>Ultra-compact mode (-u)</h2>
 * <pre>
 * [main] ↑S:2 M:1 ?:3
 * </pre>
 *
 * <h2>Verbose mode (-vv or higher)</h2>
 * Appends the full file list below the summary line.
 *
 * <h2>Non-zero exit code</h2>
 * Passes through stderr unchanged — git status exits non-zero when not in a
 * git repository (exit 128) or on other errors.
 */
@CommandFilter("git status")
@ApplicationScoped
public class GitStatusFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(GitStatusFilter.class);

    // Matches "On branch main" or "On branch feature/my-thing"
    private static final Pattern BRANCH_PATTERN =
        Pattern.compile("^On branch (.+)$", Pattern.MULTILINE);

    // Matches "HEAD detached at abc1234"
    private static final Pattern DETACHED_PATTERN =
        Pattern.compile("^HEAD detached at (.+)$", Pattern.MULTILINE);

    // Matches "nothing to commit, working tree clean" (and variants)
    private static final Pattern CLEAN_PATTERN =
        Pattern.compile("nothing to commit", Pattern.CASE_INSENSITIVE);

    @Override
    public FilterResult apply(
            String command,
            ExecutionResult result,
            ZapConfig config,
            int verbose,
            boolean ultraCompact) {

        // Non-zero exit → pass through; the AI needs to see the error
        // (e.g., exit 128: "fatal: not a git repository")
        if (!result.succeeded()) {
            log.debugf("git status exited %d — passing through stderr", result.exitCode());
            return FilterResult.passthrough(result.combined());
        }

        String stdout = result.stdout();
        String rawInput = stdout;
        List<String> lines = stdout.lines().toList();

        String branch = extractBranch(stdout);
        String prefix = branch != null ? "[" + branch + "] " : "";

        // Clean working tree
        if (CLEAN_PATTERN.matcher(stdout).find()) {
            String out = prefix + "✓ clean";
            return FilterResult.of(rawInput, out);
        }

        // Count file states
        int staged = 0;
        int modified = 0;
        int untracked = 0;
        List<String> changedFiles = new ArrayList<>();

        for (String line : lines) {
            if (line.length() < 2) continue;
            char indexStatus = line.charAt(0);   // staged column
            char workStatus  = line.charAt(1);   // unstaged column

            if (line.startsWith("??")) {
                untracked++;
                changedFiles.add("? " + line.substring(3).trim());
                continue;
            }

            // Staged changes: M, A, D, R, C in index column
            if (indexStatus != ' ' && indexStatus != '?') {
                staged++;
                changedFiles.add(indexStatus + " " + line.substring(3).trim());
            }

            // Unstaged changes: M, D in work-tree column
            if (workStatus != ' ' && workStatus != '?') {
                modified++;
                if (indexStatus == ' ') {
                    // Only add if not already added above
                    changedFiles.add("m " + line.substring(3).trim());
                }
            }
        }

        String summary = buildSummary(prefix, staged, modified, untracked, ultraCompact);

        // Verbose mode: append file list
        if (verbose >= 2 && !changedFiles.isEmpty()) {
            StringBuilder sb = new StringBuilder(summary);
            sb.append('\n');
            for (String file : changedFiles) {
                sb.append("  ").append(file).append('\n');
            }
            return FilterResult.of(rawInput, sb.toString().stripTrailing());
        }

        return FilterResult.of(rawInput, summary);
    }

    // ── private ──────────────────────────────────────────────────────────────

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
            // Single icon line: "[main] ↑S:2 M:1 ?:3"
            StringBuilder sb = new StringBuilder(prefix);
            if (staged > 0)   sb.append("↑S:").append(staged).append(' ');
            if (modified > 0) sb.append("M:").append(modified).append(' ');
            if (untracked > 0) sb.append("?:").append(untracked).append(' ');
            return sb.toString().stripTrailing();
        }

        // Normal: "[main] staged: 2 | modified: 1 | untracked: 3"
        List<String> parts = new ArrayList<>();
        if (staged > 0)   parts.add("staged: " + staged);
        if (modified > 0) parts.add("modified: " + modified);
        if (untracked > 0) parts.add("untracked: " + untracked);

        if (parts.isEmpty()) return prefix + "✓ clean"; // Fallback

        return prefix + String.join(" | ", parts);
    }
}
```

---

## Step 11 — Modify `ZapRootCommand.java`

Add passthrough dispatch to the root command. This is the main entry point when no
registered picocli subcommand matches. Open the existing file and ADD the following
fields and modify the `run()` method. Do not remove existing fields.

Add these imports:
```java
import com.zapproxy.core.*;
import jakarta.inject.Inject;
import picocli.CommandLine.Parameters;
import java.util.Arrays;
import java.util.List;
```

Add these injected fields (after existing `@Spec` field):
```java
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

@Parameters(index = "0..*", hidden = true,
    description = "Command and arguments to proxy (e.g., git status)")
String[] passthroughArgs;
```

Replace the `run()` method:
```java
@Override
public void run() {
    if (passthroughArgs == null || passthroughArgs.length == 0) {
        // No args: print help
        spec.commandLine().usage(System.out);
        return;
    }

    // Dispatch through filter pipeline
    try {
        List<String> argList = Arrays.asList(passthroughArgs);
        String commandStr = String.join(" ", passthroughArgs);

        // Execute the real command
        ExecutionResult result = executor.execute(argList);

        // Find and apply the appropriate filter
        FilterStrategy strategy = registry.lookup(passthroughArgs);
        ZapConfig config = configLoader.load();
        FilterResult filtered = strategy.apply(
            commandStr, result, config, verbosityLevel(), ultraCompact);

        // Record analytics
        tracking.insert(
            commandStr,
            ProjectFingerprint.ofCurrentDir(),
            System.getProperty("user.dir"),
            filtered.rawTokens(),
            filtered.outTokens(),
            result.durationMs()
        );

        // Maybe save raw output to tee file
        Path teePath = teeWriter.maybeDump(commandStr, result);

        // Print filtered output
        System.out.print(filtered.output());
        if (!filtered.output().endsWith("\n")) System.out.println();

        // Append tee path if saved
        if (teePath != null) {
            System.out.println("[raw output saved to: " + teePath + "]");
        }

        // CRITICAL: propagate the original exit code
        // picocli reads the return value of run() via CommandLine.execute()
        // We store it so main() can exit with it
        spec.commandLine().setExecutionResult(result.exitCode());

    } catch (IllegalStateException e) {
        // Infinite loop guard triggered
        System.err.println(e.getMessage());
        spec.commandLine().setExecutionResult(1);
    } catch (Exception e) {
        System.err.println("zap: error executing command: " + e.getMessage());
        spec.commandLine().setExecutionResult(1);
    }
}
```

Also update `ZapMain.java` to propagate the exit code correctly. Modify the `run()`
method so that after `execute(args)`, it checks the execution result:

```java
@Override
public int run(String... args) {
    CommandLine cmd = new CommandLine(new ZapRootCommand(), factory)
        .setExecutionExceptionHandler((ex, c, parseResult) -> {
            c.getErr().println("zap: error: " + ex.getMessage());
            return 1;
        })
        .setCaseInsensitiveEnumValuesAllowed(true);

    int exitCode = cmd.execute(args);

    // If the root command stored a passthrough exit code, use that instead
    Object result = cmd.getExecutionResult();
    if (result instanceof Integer passthroughExit) {
        return passthroughExit;
    }
    return exitCode;
}
```

---

## Step 12 — Create Test Fixture Files

These files simulate real `git status` output. Create them exactly as shown.

### `src/test/resources/fixtures/git-status/clean.txt`
```
On branch main
Your branch is up to date with 'origin/main'.

nothing to commit, working tree clean
```

### `src/test/resources/fixtures/git-status/modified.txt`
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

### `src/test/resources/fixtures/git-status/staged.txt`
```
On branch feature/new-filter
Your branch is ahead of 'origin/feature/new-filter' by 1 commit.

Changes to be committed:
  (use "git restore --staged <file>..." to unstage)
	new file:   src/main/java/com/example/NewFilter.java
	modified:   pom.xml

```

### `src/test/resources/fixtures/git-status/untracked.txt`
```
On branch main

Untracked files:
  (use "git add <file>..." to include in what will be committed)
	notes.txt
	scratch/
	TODO.md

nothing added to commit but untracked files present (use "git add" to track)
```

### `src/test/resources/fixtures/git-status/mixed.txt`
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

### `src/test/resources/fixtures/git-status/detached-head.txt`
```
HEAD detached at abc1234f
nothing to commit, working tree clean
```

---

## Step 13 — Write Unit Tests

### `TokenCounterTest.java`

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

    @ParameterizedTest
    @CsvSource({
        "a,      1",
        "abcd,   1",
        "abcde,  2",
        "12345678, 2"
    })
    void countCeilingDivisionByFour(String input, int expected) {
        assertThat(TokenCounter.count(input)).isEqualTo(expected);
    }

    @Test
    void typicalGitStatusOutput_around600Tokens() {
        // A typical "git status" output is ~2400 chars → ~600 tokens
        String typical = "On branch main\n" +
            "Your branch is up to date with 'origin/main'.\n\n" +
            "Changes not staged for commit:\n" +
            "  modified:   src/main/java/com/example/Foo.java\n" +
            "  modified:   src/main/java/com/example/Bar.java\n" +
            "  modified:   src/main/java/com/example/Baz.java\n";
        int tokens = TokenCounter.count(typical);
        // Should be in a reasonable range for a short git status
        assertThat(tokens).isBetween(40, 200);
    }

    @Test
    void savingsPctIsCorrect() {
        String raw = "a".repeat(400);     // 100 tokens
        String filtered = "a".repeat(40); // 10 tokens
        assertThat(TokenCounter.savingsPct(raw, filtered)).isEqualTo(90);
    }

    @Test
    void savingsPctWithZeroRawIsZero() {
        assertThat(TokenCounter.savingsPct("", "anything")).isZero();
    }
}
```

### `ProjectFingerprintTest.java`

```java
package com.zapproxy.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectFingerprintTest {

    @Test
    void outputIs12HexCharacters() {
        String fp = ProjectFingerprint.of("/home/user/myproject");
        assertThat(fp).hasSize(12).matches("[0-9a-f]{12}");
    }

    @Test
    void samePathProducesSameFingerprint() {
        String a = ProjectFingerprint.of("/tmp/test");
        String b = ProjectFingerprint.of("/tmp/test");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentPathsProduceDifferentFingerprints() {
        String a = ProjectFingerprint.of("/home/alice/project");
        String b = ProjectFingerprint.of("/home/bob/project");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void nullPathReturnsZeroFingerprint() {
        assertThat(ProjectFingerprint.of(null)).isEqualTo("000000000000");
    }

    @Test
    void blankPathReturnsZeroFingerprint() {
        assertThat(ProjectFingerprint.of("  ")).isEqualTo("000000000000");
    }

    @Test
    void currentDirDoesNotThrow() {
        assertThat(ProjectFingerprint.ofCurrentDir())
            .isNotNull()
            .hasSize(12)
            .matches("[0-9a-f]{12}");
    }
}
```

### `CommandExecutorTest.java`

```java
package com.zapproxy.core;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class CommandExecutorTest {

    @Inject
    CommandExecutor executor;

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void echoCommandCapturesStdout() throws Exception {
        ExecutionResult result = executor.execute("echo", "hello zap");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout().trim()).isEqualTo("hello zap");
        assertThat(result.stderr()).isBlank();
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void nonZeroExitCodeIsPreserved() throws Exception {
        // "false" is a POSIX command that always exits 1
        ExecutionResult result = executor.execute("false");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.succeeded()).isFalse();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void stderrIsCapturedSeparately() throws Exception {
        // Write to stderr only
        ExecutionResult result = executor.execute("sh", "-c", "echo err >&2");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isBlank();
        assertThat(result.stderr().trim()).isEqualTo("err");
        assertThat(result.hasStderr()).isTrue();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void bothStreamsAreCapturedConcurrently() throws Exception {
        // Write to both stdout and stderr — tests deadlock prevention
        ExecutionResult result = executor.execute(
            "sh", "-c", "echo out; echo err >&2");
        assertThat(result.stdout().trim()).isEqualTo("out");
        assertThat(result.stderr().trim()).isEqualTo("err");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void durationMsIsPositive() throws Exception {
        ExecutionResult result = executor.execute("echo", "timing");
        assertThat(result.durationMs()).isPositive();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void timeoutKillsProcess() throws Exception {
        // "sleep 10" should be killed after 100ms
        ExecutionResult result = executor.execute(
            java.util.List.of("sleep", "10"), Duration.ofMillis(100));
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.stderr()).contains("timed out");
    }

    @Test
    void zapSelfInvocationThrows() {
        // The guard against infinite loops
        assertThatThrownBy(() -> executor.execute("zap", "--version"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("loop");
    }
}
```

### `StrategyRegistryTest.java`

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
    void registryHasAtLeastOneFilter() {
        // GitStatusFilter is registered in Phase 2
        assertThat(registry.registeredCommands()).isNotEmpty();
    }

    @Test
    void gitStatusIsRegistered() {
        assertThat(registry.registeredCommands()).contains("git status");
    }

    @Test
    void lookupGitStatusReturnsGitStatusFilter() {
        FilterStrategy strategy = registry.lookup(new String[]{"git", "status"});
        assertThat(strategy).isNotInstanceOf(PassthroughStrategy.class);
        assertThat(strategy.getClass().getSimpleName()).isEqualTo("GitStatusFilter");
    }

    @Test
    void lookupGitStatusWithExtraFlagsStillMatches() {
        // "git status --short" should match "git status" prefix
        FilterStrategy strategy = registry.lookup(new String[]{"git", "status", "--short"});
        assertThat(strategy.getClass().getSimpleName()).isEqualTo("GitStatusFilter");
    }

    @Test
    void unknownCommandReturnsPassthrough() {
        FilterStrategy strategy = registry.lookup(new String[]{"someunknowncommand", "--flag"});
        assertThat(strategy).isInstanceOf(PassthroughStrategy.class);
    }

    @Test
    void emptyArgsReturnsPassthrough() {
        assertThat(registry.lookup(new String[]{}))
            .isInstanceOf(PassthroughStrategy.class);
    }

    @Test
    void nullArgsReturnsPassthrough() {
        assertThat(registry.lookup(null))
            .isInstanceOf(PassthroughStrategy.class);
    }

    @Test
    void hasFilterReturnsTrueForGitStatus() {
        assertThat(registry.hasFilter(new String[]{"git", "status"})).isTrue();
    }

    @Test
    void hasFilterReturnsFalseForUnknown() {
        assertThat(registry.hasFilter(new String[]{"notacommand"})).isFalse();
    }
}
```

### `TeeWriterTest.java`

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
    void doesNotSaveWhenCommandSucceeds_defaultConfig() {
        // Default config: tee.mode = FAILURES; exit 0 → no save
        ExecutionResult success = new ExecutionResult(0, "output", "", 10L);
        Path path = teeWriter.maybeDump("git status", success);
        assertThat(path).isNull();
    }

    @Test
    void savesWhenCommandFails_defaultConfig() throws Exception {
        // Default config: tee.mode = FAILURES; exit non-zero → save
        ExecutionResult failure = new ExecutionResult(
            128, "", "fatal: not a git repository", 5L);
        Path path = teeWriter.maybeDump("git status", failure);

        assertThat(path).isNotNull();
        assertThat(Files.exists(path)).isTrue();
        String content = Files.readString(path);
        assertThat(content).contains("# zap tee dump");
        assertThat(content).contains("fatal: not a git repository");
        assertThat(content).contains("exit:    128");

        // Cleanup
        Files.deleteIfExists(path);
    }

    @Test
    void teeFileHasCorrectStructure() throws Exception {
        ExecutionResult result = new ExecutionResult(1, "some stdout", "some stderr", 42L);
        Path path = teeWriter.maybeDump("test command", result);

        assertThat(path).isNotNull();
        String content = Files.readString(path);

        assertThat(content).contains("# command: test command");
        assertThat(content).contains("## stdout");
        assertThat(content).contains("some stdout");
        assertThat(content).contains("## stderr");
        assertThat(content).contains("some stderr");

        Files.deleteIfExists(path);
    }
}
```

### `GitStatusFilterTest.java`

```java
package com.zapproxy.filter.git;

import com.zapproxy.core.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitStatusFilterTest {

    private GitStatusFilter filter;
    private ZapConfig config;

    @BeforeEach
    void setUp() {
        filter = new GitStatusFilter();
        config = ZapConfig.defaults();
    }

    // ── fixture helpers ───────────────────────────────────────────────────────

    private String fixture(String name) throws IOException, URISyntaxException {
        var url = getClass().getResource("/fixtures/git-status/" + name + ".txt");
        assertThat(url)
            .as("Fixture file 'fixtures/git-status/%s.txt' not found on classpath", name)
            .isNotNull();
        return Files.readString(Path.of(url.toURI()));
    }

    private FilterResult applyFixture(String name) throws Exception {
        String raw = fixture(name);
        ExecutionResult result = new ExecutionResult(0, raw, "", 10L);
        return filter.apply("git status", result, config, 0, false);
    }

    // ── clean repo ────────────────────────────────────────────────────────────

    @Test
    void cleanRepo_emitsCleanWithBranch() throws Exception {
        FilterResult r = applyFixture("clean");
        assertThat(r.output()).isEqualTo("[main] ✓ clean");
        assertThat(r.wasFiltered()).isTrue();
    }

    @Test
    void cleanRepo_tokenSavingsArePositive() throws Exception {
        FilterResult r = applyFixture("clean");
        assertThat(r.rawTokens()).isGreaterThan(r.outTokens());
        assertThat(r.savingsPct()).isGreaterThan(0);
    }

    // ── modified files ────────────────────────────────────────────────────────

    @Test
    void modifiedFiles_showsModifiedCount() throws Exception {
        FilterResult r = applyFixture("modified");
        assertThat(r.output())
            .contains("modified: 2")
            .contains("[main]");
    }

    @Test
    void modifiedFiles_doesNotShowZeroCounts() throws Exception {
        FilterResult r = applyFixture("modified");
        assertThat(r.output()).doesNotContain("staged:");
        assertThat(r.output()).doesNotContain("untracked:");
    }

    // ── staged files ──────────────────────────────────────────────────────────

    @Test
    void stagedFiles_showsStagedCount() throws Exception {
        FilterResult r = applyFixture("staged");
        assertThat(r.output())
            .contains("staged: 2")
            .contains("[feature/new-filter]");
    }

    // ── untracked files ───────────────────────────────────────────────────────

    @Test
    void untrackedFiles_showsUntrackedCount() throws Exception {
        FilterResult r = applyFixture("untracked");
        assertThat(r.output()).contains("untracked: 3");
        assertThat(r.output()).doesNotContain("staged:");
        assertThat(r.output()).doesNotContain("modified:");
    }

    // ── mixed state ───────────────────────────────────────────────────────────

    @Test
    void mixedState_showsAllThreeCountsPresent() throws Exception {
        FilterResult r = applyFixture("mixed");
        assertThat(r.output())
            .contains("staged: 1")
            .contains("modified: 2")
            .contains("untracked: 2")
            .contains("[develop]");
    }

    @Test
    void mixedState_separatedWithPipes() throws Exception {
        FilterResult r = applyFixture("mixed");
        assertThat(r.output()).contains("|");
    }

    // ── detached HEAD ─────────────────────────────────────────────────────────

    @Test
    void detachedHead_showsDetachedPrefix() throws Exception {
        FilterResult r = applyFixture("detached-head");
        assertThat(r.output()).contains("[detached@abc1234f]");
        assertThat(r.output()).contains("✓ clean");
    }

    // ── non-zero exit code ────────────────────────────────────────────────────

    @Test
    void nonZeroExit_passesStderrThrough() {
        ExecutionResult failure = new ExecutionResult(
            128, "", "fatal: not a git repository (or any of the parent directories): .git", 3L);
        FilterResult r = filter.apply("git status", failure, config, 0, false);
        assertThat(r.wasFiltered()).isFalse();
        assertThat(r.output()).contains("fatal: not a git repository");
    }

    // ── ultra-compact mode ────────────────────────────────────────────────────

    @Test
    void ultraCompact_mixedState_singleIconLine() throws Exception {
        String raw = fixture("mixed");
        ExecutionResult result = new ExecutionResult(0, raw, "", 10L);
        FilterResult r = filter.apply("git status", result, config, 0, true);
        // Should be a single compact line without "staged:" / "modified:" words
        assertThat(r.output()).doesNotContain("staged:");
        assertThat(r.output()).doesNotContain("modified:");
        assertThat(r.output()).contains("↑S:").or().contains("M:").or().contains("?:");
        assertThat(r.output().lines().count()).isEqualTo(1L);
    }

    // ── verbose mode ──────────────────────────────────────────────────────────

    @Test
    void verboseMode_appendsFileList() throws Exception {
        String raw = fixture("mixed");
        ExecutionResult result = new ExecutionResult(0, raw, "", 10L);
        FilterResult r = filter.apply("git status", result, config, 2, false);
        // Verbose >= 2 should include file names
        assertThat(r.output().lines().count()).isGreaterThan(1L);
    }

    // ── token savings ─────────────────────────────────────────────────────────

    @Test
    void allFixtures_savingsArePositive() throws Exception {
        for (String name : List.of("clean", "modified", "staged", "untracked", "mixed")) {
            FilterResult r = applyFixture(name);
            assertThat(r.savingsPct())
                .as("Expected positive savings for fixture '%s'", name)
                .isPositive();
        }
    }
}
```

Add the missing import at the top of `GitStatusFilterTest.java`:
```java
import java.util.List;
```

---

## Step 14 — Update GraalVM Reflect Config

Open `src/main/resources/META-INF/native-image/reflect-config.json` and ADD the
following entries to the existing array. Do not remove existing entries.

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
    "name": "com.zapproxy.filter.git.GitStatusFilter",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  },
  {
    "name": "com.zapproxy.annotation.CommandFilter",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true
  }
```

---

## Step 15 — Run the Full Verification Suite

Run every command in order. Every single one must pass before Phase 2 is declared done.

### 15.1 Compile and run all tests
```bash
mvn clean verify
```
Expected: `BUILD SUCCESS`. Zero test failures. The new test classes must appear in the
Surefire output: `CommandExecutorTest`, `TokenCounterTest`, `ProjectFingerprintTest`,
`TeeWriterTest`, `StrategyRegistryTest`, `GitStatusFilterTest`.

### 15.2 Count tests — must have grown from Phase 1
```bash
mvn test 2>&1 | grep "Tests run" | awk -F',' '{sum += $1} END {print "Total tests:", sum}' | sed 's/Tests run: //'
```
Expected: at least 35 total test methods across all test classes (20 from Phase 1 + ~20
new from Phase 2).

### 15.3 Native image build
```bash
mvn package -Pnative -DskipTests
```
Expected: `BUILD SUCCESS`. No fallback warning. Binary at `target/zap-runner`.

### 15.4 Smoke test on real git repo
Run these from inside a git repository (the project itself qualifies):
```bash
# Normal mode
./target/zap-runner git status

# Ultra-compact
./target/zap-runner -u git status

# Verbose (shows file list)
./target/zap-runner -vv git status

# Passthrough — unknown command, passes through unchanged
./target/zap-runner echo "hello from zap"
```

Expected outputs:
- Normal: `[<branch>] staged: N | modified: N | untracked: N` or `[<branch>] ✓ clean`
- Ultra-compact: `[<branch>] ↑S:N M:N ?:N` (single line, icons only)
- Verbose: summary line + indented file list
- Echo passthrough: `hello from zap`

### 15.5 Exit code propagation
```bash
# In a non-git directory
cd /tmp && ./path/to/zap-runner git status; echo "Exit code: $?"
```
Expected: Exit code must be `128` (git's exit code for "not a git repository"),
not `0` and not `1`.

### 15.6 Analytics database receives a row
```bash
./target/zap-runner git status

# Check the database (requires sqlite3 CLI)
sqlite3 ~/.local/share/zap/zap.db \
  "SELECT command, raw_tokens, out_tokens, exec_ms FROM commands ORDER BY ts DESC LIMIT 1;"
```
Expected: a row showing `git status`, non-zero `raw_tokens`, smaller `out_tokens`,
positive `exec_ms`.

### 15.7 Verify no new Quarkus banner
```bash
./target/zap-runner --version 2>&1 | grep -i quarkus
```
Expected: no output.

### 15.8 Startup time still under 100ms
```bash
for i in {1..10}; do { time ./target/zap-runner --version; } 2>&1 | grep real; done
```
Expected: all under `0m0.100s`.

### 15.9 File checklist
```bash
find src -name "*.java" | sort
```
Must include ALL of:
```
src/main/java/com/zapproxy/annotation/CommandFilter.java
src/main/java/com/zapproxy/annotation/CommandFilters.java
src/main/java/com/zapproxy/core/CommandExecutor.java
src/main/java/com/zapproxy/core/ExecutionResult.java
src/main/java/com/zapproxy/core/FilterResult.java
src/main/java/com/zapproxy/core/FilterStrategy.java
src/main/java/com/zapproxy/core/PassthroughStrategy.java
src/main/java/com/zapproxy/core/ProjectFingerprint.java
src/main/java/com/zapproxy/core/StrategyRegistry.java
src/main/java/com/zapproxy/core/TeeWriter.java
src/main/java/com/zapproxy/core/TokenCounter.java
src/main/java/com/zapproxy/filter/git/GitStatusFilter.java
src/test/java/com/zapproxy/core/CommandExecutorTest.java
src/test/java/com/zapproxy/core/ProjectFingerprintTest.java
src/test/java/com/zapproxy/core/StrategyRegistryTest.java
src/test/java/com/zapproxy/core/TeeWriterTest.java
src/test/java/com/zapproxy/core/TokenCounterTest.java
src/test/java/com/zapproxy/filter/git/GitStatusFilterTest.java
```

---

## Phase 2 Sign-Off

Phase 2 is complete when ALL of the following are simultaneously true:

| # | Criterion | Pass condition |
|---|---|---|
| 1 | `mvn verify` exits 0 | `echo $?` → `0` |
| 2 | All Phase 1 tests still pass | No regressions |
| 3 | All Phase 2 tests pass | 0 failures, 0 errors |
| 4 | Total test count ≥ 35 | Count via grep |
| 5 | Native image builds without fallback | No fallback warning |
| 6 | `zap git status` on modified repo shows summary | Manual smoke test |
| 7 | `zap git status` on clean repo shows `✓ clean` | Manual smoke test |
| 8 | `zap echo hello` passes through unchanged | Manual smoke test |
| 9 | Exit code propagation works (exit 128 in /tmp) | `echo $?` after test |
| 10 | SQLite row inserted after `zap git status` | sqlite3 query |
| 11 | Cold start still under 100ms | Benchmark loop |
| 12 | All 18 new files in Step 15.9 checklist exist | `find` command |

When all 12 criteria pass, respond with:

```
PHASE 2 COMPLETE
────────────────
Tests:        [N passing / 0 failing]
Native build: [PASS / no fallback]
Cold start:   [Xms median]
Filters:      [1 registered: git status]
Analytics:    [PASS — rows inserted to SQLite]

Ready for Phase 3: All 42 Command Filter Modules.
```

---

## Common Failure Modes

**`StrategyRegistry` has 0 registered filters after startup**

Root cause: CDI is not discovering the `@CommandFilter` annotated bean. In Quarkus,
CDI beans must be in packages scanned by Quarkus. Add a `beans.xml` to
`src/main/resources/META-INF/beans.xml` with:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="https://jakarta.ee/xml/ns/jakartaee"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/beans_3_0.xsd"
       version="3.0"
       bean-discovery-mode="all">
</beans>
```

Also verify that `GitStatusFilter` has BOTH `@ApplicationScoped` AND `@CommandFilter`
annotations. Missing either one will prevent registration.

---

**`@PostConstruct build()` in StrategyRegistry logs "0 filters registered"**

Root cause: The CDI proxy class name check for `_Subclass` or `$Proxy` may not match
the naming convention Quarkus uses in your version. Fix: instead of inspecting the class
name, use `Instance.getHandle()` to get the `Bean<?>` and call
`bean.getBeanClass()` which always returns the actual implementation class regardless
of proxying:

```java
for (var handle : strategies.handles()) {
    Class<?> cls = handle.getBean().getBeanClass();
    CommandFilter[] annotations = cls.getAnnotationsByType(CommandFilter.class);
    FilterStrategy strategy = handle.get();
    // ... register
}
```

---

**Exit code is always 0 even when the proxied command fails**

Root cause: `spec.commandLine().setExecutionResult(result.exitCode())` stores the value
but `ZapMain.run()` doesn't read it correctly. Verify that `ZapMain.run()` checks
`cmd.getExecutionResult()` after `cmd.execute(args)` and casts it to `Integer`. If the
cast fails (result is a `CommandLine.ParseResult` or null), fall through to the picocli
exit code.

---

**`CommandExecutorTest.zapSelfInvocationThrows` fails because guard doesn't trigger**

Root cause: In the test environment, `ProcessHandle.current().info().command()` may
return the full path to the JVM (`java` binary), not `zap`. The guard condition
comparing against "zap" won't fire.

The test itself tests that the guard throws — but in the JVM test mode, the current
process is the JVM runner, not `zap-runner`. This test will only reliably fire in the
native binary. For JVM tests, mock `ProcessHandle` or accept that this specific test
only passes in native mode. Mark it with `@EnabledIfSystemProperty(named = "native",
matches = "true")` for the CI native test run.

---

*Phase 2 prompt complete. Next: Phase 3 — All 42 Command Filter Modules.*
