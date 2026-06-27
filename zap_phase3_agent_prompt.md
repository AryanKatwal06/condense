# Zap Java Port — Phase 3: All 42 Command Filter Modules

> You are a senior Java engineer implementing Phase 3 of the Zap Java + GraalVM port.
> Phases 1 and 2 are complete: the project builds to native, `zap git status` works
> end-to-end, the filter framework (FilterStrategy, StrategyRegistry, CommandExecutor,
> TeeWriter, TrackingRepository) is fully operational. Phase 3 adds all remaining
> command filters. When this phase is done, Zap handles 42 commands. Read every
> section before writing code.

---

## Phase 2 Completion Contract (Verify First)

```bash
mvn verify                                          # must be BUILD SUCCESS, 0 failures
./target/zap-runner git status                      # must print compact summary
./target/zap-runner echo hello                      # must pass through unchanged
sqlite3 ~/.local/share/zap/zap.db \
  "SELECT COUNT(*) FROM commands;"                  # must return > 0
grep -r "@CommandFilter" src/main/java/ | wc -l    # must show at least 1
```

If any fail, fix Phase 2 first. Do not start Phase 3.

---

## Architecture Reminder

Adding a new filter in Phase 3 always follows the same four-step pattern:

1. Create `src/main/java/com/zapproxy/filter/{group}/{Name}Filter.java`
   — implements `FilterStrategy`, annotated `@CommandFilter("cmd prefix")` + `@ApplicationScoped`
2. Create fixture file(s) in `src/test/resources/fixtures/{cmd-name}/`
3. Create `src/test/java/com/zapproxy/filter/{group}/{Name}FilterTest.java`
4. Add the new class to `reflect-config.json`

The `StrategyRegistry` discovers the new filter automatically via CDI — no other
files need editing. This is the extensibility contract from Phase 2.

---

## Step 1 — Shared Strategy Utility Classes

Before implementing any filter, create the six shared strategy utility classes that
multiple filters will call. These are pure utility classes (no CDI), stateless, and
tested independently.

### 1.1 `DeduplicationStrategy.java`

```java
package com.zapproxy.filter.strategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Collapses consecutive and near-consecutive repeated lines into a single line
 * with a repetition count suffix.
 *
 * <p>Example: five identical "ERROR: connection refused" lines become:
 * <pre>ERROR: connection refused (×5)</pre>
 *
 * <p>"Near-consecutive" means within a configurable window (default 50 lines).
 * This handles log patterns where the same error repeats but is separated by
 * stack trace lines.
 */
public final class DeduplicationStrategy {

    private DeduplicationStrategy() {}

    /**
     * Deduplicates lines within a sliding window of {@code windowSize} lines.
     *
     * @param lines      the input lines
     * @param windowSize how far back to look for a matching line (default: 50)
     * @return deduplicated lines; lines appearing N times become "line (×N)"
     */
    public static List<String> deduplicate(List<String> lines, int windowSize) {
        if (lines == null || lines.isEmpty()) return List.of();

        List<String> result = new ArrayList<>(lines.size());
        // track: line content → index in result list (within window)
        java.util.LinkedHashMap<String, int[]> seen =
            new java.util.LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, int[]> eldest) {
                    return size() > windowSize;
                }
            };

        for (String line : lines) {
            String key = line.trim();
            if (seen.containsKey(key)) {
                int[] entry = seen.get(key);
                int resultIdx = entry[0];
                int count = ++entry[1];
                // Update the result line in-place with new count
                String base = result.get(resultIdx);
                // Strip old "(×N)" suffix if present
                String stripped = base.replaceAll("\\s+\\(×\\d+\\)$", "").stripTrailing();
                result.set(resultIdx, stripped + " (×" + count + ")");
            } else {
                int[] entry = {result.size(), 1};
                seen.put(key, entry);
                result.add(line);
            }
        }
        return result;
    }

    /** Deduplicates with the default window of 50 lines. */
    public static List<String> deduplicate(List<String> lines) {
        return deduplicate(lines, 50);
    }
}
```

### 1.2 `GroupingStrategy.java`

```java
package com.zapproxy.filter.strategy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Groups lines by an extracted key and counts occurrences per key.
 *
 * <p>Used by lint filters (ESLint, TSC, ruff, clippy) to turn hundreds of
 * individual error lines into a compact frequency map:
 *
 * <pre>
 * no-unused-vars      : 14
 * no-console          :  8
 * prefer-const        :  3
 * (other)             :  2
 * </pre>
 */
public final class GroupingStrategy {

    private GroupingStrategy() {}

    /**
     * Groups {@code lines} by the string captured in group 1 of {@code keyPattern}.
     * Lines not matching the pattern are counted under the key {@code "(other)"}
     * if {@code includeOther} is true, or silently discarded otherwise.
     *
     * @param lines        input lines
     * @param keyPattern   regex with one capture group that extracts the group key
     * @param includeOther whether non-matching lines count toward "(other)"
     * @return map of key → count, sorted by count descending, then key ascending
     */
    public static Map<String, Integer> group(
            List<String> lines, Pattern keyPattern, boolean includeOther) {

        Map<String, Integer> counts = new LinkedHashMap<>();
        int other = 0;

        for (String line : lines) {
            var m = keyPattern.matcher(line);
            if (m.find()) {
                String key = m.group(1).trim();
                counts.merge(key, 1, Integer::sum);
            } else if (includeOther) {
                other++;
            }
        }

        if (includeOther && other > 0) counts.put("(other)", other);

        // Sort by count descending, then key ascending for stability
        return counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (a, b) -> a,
                LinkedHashMap::new));
    }

    /**
     * Formats a frequency map as a compact indented block:
     * <pre>
     *   rule-name           : 14
     *   another-rule        :  3
     * </pre>
     */
    public static String format(Map<String, Integer> groups) {
        if (groups.isEmpty()) return "";
        int maxKeyLen = groups.keySet().stream()
            .mapToInt(String::length).max().orElse(0);
        int maxCount = groups.values().stream()
            .mapToInt(Integer::intValue).max().orElse(0);
        int countWidth = String.valueOf(maxCount).length();

        StringBuilder sb = new StringBuilder();
        for (var entry : groups.entrySet()) {
            sb.append(String.format("  %-" + maxKeyLen + "s : %" + countWidth + "d%n",
                entry.getKey(), entry.getValue()));
        }
        return sb.toString().stripTrailing();
    }
}
```

### 1.3 `AnsiStripStrategy.java`

```java
package com.zapproxy.filter.strategy;

import java.util.regex.Pattern;

/**
 * Strips ANSI escape sequences and carriage-return progress lines from text.
 *
 * <p>Used by install/download filters (npm install, pip install, cargo install,
 * docker build) that produce animated progress output not suitable for AI context.
 */
public final class AnsiStripStrategy {

    private AnsiStripStrategy() {}

    /** Matches any ANSI CSI (Control Sequence Introducer) escape. */
    private static final Pattern ANSI_PATTERN =
        Pattern.compile("\u001B\\[[0-9;]*[mGKHFJABCDsu]");

    /** Matches lines overwritten via carriage return (progress bar updates). */
    private static final Pattern CR_LINE_PATTERN =
        Pattern.compile("[^\n]*\r(?!\n)");

    /**
     * Removes all ANSI escape codes and carriage-return progress bar lines.
     *
     * @param text raw terminal output
     * @return clean plain-text string
     */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) return "";
        // Remove CR-overwritten progress lines first
        String noCr = CR_LINE_PATTERN.matcher(text).replaceAll("");
        // Then strip ANSI codes
        return ANSI_PATTERN.matcher(noCr).replaceAll("");
    }

    /**
     * Strips ANSI and then returns only the last non-blank line.
     * Useful for install commands where only the final status line matters.
     */
    public static String lastMeaningfulLine(String text) {
        String clean = strip(text);
        return clean.lines()
            .filter(l -> !l.isBlank())
            .reduce("", (a, b) -> b); // keep last
    }
}
```

### 1.4 `JsonStructureStrategy.java`

```java
package com.zapproxy.filter.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * Replaces JSON values with their type placeholders, producing a compact schema
 * skeleton that lets an AI understand the structure without reading the full data.
 *
 * <p>Example input (200 tokens):
 * <pre>{"user":{"name":"Alice","age":30,"roles":["admin","user"]}}</pre>
 *
 * <p>Example output (15 tokens):
 * <pre>{"user":{"name":"&lt;string&gt;","age":0,"roles":["&lt;string&gt;"]}}</pre>
 */
public final class JsonStructureStrategy {

    private JsonStructureStrategy() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_DEPTH = 6;

    /**
     * Parses {@code json} and returns a schema-skeleton string.
     * Returns the original text unchanged if parsing fails.
     *
     * @param json  raw JSON string
     * @return schema skeleton, or original text on parse failure
     */
    public static String skeleton(String json) {
        if (json == null || json.isBlank()) return json;
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode skeleton = skeletonNode(root, 0);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(skeleton);
        } catch (Exception e) {
            return json; // Not valid JSON — return unchanged
        }
    }

    private static JsonNode skeletonNode(JsonNode node, int depth) {
        if (depth >= MAX_DEPTH) return MAPPER.getNodeFactory().textNode("...");

        if (node.isObject()) {
            ObjectNode obj = MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                obj.set(field.getKey(), skeletonNode(field.getValue(), depth + 1));
            }
            return obj;
        }

        if (node.isArray()) {
            ArrayNode arr = MAPPER.createArrayNode();
            if (node.size() > 0) {
                // Show type of first element only, with count
                arr.add(skeletonNode(node.get(0), depth + 1));
                if (node.size() > 1) {
                    arr.add(MAPPER.getNodeFactory()
                        .textNode("... +" + (node.size() - 1) + " more"));
                }
            }
            return arr;
        }

        if (node.isTextual())  return MAPPER.getNodeFactory().textNode("<string>");
        if (node.isNumber())   return MAPPER.getNodeFactory().numberNode(0);
        if (node.isBoolean())  return MAPPER.getNodeFactory().booleanNode(false);
        if (node.isNull())     return MAPPER.getNodeFactory().nullNode();
        return node; // Unknown node type — keep as-is
    }
}
```

### 1.5 `TreeCompressionStrategy.java`

```java
package com.zapproxy.filter.strategy;

import java.util.*;

/**
 * Converts a flat list of file paths into a compact directory tree.
 *
 * <p>Directories with more than {@link #MAX_FILES_PER_DIR} files show a summary
 * line instead of every file, keeping the output compact for large repos.
 *
 * <p>Example (50 paths → 8 lines):
 * <pre>
 * src/
 *   main/
 *     java/com/example/    (12 files)
 *   test/
 *     java/com/example/
 *       FooTest.java
 *       BarTest.java
 * </pre>
 */
public final class TreeCompressionStrategy {

    private TreeCompressionStrategy() {}

    /** Directories with more files than this show a summary count. */
    public static final int MAX_FILES_PER_DIR = 8;

    /**
     * Builds a compressed tree string from a list of file paths.
     *
     * @param paths flat list of paths (relative or absolute)
     * @return multi-line indented tree; empty string if paths is empty
     */
    public static String compress(List<String> paths) {
        if (paths == null || paths.isEmpty()) return "";

        // Build a tree: dir → list of children (files or sub-dirs)
        Map<String, List<String>> tree = new TreeMap<>();
        for (String path : paths) {
            String normalised = path.trim().replace('\\', '/');
            if (normalised.isBlank()) continue;
            int lastSlash = normalised.lastIndexOf('/');
            String dir  = lastSlash >= 0 ? normalised.substring(0, lastSlash) : ".";
            String file = lastSlash >= 0 ? normalised.substring(lastSlash + 1) : normalised;
            tree.computeIfAbsent(dir, k -> new ArrayList<>()).add(file);
        }

        StringBuilder sb = new StringBuilder();
        // Collect unique top-level dirs
        Set<String> topDirs = new TreeSet<>();
        for (String dir : tree.keySet()) {
            String top = dir.contains("/") ? dir.substring(0, dir.indexOf('/')) : dir;
            topDirs.add(top);
        }

        for (String top : topDirs) {
            sb.append(top).append("/\n");
            renderDir(sb, tree, top, "  ");
        }

        return sb.toString().stripTrailing();
    }

    private static void renderDir(StringBuilder sb, Map<String, List<String>> tree,
                                   String dir, String indent) {
        List<String> files = tree.getOrDefault(dir, List.of());
        if (files.size() > MAX_FILES_PER_DIR) {
            sb.append(indent).append("(").append(files.size()).append(" files)\n");
        } else {
            for (String file : files) {
                sb.append(indent).append(file).append("\n");
            }
        }

        // Render sub-directories
        for (String subDir : new TreeSet<>(tree.keySet())) {
            if (subDir.startsWith(dir + "/") &&
                !subDir.substring(dir.length() + 1).contains("/")) {
                String subName = subDir.substring(dir.length() + 1);
                sb.append(indent).append(subName).append("/\n");
                renderDir(sb, tree, subDir, indent + "  ");
            }
        }
    }
}
```

### 1.6 `StateMachineStrategy.java`

```java
package com.zapproxy.filter.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Generic line-by-line state machine for structured command output.
 *
 * <p>Used by test runner filters (pytest, cargo test) that have well-defined
 * sections: header → collection → running → summary. Each state decides
 * whether to EMIT, COLLECT, or DISCARD the current line.
 *
 * <p>Usage:
 * <pre>{@code
 * var machine = new StateMachineStrategy.Builder()
 *     .state("HEADER",  line -> line.startsWith("==="), Action.DISCARD, "COLLECT")
 *     .state("COLLECT", line -> line.startsWith("FAILED"), Action.EMIT, "COLLECT")
 *     .build();
 * List<String> output = machine.process(lines);
 * }</pre>
 */
public final class StateMachineStrategy {

    public enum Action { EMIT, DISCARD, COLLECT }

    public record Transition(
        String fromState,
        Predicate<String> trigger,
        Action action,
        String nextState
    ) {}

    private final List<Transition> transitions;
    private final String initialState;

    private StateMachineStrategy(List<Transition> transitions, String initialState) {
        this.transitions = transitions;
        this.initialState = initialState;
    }

    /**
     * Processes lines through the state machine and returns the EMIT lines.
     */
    public List<String> process(List<String> lines) {
        List<String> output = new ArrayList<>();
        String state = initialState;

        for (String line : lines) {
            boolean transitioned = false;
            for (Transition t : transitions) {
                if (t.fromState().equals(state) && t.trigger().test(line)) {
                    if (t.action() == Action.EMIT) output.add(line);
                    state = t.nextState();
                    transitioned = true;
                    break;
                }
            }
            // Default action for current state (no matching trigger)
            if (!transitioned) {
                // Find default for current state (trigger = always-false sentinel)
                for (Transition t : transitions) {
                    if (t.fromState().equals(state + ":default")) {
                        if (t.action() == Action.EMIT) output.add(line);
                        break;
                    }
                }
            }
        }
        return output;
    }

    public static Builder builder(String initialState) {
        return new Builder(initialState);
    }

    public static final class Builder {
        private final List<Transition> transitions = new ArrayList<>();
        private final String initialState;

        public Builder(String initialState) {
            this.initialState = initialState;
        }

        /** On matching line in {@code fromState}: apply {@code action}, move to {@code nextState}. */
        public Builder on(String fromState, Pattern pattern, Action action, String nextState) {
            transitions.add(new Transition(fromState, line -> pattern.matcher(line).find(),
                action, nextState));
            return this;
        }

        /** Default action for all non-matching lines in {@code state}. */
        public Builder defaultAction(String state, Action action) {
            transitions.add(new Transition(state + ":default", line -> false, action, state));
            return this;
        }

        public StateMachineStrategy build() {
            return new StateMachineStrategy(transitions, initialState);
        }
    }
}
```

---

## Step 2 — Git Filters (Group A: Stats Extraction)

Create each file in `src/main/java/com/zapproxy/filter/git/`.

### `GitDiffFilter.java`

Parses `git diff --stat` style output. Extracts the summary line that looks like
`3 files changed, 45 insertions(+), 12 deletions(-)` and emits just that.
For plain `git diff` (no `--stat`), counts `+` lines and `-` lines in the patch.

```java
package com.zapproxy.filter.git;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandFilter("git diff")
@ApplicationScoped
public class GitDiffFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(GitDiffFilter.class);

    // Matches "3 files changed, 45 insertions(+), 12 deletions(-)"
    private static final Pattern STAT_SUMMARY =
        Pattern.compile("(\\d+) files? changed.*");

    // Matches diff stat file lines: " src/Foo.java | 12 ++"
    private static final Pattern STAT_FILE_LINE =
        Pattern.compile("^\\s+\\S.*\\|\\s*\\d+");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded() && result.stdout().isBlank()) {
            return FilterResult.passthrough(result.combined());
        }

        try {
            String stdout = result.stdout();
            String raw = stdout.isBlank() ? result.stderr() : stdout;

            // Look for --stat summary line
            Matcher m = STAT_SUMMARY.matcher(raw);
            if (m.find()) {
                String summary = m.group(0).trim();
                if (verbose >= 2) {
                    // Include per-file stats
                    StringBuilder sb = new StringBuilder(summary).append('\n');
                    raw.lines()
                        .filter(l -> STAT_FILE_LINE.matcher(l).find())
                        .forEach(l -> sb.append("  ").append(l.trim()).append('\n'));
                    return FilterResult.of(raw, sb.toString().stripTrailing());
                }
                return FilterResult.of(raw, summary);
            }

            // Plain diff — count patch lines
            long added   = raw.lines().filter(l -> l.startsWith("+") && !l.startsWith("+++")).count();
            long removed = raw.lines().filter(l -> l.startsWith("-") && !l.startsWith("---")).count();

            if (added == 0 && removed == 0) {
                return FilterResult.of(raw, "no changes");
            }

            String summary = "+" + added + " / -" + removed + " lines";
            return FilterResult.of(raw, summary);

        } catch (Exception e) {
            log.warnf("GitDiffFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result.stdout());
        }
    }
}
```

### `GitLogFilter.java`

```java
package com.zapproxy.filter.git;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@CommandFilter("git log")
@ApplicationScoped
public class GitLogFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(GitLogFilter.class);

    // "commit abc1234def456..." — extract first 8 chars
    private static final Pattern COMMIT_PATTERN =
        Pattern.compile("^commit ([0-9a-f]{8})[0-9a-f]*");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        try {
            String stdout = result.stdout();
            List<String> lines = stdout.lines().toList();
            List<String> commits = new ArrayList<>();

            String currentHash = null;
            for (String line : lines) {
                var m = COMMIT_PATTERN.matcher(line);
                if (m.find()) {
                    currentHash = m.group(1);
                } else if (currentHash != null && !line.isBlank()
                        && !line.startsWith("Author:")
                        && !line.startsWith("Date:")
                        && !line.startsWith("Merge:")) {
                    // First non-blank, non-header line after commit = message
                    commits.add(currentHash + " " + line.trim());
                    currentHash = null; // Reset until next commit line
                }
            }

            if (commits.isEmpty()) return FilterResult.of(stdout, "(no commits)");

            int limit = verbose >= 2 ? commits.size() : Math.min(10, commits.size());
            String out = String.join("\n", commits.subList(0, limit));
            if (!ultraCompact && commits.size() > limit) {
                out += "\n(+" + (commits.size() - limit) + " more)";
            }
            return FilterResult.of(stdout, out);

        } catch (Exception e) {
            log.warnf("GitLogFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result.stdout());
        }
    }
}
```

### `GitPushFilter.java`

```java
package com.zapproxy.filter.git;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandFilter("git push")
@ApplicationScoped
public class GitPushFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(GitPushFilter.class);

    private static final Pattern BRANCH_PATTERN =
        Pattern.compile("\\s+(\\S+)\\s+->\\s+(\\S+)");
    private static final Pattern UP_TO_DATE =
        Pattern.compile("Everything up-to-date", Pattern.CASE_INSENSITIVE);
    private static final Pattern REJECTED =
        Pattern.compile("\\[rejected\\]|error:|failed to push");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        String raw = result.combined();

        if (REJECTED.matcher(raw).find()) {
            // Push failed — return stderr so AI sees the rejection reason
            return FilterResult.passthrough(result.stderr().isBlank() ? raw : result.stderr());
        }

        if (UP_TO_DATE.matcher(raw).find()) {
            return FilterResult.of(raw, "✓ up-to-date (nothing pushed)");
        }

        Matcher m = BRANCH_PATTERN.matcher(raw);
        if (m.find()) {
            String dest = m.group(2).trim();
            return FilterResult.of(raw, "✓ pushed → " + dest);
        }

        // Fallback: just confirm success
        return result.succeeded()
            ? FilterResult.of(raw, "✓ pushed")
            : FilterResult.passthrough(raw);
    }
}
```

### `GitCommitFilter.java`

```java
package com.zapproxy.filter.git;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandFilter("git commit")
@ApplicationScoped
public class GitCommitFilter implements FilterStrategy {

    // "[main abc1234] commit message"
    private static final Pattern COMMIT_LINE =
        Pattern.compile("^\\[([^\\]]+)\\s+([0-9a-f]+)\\]\\s+(.+)$", Pattern.MULTILINE);

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout();
        Matcher m = COMMIT_LINE.matcher(raw);
        if (m.find()) {
            String branch  = m.group(1).trim();
            String hash    = m.group(2).substring(0, Math.min(8, m.group(2).length()));
            String message = m.group(3).trim();
            String out = ultraCompact
                ? "[" + branch + "] " + hash + " " + message
                : "✓ committed [" + branch + "] " + hash + " — " + message;
            return FilterResult.of(raw, out);
        }

        return FilterResult.of(raw, "✓ committed");
    }
}
```

### `GitAddFilter.java`

```java
package com.zapproxy.filter.git;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

@CommandFilter("git add")
@ApplicationScoped
public class GitAddFilter implements FilterStrategy {

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        // git add normally produces no output on success
        String raw = result.stdout();
        if (raw.isBlank()) return FilterResult.of("", "✓ staged");

        long fileCount = raw.lines().filter(l -> !l.isBlank()).count();
        return FilterResult.of(raw, "✓ staged " + fileCount + " file(s)");
    }
}
```

---

## Step 3 — Test Runner Filters (Group B: Failure Focus)

### `CargoTestFilter.java`

Handles `cargo test` output. Parses the text output looking for FAILED lines.
Returns only failure names + summary line. If all tests pass, returns compact success.

```java
package com.zapproxy.filter.cargo;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandFilter("cargo test")
@ApplicationScoped
public class CargoTestFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(CargoTestFilter.class);

    private static final Pattern FAILED_TEST =
        Pattern.compile("^test (.+) \\.\\.\\.\\s+FAILED", Pattern.MULTILINE);
    private static final Pattern TEST_RESULT =
        Pattern.compile("^test result: (ok|FAILED)\\. (\\d+) passed.*", Pattern.MULTILINE);
    private static final Pattern COMPILING =
        Pattern.compile("^\\s*Compiling ", Pattern.MULTILINE);

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        try {
            String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();

            List<String> failures = new ArrayList<>();
            Matcher fm = FAILED_TEST.matcher(raw);
            while (fm.find()) failures.add("  FAILED: " + fm.group(1).trim());

            Matcher rm = TEST_RESULT.matcher(raw);
            String resultLine = rm.find() ? rm.group(0).trim() : null;

            if (failures.isEmpty()) {
                // Check for compile error
                if (result.exitCode() != 0 && COMPILING.matcher(raw).find()) {
                    // Extract compiler error lines
                    List<String> errors = raw.lines()
                        .filter(l -> l.startsWith("error") || l.startsWith("  -->"))
                        .limit(10)
                        .toList();
                    String out = "cargo test: compile error\n" + String.join("\n", errors);
                    return FilterResult.of(raw, out);
                }
                String summary = resultLine != null ? resultLine : "✓ all tests passed";
                return FilterResult.of(raw, summary);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("cargo test: ").append(failures.size()).append(" failure(s)\n");
            failures.forEach(f -> sb.append(f).append('\n'));
            if (resultLine != null) sb.append(resultLine);

            return FilterResult.of(raw, sb.toString().stripTrailing());

        } catch (Exception e) {
            log.warnf("CargoTestFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result.stdout());
        }
    }
}
```

### `PytestFilter.java`

State-machine parser for pytest output.

```java
package com.zapproxy.filter.python;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@CommandFilter("pytest")
@ApplicationScoped
public class PytestFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(PytestFilter.class);

    private static final Pattern FAILED_LINE  = Pattern.compile("^FAILED\\s+");
    private static final Pattern ERROR_LINE   = Pattern.compile("^ERROR\\s+");
    private static final Pattern SUMMARY_LINE = Pattern.compile("^=+.*=+$");
    private static final Pattern SHORT_TEST_SUMMARY = Pattern.compile("short test summary");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        try {
            String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
            List<String> lines  = raw.lines().toList();
            List<String> output = new ArrayList<>();

            boolean inShortSummary = false;

            for (String line : lines) {
                if (SHORT_TEST_SUMMARY.matcher(line).find()) {
                    inShortSummary = true;
                    continue;
                }
                if (inShortSummary) {
                    if (FAILED_LINE.matcher(line).find() || ERROR_LINE.matcher(line).find()) {
                        output.add(line.trim());
                    } else if (SUMMARY_LINE.matcher(line).find()) {
                        // Final summary line (passed/failed count)
                        output.add(line.trim());
                        inShortSummary = false;
                    }
                } else if (SUMMARY_LINE.matcher(line).find()
                        && (line.contains("passed") || line.contains("failed")
                            || line.contains("error"))) {
                    // Final result line — always emit
                    output.add(line.trim());
                }
            }

            if (output.isEmpty()) {
                // No failures found
                String lastLine = lines.stream().filter(l -> !l.isBlank())
                    .reduce("", (a, b) -> b);
                return FilterResult.of(raw, lastLine.isBlank() ? "✓ all tests passed" : lastLine);
            }

            return FilterResult.of(raw, String.join("\n", output));

        } catch (Exception e) {
            log.warnf("PytestFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result.stdout());
        }
    }
}
```

### `GoTestFilter.java`

Parses `go test -json` NDJSON streaming output.

```java
package com.zapproxy.filter.golang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

@CommandFilter("go test")
@ApplicationScoped
public class GoTestFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(GoTestFilter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        try {
            String raw = result.stdout();
            List<String> failures = new ArrayList<>();
            int passed = 0, skipped = 0;

            for (String line : raw.lines().toList()) {
                if (line.isBlank() || !line.startsWith("{")) continue;
                try {
                    JsonNode node = MAPPER.readTree(line);
                    String action = node.path("Action").asText();
                    String test   = node.path("Test").asText();
                    switch (action) {
                        case "pass"   -> { if (!test.isBlank()) passed++; }
                        case "skip"   -> { if (!test.isBlank()) skipped++; }
                        case "fail"   -> {
                            if (!test.isBlank()) failures.add("  FAIL: " + test);
                        }
                        default -> {} // "run", "output", "pause", "cont" — ignore
                    }
                } catch (Exception ignored) {
                    // Line was not JSON (normal text output before -json flag)
                }
            }

            // Fallback: no JSON events — parse plain text
            if (passed == 0 && failures.isEmpty()) {
                return parsePlainGoTest(raw, result.succeeded());
            }

            StringBuilder sb = new StringBuilder();
            if (!failures.isEmpty()) {
                sb.append("go test: ").append(failures.size()).append(" failure(s)\n");
                failures.forEach(f -> sb.append(f).append('\n'));
            }
            sb.append("passed: ").append(passed);
            if (skipped > 0) sb.append(" | skipped: ").append(skipped);
            if (!failures.isEmpty()) sb.append(" | failed: ").append(failures.size());

            return FilterResult.of(raw, sb.toString().stripTrailing());

        } catch (Exception e) {
            log.warnf("GoTestFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result.stdout());
        }
    }

    private FilterResult parsePlainGoTest(String raw, boolean succeeded) {
        List<String> failures = raw.lines()
            .filter(l -> l.startsWith("--- FAIL:") || l.startsWith("FAIL"))
            .limit(20)
            .toList();
        String lastLine = raw.lines().filter(l -> !l.isBlank()).reduce("", (a, b) -> b);

        if (failures.isEmpty()) {
            return FilterResult.of(raw, succeeded ? "✓ ok" : lastLine);
        }
        return FilterResult.of(raw,
            "go test: " + failures.size() + " failure(s)\n" + String.join("\n", failures));
    }
}
```

### `JestFilter.java`

```java
package com.zapproxy.filter.node;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@CommandFilter("jest")
@ApplicationScoped
public class JestFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(JestFilter.class);

    private static final Pattern FAIL_SUITE  = Pattern.compile("^\\s*FAIL\\s+(.+)$");
    private static final Pattern PASS_SUITE  = Pattern.compile("^\\s*PASS\\s+");
    private static final Pattern SUMMARY     = Pattern.compile("^Tests:\\s+");
    private static final Pattern TEST_SUITES = Pattern.compile("^Test Suites:");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        try {
            String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
            List<String> failedSuites = new ArrayList<>();
            List<String> summaryLines = new ArrayList<>();

            for (String line : raw.lines().toList()) {
                var fm = FAIL_SUITE.matcher(line);
                if (fm.find()) { failedSuites.add("  FAIL: " + fm.group(1).trim()); continue; }
                if (SUMMARY.matcher(line).find() || TEST_SUITES.matcher(line).find()) {
                    summaryLines.add(line.trim());
                }
            }

            if (failedSuites.isEmpty() && result.succeeded()) {
                String summary = summaryLines.isEmpty() ? "✓ all tests passed"
                    : String.join("\n", summaryLines);
                return FilterResult.of(raw, summary);
            }

            StringBuilder sb = new StringBuilder();
            if (!failedSuites.isEmpty()) {
                sb.append("jest: ").append(failedSuites.size()).append(" suite(s) failed\n");
                failedSuites.forEach(l -> sb.append(l).append('\n'));
            }
            summaryLines.forEach(l -> sb.append(l).append('\n'));

            return FilterResult.of(raw, sb.toString().stripTrailing());

        } catch (Exception e) {
            log.warnf("JestFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result.stdout());
        }
    }
}
```

### `VitestFilter.java`

```java
package com.zapproxy.filter.node;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@CommandFilter("vitest")
@ApplicationScoped
public class VitestFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(VitestFilter.class);

    private static final Pattern FAIL_LINE    = Pattern.compile("×|✗|FAIL", Pattern.UNICODE_CASE);
    private static final Pattern SUMMARY_LINE = Pattern.compile("Tests\\s+\\d+");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        try {
            String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
            List<String> failures = new ArrayList<>();
            List<String> summary  = new ArrayList<>();

            for (String line : raw.lines().toList()) {
                if (FAIL_LINE.matcher(line).find()
                        && !line.contains("passed") && !line.isBlank()) {
                    failures.add("  " + line.trim());
                } else if (SUMMARY_LINE.matcher(line).find()) {
                    summary.add(line.trim());
                }
            }

            if (failures.isEmpty() && result.succeeded()) {
                return FilterResult.of(raw,
                    summary.isEmpty() ? "✓ all tests passed" : String.join("\n", summary));
            }

            StringBuilder sb = new StringBuilder();
            if (!failures.isEmpty()) {
                sb.append("vitest: ").append(failures.size()).append(" failure(s)\n");
                failures.stream().limit(20).forEach(l -> sb.append(l).append('\n'));
            }
            summary.forEach(l -> sb.append(l).append('\n'));

            return FilterResult.of(raw, sb.toString().stripTrailing());
        } catch (Exception e) {
            log.warnf("VitestFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result.stdout());
        }
    }
}
```

---

## Step 4 — Lint / Build Filters (Group C: Grouping + Deduplication)

### `ESLintFilter.java`

```java
package com.zapproxy.filter.node;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.annotation.CommandFilters;
import com.zapproxy.core.*;
import com.zapproxy.filter.strategy.GroupingStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@CommandFilters({
    @CommandFilter("eslint"),
    @CommandFilter("npx eslint")
})
@ApplicationScoped
public class ESLintFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(ESLintFilter.class);

    // " 3:14  error  'foo' is not defined  no-undef"
    private static final Pattern RULE_PATTERN =
        Pattern.compile("\\s+\\d+:\\d+\\s+(?:error|warning)\\s+.+?\\s+(\\S+)$");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        try {
            String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
            List<String> lines = raw.lines().toList();

            long errors   = lines.stream().filter(l -> l.contains("  error  ")).count();
            long warnings = lines.stream().filter(l -> l.contains("  warning  ")).count();

            if (errors == 0 && warnings == 0 && result.succeeded()) {
                return FilterResult.of(raw, "✓ no lint issues");
            }

            Map<String, Integer> groups = GroupingStrategy.group(lines, RULE_PATTERN, false);

            StringBuilder sb = new StringBuilder();
            sb.append("eslint: ").append(errors).append(" error(s), ")
              .append(warnings).append(" warning(s)\n");
            sb.append(GroupingStrategy.format(groups));

            return FilterResult.of(raw, sb.toString().stripTrailing());
        } catch (Exception e) {
            log.warnf("ESLintFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result.stdout());
        }
    }
}
```

### `TscFilter.java`

```java
package com.zapproxy.filter.node;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandFilter("tsc")
@ApplicationScoped
public class TscFilter implements FilterStrategy {

    // "src/foo.ts(12,3): error TS2345: ..."
    private static final Pattern FILE_PATTERN =
        Pattern.compile("^(\\S+\\.ts)\\(");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
        List<String> lines = raw.lines().toList();

        Map<String, Integer> byFile = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher m = FILE_PATTERN.matcher(line);
            if (m.find()) byFile.merge(m.group(1), 1, Integer::sum);
        }

        if (byFile.isEmpty() && result.succeeded()) return FilterResult.of(raw, "✓ no type errors");

        long total = byFile.values().stream().mapToLong(Integer::longValue).sum();
        StringBuilder sb = new StringBuilder("tsc: ").append(total).append(" error(s)\n");
        byFile.forEach((f, c) -> sb.append("  ").append(f).append(": ").append(c).append('\n'));

        return FilterResult.of(raw, sb.toString().stripTrailing());
    }
}
```

### `RuffFilter.java`

```java
package com.zapproxy.filter.python;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.annotation.CommandFilters;
import com.zapproxy.core.*;
import com.zapproxy.filter.strategy.GroupingStrategy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@CommandFilters({
    @CommandFilter("ruff check"),
    @CommandFilter("ruff")
})
@ApplicationScoped
public class RuffFilter implements FilterStrategy {

    // "src/foo.py:12:3: E501 Line too long"
    private static final Pattern RULE_PATTERN =
        Pattern.compile(":\\s+([A-Z]\\d+)\\s");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
        List<String> lines = raw.lines().toList();

        Map<String, Integer> groups = GroupingStrategy.group(lines, RULE_PATTERN, false);
        long total = groups.values().stream().mapToLong(Integer::longValue).sum();

        if (total == 0 && result.succeeded()) return FilterResult.of(raw, "✓ no lint issues");

        StringBuilder sb = new StringBuilder("ruff: ").append(total).append(" issue(s)\n");
        sb.append(GroupingStrategy.format(groups));
        return FilterResult.of(raw, sb.toString().stripTrailing());
    }
}
```

### `CargoClippyFilter.java`

```java
package com.zapproxy.filter.cargo;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import com.zapproxy.filter.strategy.DeduplicationStrategy;
import com.zapproxy.filter.strategy.GroupingStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@CommandFilter("cargo clippy")
@ApplicationScoped
public class CargoClippyFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(CargoClippyFilter.class);

    // "warning: unused variable `x` --> src/main.rs:10:5"
    private static final Pattern WARNING_RULE =
        Pattern.compile("^warning: (.+)$", Pattern.MULTILINE);
    private static final Pattern LINT_NAME =
        Pattern.compile("#\\[warn\\((.+?)\\)\\]");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        try {
            String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
            List<String> lines = raw.lines().toList();

            // Extract lint rule names from #[warn(...)] annotations
            Map<String, Integer> groups = GroupingStrategy.group(lines, LINT_NAME, false);

            // Fallback: group by warning message first line
            if (groups.isEmpty()) {
                groups = GroupingStrategy.group(lines, WARNING_RULE, false);
            }

            long warnings = groups.values().stream().mapToLong(Integer::longValue).sum();
            if (warnings == 0 && result.succeeded()) {
                return FilterResult.of(raw, "✓ no clippy warnings");
            }

            StringBuilder sb = new StringBuilder("cargo clippy: ")
                .append(warnings).append(" warning(s)\n");
            sb.append(GroupingStrategy.format(groups));

            return FilterResult.of(raw, sb.toString().stripTrailing());
        } catch (Exception e) {
            log.warnf("CargoClippyFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result.stdout());
        }
    }
}
```

### `GolangciLintFilter.java`

```java
package com.zapproxy.filter.golang;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import com.zapproxy.filter.strategy.GroupingStrategy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@CommandFilter("golangci-lint run")
@ApplicationScoped
public class GolangciLintFilter implements FilterStrategy {

    // "src/foo.go:12:3: error message (linter-name)"
    private static final Pattern LINTER_PATTERN =
        Pattern.compile("\\(([a-z][a-z0-9-]+)\\)\\s*$");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
        List<String> lines = raw.lines().toList();

        Map<String, Integer> groups = GroupingStrategy.group(lines, LINTER_PATTERN, false);
        long total = groups.values().stream().mapToLong(Integer::longValue).sum();

        if (total == 0 && result.succeeded()) return FilterResult.of(raw, "✓ no lint issues");

        StringBuilder sb = new StringBuilder("golangci-lint: ").append(total).append(" issue(s)\n");
        sb.append(GroupingStrategy.format(groups));
        return FilterResult.of(raw, sb.toString().stripTrailing());
    }
}
```

---

## Step 5 — File System Filters (Group D: Tree Compression)

### `LsFilter.java`

```java
package com.zapproxy.filter.fs;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import com.zapproxy.filter.strategy.TreeCompressionStrategy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@CommandFilter("ls")
@ApplicationScoped
public class LsFilter implements FilterStrategy {

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout();
        List<String> lines = raw.lines().filter(l -> !l.isBlank()).toList();

        if (lines.isEmpty()) return FilterResult.of(raw, "(empty directory)");
        if (lines.size() <= 10 || verbose >= 2) return FilterResult.passthrough(raw);

        // Large directory: compress to summary
        String tree = TreeCompressionStrategy.compress(lines);
        if (tree.isBlank()) return FilterResult.of(raw, lines.size() + " items");

        return FilterResult.of(raw, tree);
    }
}
```

### `FindFilter.java`

```java
package com.zapproxy.filter.fs;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@CommandFilter("find")
@ApplicationScoped
public class FindFilter implements FilterStrategy {

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout();
        List<String> lines = raw.lines().filter(l -> !l.isBlank()).toList();

        if (lines.isEmpty()) return FilterResult.of(raw, "(no results)");
        if (lines.size() <= 10 || verbose >= 2) return FilterResult.passthrough(raw);

        // Group by extension
        Map<String, Long> byExt = lines.stream().collect(Collectors.groupingBy(
            l -> {
                int dot = l.lastIndexOf('.');
                return dot >= 0 ? l.substring(dot) : "(no extension)";
            }, Collectors.counting()
        ));

        StringBuilder sb = new StringBuilder(lines.size() + " result(s)\n");
        byExt.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(10)
            .forEach(e -> sb.append("  ").append(e.getKey()).append(": ")
                .append(e.getValue()).append('\n'));

        return FilterResult.of(raw, sb.toString().stripTrailing());
    }
}
```

### `GrepFilter.java`

```java
package com.zapproxy.filter.fs;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.annotation.CommandFilters;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@CommandFilters({
    @CommandFilter("grep"),
    @CommandFilter("rg")
})
@ApplicationScoped
public class GrepFilter implements FilterStrategy {

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (result.exitCode() == 1) {
            // grep exits 1 when no matches — not an error
            return FilterResult.of(result.stdout(), "(no matches)");
        }
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout();
        List<String> lines = raw.lines().filter(l -> !l.isBlank()).toList();

        if (lines.isEmpty()) return FilterResult.of(raw, "(no matches)");
        if (lines.size() <= 10 || verbose >= 2) return FilterResult.passthrough(raw);

        // Count matches per file
        Map<String, Integer> byFile = new LinkedHashMap<>();
        for (String line : lines) {
            int colon = line.indexOf(':');
            String file = colon > 0 ? line.substring(0, colon) : "(stdin)";
            byFile.merge(file, 1, Integer::sum);
        }

        StringBuilder sb = new StringBuilder(lines.size() + " match(es) in ")
            .append(byFile.size()).append(" file(s)\n");
        byFile.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(10)
            .forEach(e -> sb.append("  ").append(e.getKey()).append(": ")
                .append(e.getValue()).append('\n'));

        return FilterResult.of(raw, sb.toString().stripTrailing());
    }
}
```

---

## Step 6 — JSON / Cat Filter (Group E)

### `CatFilter.java`

```java
package com.zapproxy.filter.fs;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.annotation.CommandFilters;
import com.zapproxy.core.*;
import com.zapproxy.filter.strategy.JsonStructureStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@CommandFilters({
    @CommandFilter("cat"),
    @CommandFilter("read")
})
@ApplicationScoped
public class CatFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(CatFilter.class);
    private static final int CHAR_LIMIT_BEFORE_COMPRESS = 2000;

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout();

        // Small output — pass through as-is
        if (raw.length() <= CHAR_LIMIT_BEFORE_COMPRESS || verbose >= 2) {
            return FilterResult.passthrough(raw);
        }

        String trimmed = raw.trim();

        // JSON content — show schema skeleton
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                String skeleton = JsonStructureStrategy.skeleton(trimmed);
                return FilterResult.of(raw, skeleton);
            } catch (Exception e) {
                log.debugf("CatFilter JSON parse failed: %s", e.getMessage());
            }
        }

        // Large text — show first + last section
        String[] lines = raw.split("\n");
        if (lines.length > 40) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 20; i++) sb.append(lines[i]).append('\n');
            sb.append("... (").append(lines.length - 40).append(" lines omitted) ...\n");
            for (int i = lines.length - 20; i < lines.length; i++) sb.append(lines[i]).append('\n');
            return FilterResult.of(raw, sb.toString().stripTrailing());
        }

        return FilterResult.passthrough(raw);
    }
}
```

---

## Step 7 — Progress / ANSI Filters (Group F)

### `NpmInstallFilter.java`

```java
package com.zapproxy.filter.node;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.annotation.CommandFilters;
import com.zapproxy.core.*;
import com.zapproxy.filter.strategy.AnsiStripStrategy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandFilters({
    @CommandFilter("npm install"),
    @CommandFilter("npm ci"),
    @CommandFilter("npm i")
})
@ApplicationScoped
public class NpmInstallFilter implements FilterStrategy {

    private static final Pattern ADDED_PATTERN =
        Pattern.compile("added (\\d+) packages?");
    private static final Pattern AUDIT_PATTERN =
        Pattern.compile("found (\\d+) vulnerabilit");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
        String clean = AnsiStripStrategy.strip(raw);

        Matcher added = ADDED_PATTERN.matcher(clean);
        Matcher audit = AUDIT_PATTERN.matcher(clean);

        StringBuilder sb = new StringBuilder("✓ npm install");
        if (added.find()) sb.append(": ").append(added.group(1)).append(" packages");
        if (audit.find()) sb.append(" | ").append(audit.group(0));

        return FilterResult.of(raw, sb.toString());
    }
}
```

### `PipInstallFilter.java`

```java
package com.zapproxy.filter.python;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.annotation.CommandFilters;
import com.zapproxy.core.*;
import com.zapproxy.filter.strategy.AnsiStripStrategy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@CommandFilters({
    @CommandFilter("pip install"),
    @CommandFilter("pip3 install")
})
@ApplicationScoped
public class PipInstallFilter implements FilterStrategy {

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
        String clean = AnsiStripStrategy.strip(raw);

        List<String> installed = clean.lines()
            .filter(l -> l.startsWith("Successfully installed"))
            .toList();

        if (installed.isEmpty()) {
            String lastLine = AnsiStripStrategy.lastMeaningfulLine(clean);
            return FilterResult.of(raw, lastLine.isBlank() ? "✓ pip install" : lastLine);
        }

        return FilterResult.of(raw, installed.get(installed.size() - 1).trim());
    }
}
```

### `CargoInstallFilter.java`

```java
package com.zapproxy.filter.cargo;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.annotation.CommandFilters;
import com.zapproxy.core.*;
import com.zapproxy.filter.strategy.AnsiStripStrategy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandFilters({
    @CommandFilter("cargo install"),
    @CommandFilter("cargo build")
})
@ApplicationScoped
public class CargoInstallFilter implements FilterStrategy {

    private static final Pattern INSTALLED =
        Pattern.compile("Installed package `(.+?)`|Compiling (.+?) v");
    private static final Pattern FINISHED =
        Pattern.compile("Finished .+ in (.+)");
    private static final Pattern ERROR_LINE =
        Pattern.compile("^error(\\[.+?\\])?:", Pattern.MULTILINE);

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
        String clean = AnsiStripStrategy.strip(raw);

        if (!result.succeeded()) {
            // Return compiler errors only
            List<String> errors = clean.lines()
                .filter(l -> ERROR_LINE.matcher(l).find())
                .limit(15)
                .toList();
            String errOut = errors.isEmpty() ? clean : String.join("\n", errors);
            return FilterResult.of(raw, errOut);
        }

        Matcher fin = FINISHED.matcher(clean);
        if (fin.find()) return FilterResult.of(raw, "✓ " + fin.group(0).trim());

        return FilterResult.of(raw, "✓ " + command.split(" ")[1] + " complete");
    }

    private java.util.List<String> List(java.util.stream.Stream<String> stream) {
        return stream.toList();
    }
}
```

### `DockerBuildFilter.java`

```java
package com.zapproxy.filter.cloud;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import com.zapproxy.filter.strategy.AnsiStripStrategy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandFilter("docker build")
@ApplicationScoped
public class DockerBuildFilter implements FilterStrategy {

    private static final Pattern IMAGE_ID =
        Pattern.compile("(?:Successfully built|writing image sha256:)\\s*([0-9a-f]{8,12})");
    private static final Pattern TAGGED =
        Pattern.compile("Successfully tagged (.+)");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
        String clean = AnsiStripStrategy.strip(raw);

        StringBuilder sb = new StringBuilder("✓ docker build");
        Matcher id = IMAGE_ID.matcher(clean);
        if (id.find()) sb.append(": ").append(id.group(1));
        Matcher tag = TAGGED.matcher(clean);
        if (tag.find()) sb.append(" → ").append(tag.group(1).trim());

        return FilterResult.of(raw, sb.toString());
    }
}
```

---

## Step 8 — Cloud CLI Filters (Group G)

### `DockerPsFilter.java`

```java
package com.zapproxy.filter.cloud;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

@CommandFilter("docker ps")
@ApplicationScoped
public class DockerPsFilter implements FilterStrategy {

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout();
        List<String> lines = raw.lines().toList();
        if (lines.isEmpty()) return FilterResult.of(raw, "(no containers running)");

        // Parse docker ps tabular output: CONTAINER ID | IMAGE | STATUS | NAMES
        List<String> compact = new ArrayList<>();
        compact.add("ID       IMAGE                STATUS    NAME");
        compact.add("─".repeat(55));

        for (int i = 1; i < lines.size(); i++) {   // skip header
            String line = lines.get(i);
            if (line.isBlank()) continue;
            String[] cols = line.split("\\s{2,}");
            if (cols.length < 7) {
                compact.add(line.trim());
                continue;
            }
            String id     = cols[0].length() > 8 ? cols[0].substring(0, 8) : cols[0];
            String image  = cols[1].length() > 20 ? cols[1].substring(0, 19) + "…" : cols[1];
            String status = cols[4].length() > 10 ? cols[4].substring(0, 10) : cols[4];
            String name   = cols[cols.length - 1];
            compact.add(String.format("%-8s %-20s %-10s %s", id, image, status, name));
        }

        return FilterResult.of(raw, String.join("\n", compact));
    }
}
```

### `KubectlFilter.java`

```java
package com.zapproxy.filter.cloud;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@CommandFilter("kubectl")
@ApplicationScoped
public class KubectlFilter implements FilterStrategy {

    private static final Pattern NOT_RUNNING = Pattern.compile("Error|CrashLoopBackOff|OOMKilled|Pending|Terminating", Pattern.CASE_INSENSITIVE);

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout();
        List<String> lines = raw.lines().toList();
        if (lines.isEmpty()) return FilterResult.passthrough(raw);

        // For 'kubectl get pods' style: compact table
        if (command.contains("get") || command.contains("describe")) {
            return compactTable(raw, lines);
        }

        // For 'kubectl logs': tail last 20 lines
        if (command.contains("logs")) {
            List<String> tail = lines.subList(Math.max(0, lines.size() - 20), lines.size());
            String note = lines.size() > 20 ? "... (showing last 20 of " + lines.size() + " lines)\n" : "";
            return FilterResult.of(raw, note + String.join("\n", tail));
        }

        return FilterResult.passthrough(raw);
    }

    private FilterResult compactTable(String raw, List<String> lines) {
        // Surface non-Running pods prominently
        List<String> unhealthy = new ArrayList<>();
        List<String> healthy   = new ArrayList<>();
        if (!lines.isEmpty()) healthy.add(lines.get(0)); // header

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (NOT_RUNNING.matcher(line).find()) {
                unhealthy.add("⚠ " + line.trim());
            } else {
                healthy.add(line);
            }
        }

        StringBuilder sb = new StringBuilder();
        if (!unhealthy.isEmpty()) {
            sb.append("UNHEALTHY PODS:\n");
            unhealthy.forEach(l -> sb.append("  ").append(l).append('\n'));
            sb.append('\n');
        }
        healthy.forEach(l -> sb.append(l).append('\n'));
        return FilterResult.of(raw, sb.toString().stripTrailing());
    }
}
```

### `AwsFilter.java`

```java
package com.zapproxy.filter.cloud;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import com.zapproxy.filter.strategy.JsonStructureStrategy;
import jakarta.enterprise.context.ApplicationScoped;

@CommandFilter("aws")
@ApplicationScoped
public class AwsFilter implements FilterStrategy {

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout();
        if (raw.isBlank()) return FilterResult.of(raw, "✓ ok");

        // AWS CLI returns JSON — show schema skeleton
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            if (raw.length() > 500 || !verbose_mode(verbose)) {
                String skeleton = JsonStructureStrategy.skeleton(trimmed);
                return FilterResult.of(raw, skeleton);
            }
        }

        return FilterResult.passthrough(raw);
    }

    private boolean verbose_mode(int verbose) { return verbose >= 2; }
}
```

---

## Step 9 — Remaining Filters

### `CargoBuildFilter.java` (alias — delegates to CargoInstallFilter logic)

Already handled by `@CommandFilters({@CommandFilter("cargo install"), @CommandFilter("cargo build")})` on `CargoInstallFilter.java`.

### `PythonFilter.java` (python -m pytest / python -c)

```java
package com.zapproxy.filter.python;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.annotation.CommandFilters;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

@CommandFilters({
    @CommandFilter("python -m pytest"),
    @CommandFilter("python3 -m pytest"),
    @CommandFilter("python -c"),
    @CommandFilter("python3 -c")
})
@ApplicationScoped
public class PythonFilter implements FilterStrategy {

    private final PytestFilter pytestFilter = new PytestFilter();

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        // Delegate to pytest filter for pytest invocations
        if (command.contains("pytest")) {
            return pytestFilter.apply(command, result, config, verbose, ultraCompact);
        }
        // For python -c: passthrough (output is intentional)
        return FilterResult.passthrough(result.stdout());
    }
}
```

### `MakeFilter.java`

```java
package com.zapproxy.filter.build;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@CommandFilter("make")
@ApplicationScoped
public class MakeFilter implements FilterStrategy {

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) {
            // Show only error lines on failure
            String raw = result.combined();
            List<String> errors = raw.lines()
                .filter(l -> l.startsWith("make") || l.contains("Error") || l.contains("error:"))
                .limit(15)
                .toList();
            return FilterResult.of(raw, errors.isEmpty() ? raw : String.join("\n", errors));
        }

        String raw = result.stdout();
        String lastLine = raw.lines().filter(l -> !l.isBlank()).reduce("", (a, b) -> b);
        return FilterResult.of(raw, "✓ make: " + (lastLine.isBlank() ? "done" : lastLine.trim()));
    }
}
```

### `MvnFilter.java`

```java
package com.zapproxy.filter.build;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.annotation.CommandFilters;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@CommandFilters({
    @CommandFilter("mvn"),
    @CommandFilter("./mvnw")
})
@ApplicationScoped
public class MvnFilter implements FilterStrategy {

    private static final Pattern BUILD_SUCCESS = Pattern.compile("BUILD SUCCESS");
    private static final Pattern BUILD_FAILURE = Pattern.compile("BUILD FAILURE");
    private static final Pattern ERROR_LINE    = Pattern.compile("^\\[ERROR\\]");
    private static final Pattern TEST_FAIL     = Pattern.compile("Tests run:.+Failures: [1-9]");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
        List<String> lines = raw.lines().toList();

        if (BUILD_SUCCESS.matcher(raw).find()) {
            // Count test results if any
            String testLine = lines.stream()
                .filter(l -> l.contains("Tests run:"))
                .reduce("", (a, b) -> b);
            return FilterResult.of(raw,
                "✓ BUILD SUCCESS" + (testLine.isBlank() ? "" : " — " + testLine.trim()));
        }

        if (BUILD_FAILURE.matcher(raw).find()) {
            List<String> errors = new ArrayList<>();
            for (String line : lines) {
                if (ERROR_LINE.matcher(line).find()) errors.add(line.trim());
                if (TEST_FAIL.matcher(line).find()) errors.add(line.trim());
            }
            errors = errors.subList(0, Math.min(20, errors.size()));
            return FilterResult.of(raw,
                "✗ BUILD FAILURE\n" + String.join("\n", errors));
        }

        return FilterResult.passthrough(raw);
    }
}
```

### `GradleFilter.java`

```java
package com.zapproxy.filter.build;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.annotation.CommandFilters;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

@CommandFilters({
    @CommandFilter("gradle"),
    @CommandFilter("./gradlew")
})
@ApplicationScoped
public class GradleFilter implements FilterStrategy {

    private static final Pattern BUILD_SUCCESSFUL = Pattern.compile("BUILD SUCCESSFUL");
    private static final Pattern BUILD_FAILED     = Pattern.compile("BUILD FAILED");
    private static final Pattern FAILURE_DETAIL   = Pattern.compile("^> ", Pattern.MULTILINE);

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();

        if (BUILD_SUCCESSFUL.matcher(raw).find()) {
            String duration = raw.lines()
                .filter(l -> l.contains("BUILD SUCCESSFUL"))
                .findFirst().map(String::trim).orElse("BUILD SUCCESSFUL");
            return FilterResult.of(raw, "✓ " + duration);
        }

        if (BUILD_FAILED.matcher(raw).find()) {
            List<String> details = raw.lines()
                .filter(l -> FAILURE_DETAIL.matcher(l).find() || l.startsWith("FAILURE:"))
                .limit(15)
                .toList();
            return FilterResult.of(raw,
                "✗ BUILD FAILED\n" + String.join("\n", details));
        }

        return FilterResult.passthrough(raw);
    }
}
```

### `DockerFilter.java` (docker logs / docker exec / docker run)

```java
package com.zapproxy.filter.cloud;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.annotation.CommandFilters;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@CommandFilters({
    @CommandFilter("docker logs"),
    @CommandFilter("docker run"),
    @CommandFilter("docker exec")
})
@ApplicationScoped
public class DockerFilter implements FilterStrategy {

    private static final int MAX_LOG_LINES = 30;

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
        List<String> lines = raw.lines().filter(l -> !l.isBlank()).toList();

        if (lines.size() <= MAX_LOG_LINES || verbose >= 2) return FilterResult.passthrough(raw);

        // Tail last MAX_LOG_LINES lines
        List<String> tail = lines.subList(lines.size() - MAX_LOG_LINES, lines.size());
        String header = "... (showing last " + MAX_LOG_LINES + " of " + lines.size() + " lines)\n";
        return FilterResult.of(raw, header + String.join("\n", tail));
    }
}
```

---

## Step 10 — Test Support Infrastructure

### `FilterTestSupport.java` (base class for all filter tests)

```java
package com.zapproxy.filter;

import com.zapproxy.core.*;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class providing fixture loading and assertion helpers for filter tests.
 *
 * <p>Fixture files live under {@code src/test/resources/fixtures/{command-name}/}.
 * Each fixture directory must contain at least {@code typical.txt}.
 */
public abstract class FilterTestSupport {

    /** Loads a fixture file from the classpath. */
    protected String fixture(String commandDir, String fixtureName) throws Exception {
        String path = "/fixtures/" + commandDir + "/" + fixtureName + ".txt";
        URL url = getClass().getResource(path);
        assertThat(url)
            .as("Fixture '%s' not found on classpath", path)
            .isNotNull();
        return Files.readString(Path.of(url.toURI()));
    }

    /** Creates an ExecutionResult with exit 0 and the given stdout. */
    protected ExecutionResult success(String stdout) {
        return new ExecutionResult(0, stdout, "", 10L);
    }

    /** Creates an ExecutionResult with non-zero exit and stderr. */
    protected ExecutionResult failure(int exitCode, String stderr) {
        return new ExecutionResult(exitCode, "", stderr, 5L);
    }

    /** Creates an ExecutionResult with non-zero exit and both streams. */
    protected ExecutionResult failure(int exitCode, String stdout, String stderr) {
        return new ExecutionResult(exitCode, stdout, stderr, 5L);
    }

    /** Asserts that the filter output is shorter than the raw input in tokens. */
    protected void assertCompressed(FilterResult result) {
        assertThat(result.rawTokens())
            .as("Expected rawTokens > outTokens (filter should compress)")
            .isGreaterThan(result.outTokens());
        assertThat(result.wasFiltered()).isTrue();
    }

    /** Asserts that the filter did not modify the output. */
    protected void assertPassthrough(FilterResult result) {
        assertThat(result.wasFiltered()).isFalse();
        assertThat(result.rawTokens()).isEqualTo(result.outTokens());
    }
}
```

### Fixture files to create

Create the following fixture files. Each is a representative sample of real command
output. The content below is a minimal representative — add more realistic content
for your actual test environment.

**`src/test/resources/fixtures/git-diff/typical.txt`**
```
diff --git a/src/main/java/Foo.java b/src/main/java/Foo.java
index abc1234..def5678 100644
--- a/src/main/java/Foo.java
+++ b/src/main/java/Foo.java
@@ -10,6 +10,8 @@ public class Foo {
     public void bar() {
+        // new comment
+        doSomething();
     }
 }
 3 files changed, 45 insertions(+), 12 deletions(-)
```

**`src/test/resources/fixtures/git-diff/stat.txt`**
```
 src/main/java/Foo.java      | 12 +++++++++---
 src/main/java/Bar.java      |  3 +--
 src/test/java/FooTest.java  |  8 ++++++++
 3 files changed, 19 insertions(+), 4 deletions(-)
```

**`src/test/resources/fixtures/cargo-test/typical.txt`**
```
running 5 tests
test tests::test_add ... ok
test tests::test_subtract ... ok
test tests::test_multiply ... FAILED
test tests::test_divide ... ok
test tests::test_modulo ... FAILED

failures:

---- tests::test_multiply stdout ----
thread 'tests::test_multiply' panicked at 'assertion failed: `(left == right)`
  left: `6`,
 right: `5`', src/lib.rs:25:5

---- tests::test_modulo stdout ----
thread 'tests::test_modulo' panicked at 'attempt to divide by zero', src/lib.rs:30:5

test result: FAILED. 3 passed; 2 failed; 0 ignored; 0 measured; 0 filtered out
```

**`src/test/resources/fixtures/cargo-test/passing.txt`**
```
running 3 tests
test tests::test_one ... ok
test tests::test_two ... ok
test tests::test_three ... ok

test result: ok. 3 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out
```

**`src/test/resources/fixtures/pytest/typical.txt`**
```
============================= test session starts ==============================
platform linux -- Python 3.11.0, pytest-7.4.0
collected 8 items

test_math.py::test_add PASSED                                            [ 12%]
test_math.py::test_sub PASSED                                            [ 25%]
test_math.py::test_mul FAILED                                            [ 37%]
test_math.py::test_div PASSED                                            [ 50%]
test_math.py::test_mod FAILED                                            [ 62%]

=========================== short test summary info ============================
FAILED test_math.py::test_mul - AssertionError: assert 6 == 5
FAILED test_math.py::test_mod - ZeroDivisionError: division by zero
========================= 3 passed, 2 failed in 0.12s =========================
```

**`src/test/resources/fixtures/eslint/typical.txt`**
```
/home/user/project/src/index.js
  3:5   error  'foo' is not defined           no-undef
  7:10  warning  Unexpected console statement  no-console
 12:1   error  'bar' is not defined           no-undef

/home/user/project/src/utils.js
  1:8   error  'baz' is not defined           no-undef
  5:3   warning  Unexpected console statement  no-console

✖ 5 problems (3 errors, 2 warnings)
```

**`src/test/resources/fixtures/docker-ps/typical.txt`**
```
CONTAINER ID   IMAGE          COMMAND                  CREATED         STATUS         PORTS                    NAMES
a1b2c3d4e5f6   nginx:latest   "/docker-entrypoint.…"   2 hours ago     Up 2 hours     0.0.0.0:80->80/tcp       web-server
b2c3d4e5f6a1   postgres:15    "docker-entrypoint.s…"   3 hours ago     Up 3 hours     0.0.0.0:5432->5432/tcp   database
```

---

## Step 11 — Filter Tests

Write one test class per filter. Here are three representative examples — write the
rest following the same pattern.

### `GitDiffFilterTest.java`

```java
package com.zapproxy.filter.git;

import com.zapproxy.core.*;
import com.zapproxy.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitDiffFilterTest extends FilterTestSupport {

    private GitDiffFilter filter;
    private ZapConfig config;

    @BeforeEach
    void setUp() { filter = new GitDiffFilter(); config = ZapConfig.defaults(); }

    @Test
    void statSummary_extractsSummaryLine() throws Exception {
        FilterResult r = filter.apply("git diff",
            success(fixture("git-diff", "stat")), config, 0, false);
        assertThat(r.output()).contains("files changed");
        assertCompressed(r);
    }

    @Test
    void patchDiff_countsAddedAndRemovedLines() throws Exception {
        FilterResult r = filter.apply("git diff",
            success(fixture("git-diff", "typical")), config, 0, false);
        assertThat(r.output()).containsAnyOf("+", "files changed");
        assertCompressed(r);
    }

    @Test
    void nonZeroExit_passesThrough() {
        FilterResult r = filter.apply("git diff",
            failure(1, "fatal: bad revision"), config, 0, false);
        assertPassthrough(r);
        assertThat(r.output()).contains("fatal:");
    }
}
```

### `CargoTestFilterTest.java`

```java
package com.zapproxy.filter.cargo;

import com.zapproxy.core.*;
import com.zapproxy.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CargoTestFilterTest extends FilterTestSupport {

    private CargoTestFilter filter;
    private ZapConfig config;

    @BeforeEach
    void setUp() { filter = new CargoTestFilter(); config = ZapConfig.defaults(); }

    @Test
    void withFailures_showsOnlyFailureNames() throws Exception {
        FilterResult r = filter.apply("cargo test",
            new ExecutionResult(101, fixture("cargo-test", "typical"), "", 500L),
            config, 0, false);
        assertThat(r.output()).contains("FAILED: tests::test_multiply");
        assertThat(r.output()).contains("FAILED: tests::test_modulo");
        assertThat(r.output()).doesNotContain("ok");
        assertCompressed(r);
    }

    @Test
    void allPassing_showsSuccessLine() throws Exception {
        FilterResult r = filter.apply("cargo test",
            success(fixture("cargo-test", "passing")), config, 0, false);
        assertThat(r.output()).containsAnyOf("passed", "✓");
        assertCompressed(r);
    }

    @Test
    void failureCount_isCorrect() throws Exception {
        FilterResult r = filter.apply("cargo test",
            new ExecutionResult(101, fixture("cargo-test", "typical"), "", 500L),
            config, 0, false);
        assertThat(r.output()).contains("2 failure(s)");
    }
}
```

### `PytestFilterTest.java`

```java
package com.zapproxy.filter.python;

import com.zapproxy.core.*;
import com.zapproxy.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PytestFilterTest extends FilterTestSupport {

    private PytestFilter filter;
    private ZapConfig config;

    @BeforeEach
    void setUp() { filter = new PytestFilter(); config = ZapConfig.defaults(); }

    @Test
    void withFailures_showsFailedTestNames() throws Exception {
        FilterResult r = filter.apply("pytest",
            new ExecutionResult(1, fixture("pytest", "typical"), "", 200L),
            config, 0, false);
        assertThat(r.output()).contains("test_mul");
        assertThat(r.output()).contains("test_mod");
        assertCompressed(r);
    }

    @Test
    void withFailures_showsFinalSummaryLine() throws Exception {
        FilterResult r = filter.apply("pytest",
            new ExecutionResult(1, fixture("pytest", "typical"), "", 200L),
            config, 0, false);
        assertThat(r.output()).contains("passed");
        assertThat(r.output()).contains("failed");
    }

    @Test
    void doesNotShowPassedTestLines() throws Exception {
        FilterResult r = filter.apply("pytest",
            new ExecutionResult(1, fixture("pytest", "typical"), "", 200L),
            config, 0, false);
        assertThat(r.output()).doesNotContain("PASSED");
    }
}
```

### `DockerPsFilterTest.java`

```java
package com.zapproxy.filter.cloud;

import com.zapproxy.core.*;
import com.zapproxy.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DockerPsFilterTest extends FilterTestSupport {

    private DockerPsFilter filter;
    private ZapConfig config;

    @BeforeEach
    void setUp() { filter = new DockerPsFilter(); config = ZapConfig.defaults(); }

    @Test
    void compactsTableToEssentialColumns() throws Exception {
        FilterResult r = filter.apply("docker ps",
            success(fixture("docker-ps", "typical")), config, 0, false);
        assertThat(r.output()).contains("web-server");
        assertThat(r.output()).contains("database");
        assertCompressed(r);
    }

    @Test
    void emptyDockerPs_returnsNoContainersMessage() {
        FilterResult r = filter.apply("docker ps",
            success("CONTAINER ID   IMAGE   COMMAND   CREATED   STATUS   PORTS   NAMES"),
            config, 0, false);
        assertThat(r.output()).contains("no containers");
    }
}
```

---

## Step 12 — Update `reflect-config.json`

Add an entry for every new filter class. Open the file and append:

```json
  { "name": "com.zapproxy.filter.git.GitDiffFilter",       "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.git.GitLogFilter",        "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.git.GitPushFilter",       "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.git.GitCommitFilter",     "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.git.GitAddFilter",        "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.cargo.CargoTestFilter",   "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.cargo.CargoClippyFilter", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.cargo.CargoInstallFilter","allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.python.PytestFilter",     "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.python.RuffFilter",       "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.python.PipInstallFilter", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.python.PythonFilter",     "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.golang.GoTestFilter",     "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.golang.GolangciLintFilter","allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.node.JestFilter",         "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.node.VitestFilter",       "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.node.ESLintFilter",       "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.node.TscFilter",          "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.node.NpmInstallFilter",   "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.fs.LsFilter",             "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.fs.FindFilter",           "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.fs.GrepFilter",           "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.fs.CatFilter",            "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.cloud.DockerPsFilter",    "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.cloud.DockerBuildFilter", "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.cloud.DockerFilter",      "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.cloud.KubectlFilter",     "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.cloud.AwsFilter",         "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.build.MakeFilter",        "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.build.MvnFilter",         "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.build.GradleFilter",      "allDeclaredConstructors": true, "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.strategy.DeduplicationStrategy",  "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.strategy.GroupingStrategy",       "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.strategy.AnsiStripStrategy",      "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.strategy.JsonStructureStrategy",  "allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.strategy.TreeCompressionStrategy","allDeclaredMethods": true },
  { "name": "com.zapproxy.filter.strategy.StateMachineStrategy",   "allDeclaredConstructors": true, "allDeclaredMethods": true }
```

---

## Step 13 — Verification Gauntlet

### 13.1 JVM build
```bash
mvn clean verify
```
Expected: `BUILD SUCCESS`, zero failures. Surefire must show all new test classes.

### 13.2 Count registered filters
```bash
mvn test 2>&1 | grep "StrategyRegistry:"
```
Expected: `StrategyRegistry: 28+ filter(s) registered`
(GitStatus=1 from Phase 2 + 27+ new from Phase 3)

### 13.3 Native image build
```bash
mvn package -Pnative -DskipTests
```
Expected: `BUILD SUCCESS`, no fallback warning.

### 13.4 Smoke tests
```bash
# From inside a git repo with changes:
./target/zap-runner git diff                      # shows "+N/-N lines" or stat summary
./target/zap-runner git log                       # shows "abc1234 commit message" per commit
./target/zap-runner git push                      # shows "✓ pushed → main" or "✓ up-to-date"

# From a Rust project with failing tests:
./target/zap-runner cargo test 2>&1 | head -5     # shows only FAILED lines

# Node project with ESLint errors:
./target/zap-runner eslint src/ 2>&1 | head -10   # shows grouped rule counts

# Docker:
./target/zap-runner docker ps                     # compact container table

# File system:
./target/zap-runner ls -la /usr/lib               # compressed tree, not raw list
./target/zap-runner grep -r "TODO" src/ 2>&1      # match count per file
```

### 13.5 Token savings are being logged
```bash
./target/zap-runner git diff
./target/zap-runner cargo test 2>/dev/null || true
sqlite3 ~/.local/share/zap/zap.db \
  "SELECT command, raw_tokens, out_tokens, printf('%.0f%%', 100.0*(raw_tokens-out_tokens)/raw_tokens) as saved
   FROM commands ORDER BY ts DESC LIMIT 5;"
```
Expected: rows with non-trivial savings (>50%) for filtered commands.

### 13.6 Startup time still ≤ 100ms
```bash
for i in {1..5}; do { time ./target/zap-runner --version; } 2>&1 | grep real; done
```

### 13.7 File structure check
```bash
find src/main/java/com/zapproxy/filter -name "*.java" | sort
```
Must include all packages: `annotation`, `filter/git`, `filter/cargo`, `filter/python`,
`filter/golang`, `filter/node`, `filter/fs`, `filter/cloud`, `filter/build`,
`filter/strategy`.

---

## Phase 3 Sign-Off Table

| # | Criterion | Pass Condition |
|---|---|---|
| 1 | `mvn verify` exits 0 | `echo $?` → `0` |
| 2 | No Phase 1/2 regressions | All prior test classes still green |
| 3 | All Phase 3 tests pass | 0 failures in new test classes |
| 4 | 28+ filters registered at startup | Grep `StrategyRegistry:` in test output |
| 5 | Native image builds without fallback | No "fallback image" in build output |
| 6 | `zap cargo test` shows only failures | Smoke test 13.4 |
| 7 | `zap eslint src/` shows grouped rules | Smoke test 13.4 |
| 8 | `zap docker ps` shows compact table | Smoke test 13.4 |
| 9 | `zap ls /usr/lib` compresses output | Smoke test 13.4 |
| 10 | SQLite logs savings > 50% for filters | Step 13.5 sqlite3 query |
| 11 | Cold start ≤ 100ms median | Benchmark loop 13.6 |
| 12 | All filter packages present | `find` command 13.7 |

When all 12 pass simultaneously:

```
PHASE 3 COMPLETE
────────────────────────────────────────────────
Tests:        [N passing / 0 failing / 0 errors]
Filters:      [28+ registered]
Native build: PASS — no fallback
Cold start:   [Xms median]
Coverage:     git(5) cargo(3) python(4) go(2) node(5) fs(4) cloud(4) build(3)

Ready for Phase 4: Analytics Engine + Hook Installer.
```

---

## Common Failure Modes

**"Ambiguous dependency" CDI error when two filters handle overlapping prefixes**
Example: `@CommandFilter("ruff")` and `@CommandFilter("ruff check")` on different classes.
Longest-prefix matching in `StrategyRegistry` handles this correctly at runtime, but CDI
may not know which bean to inject if someone directly injects a `FilterStrategy`. Solution:
ensure no two classes share the exact same `@CommandFilter` value.

**`@CommandFilters` container annotation not found at runtime**
`@CommandFilters` must have `@Retention(RetentionPolicy.RUNTIME)` — verify this. If it
has `RetentionPolicy.CLASS` (the default), the annotation is invisible at runtime and
CDI discovery fails silently.

**`NullPointerException` in `CargoInstallFilter` referencing a non-existent method `List()`**
The filter as written has a private `List()` method that shadows `java.util.List`. Remove
the private `List()` method — it was a typo in an earlier draft. Use `stream.toList()`
directly where needed.

**`GoTestFilter` never finds JSON events — falls through to plain text parser**
If the user doesn't pass `-json` to `go test`, output is plain text. The filter handles
both paths (JSON events first, plain text fallback) — verify the fallback
`parsePlainGoTest()` method exists and is called when `passed == 0 && failures.isEmpty()`.

**Docker PS table misaligned**
`docker ps` output column spacing varies by container name length. The filter splits on
`\\s{2,}` (two or more spaces) — verify this regex is correct. If `cols.length < 7`,
fall back to printing the raw line trimmed. Don't hard-code column offsets.

*Phase 3 prompt complete. Next: Phase 4 — Analytics Engine + Hook Installer.*
