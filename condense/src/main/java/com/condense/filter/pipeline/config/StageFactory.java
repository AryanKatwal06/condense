package com.condense.filter.pipeline.config;

import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.stage.*;
import com.condense.filter.strategy.*;
import com.condense.trust.Capability;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Hardcoded dispatch from a declarative strategy alias to a {@link FilterStage}.
 * Never uses reflection or user-supplied class names.
 */
public final class StageFactory {

    public static final long REGEX_TIMEOUT_MS = 200L;
    public static final int MAX_PATTERN_LENGTH = 500;
    public static final int MAX_TRANSITIONS_COUNT = 50;

    public static final Set<String> ALLOWED_ALIASES = Set.of(
        "ansi_strip", "ansi-strip", "ansi",
        "tree_compression", "tree-compression", "tree",
        "json_structure", "json-structure", "json",
        "deduplication", "dedup",
        "grouping", "group",
        "state_machine", "state-machine",
        "tail_lines", "tail-lines",
        "head_tail", "head-tail",
        "aggregate_by_key", "aggregate-by-key",
        "regex_capture", "regex-capture",
        "git_status", "git-status",
        "json_lines", "json-lines",
        "docker_ps", "docker-ps",
        "git_add_summary",
        "git_commit_summary",
        "git_diff_summary",
        "git_log",
        "git_push_summary",
        "ls_empty_tree_fallback",
        "cat_content",
        "docker_build_summary",
        "kubectl_dispatch",
        "cargo_clippy_summary",
        "cargo_install_summary",
        "cargo_test_summary",
        "gradle_summary",
        "make_summary",
        "mvn_summary",
        "eslint_json",
        "eslint_text",
        "jest_summary",
        "npm_install_summary",
        "tsc_summary",
        "vitest_summary",
        "golangci_summary",
        "pip_install_summary",
        "pytest_summary",
        "ruff_summary"
    );

    private static final String CANONICAL_LIST =
        "ansi_strip, tree_compression, json_structure, deduplication, grouping, state_machine, "
            + "tail_lines, head_tail, aggregate_by_key, regex_capture, git_status, json_lines, docker_ps, "
            + "and the named command-specific summaries";

    private StageFactory() {}

    public static String normalize(String strategy) {
        return strategy == null ? "" : strategy.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isAllowed(String strategy) {
        return ALLOWED_ALIASES.contains(normalize(strategy));
    }

    /**
     * Capability class for a strategy alias. Unknown aliases are treated as reshape
     * (they will fail validation before a trusted file can apply them).
     */
    public static Capability capabilityOf(String strategy) {
        return switch (normalize(strategy)) {
            case "ansi_strip", "ansi-strip", "ansi",
                 "tail_lines", "tail-lines",
                 "head_tail", "head-tail",
                 "tree_compression", "tree-compression", "tree",
                 "deduplication", "dedup" -> Capability.REDUCE;
            case "regex_capture", "regex-capture",
                 "state_machine", "state-machine" -> Capability.REWRITE;
            default -> Capability.RESHAPE;
        };
    }

    public static Set<Capability> requiredCapabilities(List<FilterOverrideConfig.StageDef> stages) {
        if (stages == null || stages.isEmpty()) {
            return Set.of(Capability.REDUCE);
        }
        Set<Capability> caps = new LinkedHashSet<>();
        for (FilterOverrideConfig.StageDef stage : stages) {
            if (stage != null && stage.strategy() != null && !stage.strategy().isBlank()) {
                caps.add(capabilityOf(stage.strategy()));
            }
        }
        return caps.isEmpty() ? Set.of(Capability.REDUCE) : Set.copyOf(caps);
    }

    public static FilterPipeline buildPipeline(List<FilterOverrideConfig.StageDef> stages) {
        FilterPipeline.Builder builder = FilterPipeline.builder();
        if (stages == null || stages.isEmpty()) {
            return builder.build();
        }
        for (FilterOverrideConfig.StageDef stageDef : stages) {
            FilterStage stage = instantiate(stageDef);
            if (stage != null) {
                builder.addStage(stage);
            }
        }
        return builder.build();
    }

    public static FilterStage instantiate(FilterOverrideConfig.StageDef stageDef) {
        if (stageDef == null) {
            return null;
        }
        return switch (normalize(stageDef.strategy())) {
            case "ansi_strip", "ansi-strip", "ansi" -> AnsiStripStrategy.INSTANCE;
            case "tree_compression", "tree-compression", "tree" -> TreeCompressionStrategy.INSTANCE;
            case "json_structure", "json-structure", "json" -> JsonStructureStrategy.INSTANCE;
            case "deduplication", "dedup" -> {
                int window = (stageDef.windowSize() != null && stageDef.windowSize() > 0)
                    ? stageDef.windowSize() : 50;
                yield new DeduplicationStrategy(window);
            }
            case "grouping", "group" -> {
                Pattern pattern = stageDef.pattern() != null && !stageDef.pattern().isBlank()
                    ? Pattern.compile(stageDef.pattern())
                    : Pattern.compile("(.*)");
                boolean includeOther = Boolean.TRUE.equals(stageDef.includeOther());
                yield new GroupingStrategy(pattern, includeOther, REGEX_TIMEOUT_MS);
            }
            case "state_machine", "state-machine" -> buildStateMachine(stageDef);
            case "tail_lines", "tail-lines" -> {
                int max = stageDef.maxLines() != null ? stageDef.maxLines() : 20;
                boolean skipBlank = Boolean.TRUE.equals(stageDef.skipBlank());
                boolean headerOnly = stageDef.headerOnlyWhenTruncating() == null
                    || Boolean.TRUE.equals(stageDef.headerOnlyWhenTruncating());
                yield new TailLinesStage(max, skipBlank, headerOnly);
            }
            case "head_tail", "head-tail" -> {
                int head = stageDef.head() != null ? stageDef.head() : 20;
                int tail = stageDef.tail() != null ? stageDef.tail() : 20;
                yield new HeadTailStage(head, tail);
            }
            case "aggregate_by_key", "aggregate-by-key" -> {
                String key = stageDef.key() != null ? stageDef.key() : AggregateByKeyStage.KEY_PREFIX_BEFORE_COLON;
                String header = stageDef.header() != null ? stageDef.header() : "{lines}";
                int topN = stageDef.topN() != null && stageDef.topN() > 0 ? stageDef.topN() : 10;
                yield AggregateByKeyStage.ofPreset(key, header, topN);
            }
            case "regex_capture", "regex-capture" -> {
                Pattern pattern = Pattern.compile(
                    stageDef.pattern() != null && !stageDef.pattern().isBlank() ? stageDef.pattern() : "(.*)");
                yield new RegexCaptureStage(pattern, stageDef.format(), stageDef.fallback());
            }
            case "git_status", "git-status" -> GitStatusStage.INSTANCE;
            case "json_lines", "json-lines" -> JsonLinesStage.INSTANCE;
            case "docker_ps", "docker-ps" -> DockerPsStage.INSTANCE;
            case "git_add_summary" -> GitAddSummaryStage.INSTANCE;
            case "git_commit_summary" -> GitCommitSummaryStage.INSTANCE;
            case "git_diff_summary" -> GitDiffSummaryStage.INSTANCE;
            case "git_log" -> GitLogStage.INSTANCE;
            case "git_push_summary" -> GitPushSummaryStage.INSTANCE;
            case "ls_empty_tree_fallback" -> LsEmptyTreeFallbackStage.INSTANCE;
            case "cat_content" -> CatContentStage.INSTANCE;
            case "docker_build_summary" -> DockerBuildSummaryStage.INSTANCE;
            case "kubectl_dispatch" -> KubectlDispatchStage.INSTANCE;
            case "cargo_clippy_summary" -> CargoClippySummaryStage.INSTANCE;
            case "cargo_install_summary" -> CargoInstallSummaryStage.INSTANCE;
            case "cargo_test_summary" -> CargoTestSummaryStage.INSTANCE;
            case "gradle_summary" -> GradleSummaryStage.INSTANCE;
            case "make_summary" -> MakeSummaryStage.INSTANCE;
            case "mvn_summary" -> MvnSummaryStage.INSTANCE;
            case "eslint_json" -> EsLintJsonStage.INSTANCE;
            case "eslint_text" -> EsLintTextStage.INSTANCE;
            case "jest_summary" -> JestSummaryStage.INSTANCE;
            case "npm_install_summary" -> NpmInstallSummaryStage.INSTANCE;
            case "tsc_summary" -> TscSummaryStage.INSTANCE;
            case "vitest_summary" -> VitestSummaryStage.INSTANCE;
            case "golangci_summary" -> GolangciSummaryStage.INSTANCE;
            case "pip_install_summary" -> PipInstallSummaryStage.INSTANCE;
            case "pytest_summary" -> PytestSummaryStage.INSTANCE;
            case "ruff_summary" -> RuffSummaryStage.INSTANCE;
            default -> null;
        };
    }

    public static void validate(String location, FilterOverrideConfig.StageDef stage, List<String> errors) {
        if (stage == null) {
            errors.add(location + ": Stage configuration is null");
            return;
        }
        String strategy = stage.strategy();
        if (strategy == null || strategy.isBlank()) {
            errors.add(location + ": Missing required 'strategy' field");
            return;
        }
        String normalized = normalize(strategy);
        if (!ALLOWED_ALIASES.contains(normalized)) {
            errors.add(location + ": Unknown strategy: '" + strategy + "'. Allowed strategies: " + CANONICAL_LIST);
            return;
        }
        switch (normalized) {
            case "deduplication", "dedup" -> {
                if (stage.windowSize() != null && (stage.windowSize() <= 0 || stage.windowSize() > 10000)) {
                    errors.add(location + ": 'window_size' must be between 1 and 10000, got: " + stage.windowSize());
                }
            }
            case "grouping", "group" -> validateRegex(location, "'pattern'", stage.pattern(), true, errors);
            case "state_machine", "state-machine" -> validateStateMachine(location, stage, errors);
            case "tail_lines", "tail-lines" -> {
                if (stage.maxLines() == null || stage.maxLines() < 1) {
                    errors.add(location + ": 'max_lines' must be >= 1");
                }
            }
            case "head_tail", "head-tail" -> {
                if (stage.head() == null || stage.head() < 0) {
                    errors.add(location + ": 'head' must be >= 0");
                }
                if (stage.tail() == null || stage.tail() < 0) {
                    errors.add(location + ": 'tail' must be >= 0");
                }
            }
            case "aggregate_by_key", "aggregate-by-key" -> {
                String key = stage.key() != null ? stage.key().trim().toLowerCase(Locale.ROOT) : "";
                if (!AggregateByKeyStage.KEY_PREFIX_BEFORE_COLON.equals(key)
                    && !AggregateByKeyStage.KEY_FILE_EXTENSION.equals(key)) {
                    errors.add(location + ": 'key' must be '" + AggregateByKeyStage.KEY_PREFIX_BEFORE_COLON
                        + "' or '" + AggregateByKeyStage.KEY_FILE_EXTENSION + "'");
                }
                if (stage.header() == null || stage.header().isBlank()) {
                    errors.add(location + ": 'header' must not be empty");
                }
                if (stage.topN() != null && (stage.topN() < 1 || stage.topN() > 10000)) {
                    errors.add(location + ": 'top_n' must be between 1 and 10000, got: " + stage.topN());
                }
            }
            case "regex_capture", "regex-capture" ->
                validateRegex(location, "'pattern'", stage.pattern(), false, errors);
            default -> {
            }
        }
    }

    public static Set<String> canonicalAliases() {
        Set<String> canonical = new LinkedHashSet<>();
        for (String alias : ALLOWED_ALIASES) {
            if (!alias.contains("-") && !Set.of("ansi", "tree", "json", "dedup", "group").contains(alias)) {
                canonical.add(alias);
            }
        }
        return canonical;
    }

    private static void validateRegex(
        String location,
        String field,
        String pattern,
        boolean requireCapture,
        List<String> errors
    ) {
        if (pattern == null || pattern.isBlank()) {
            if (requireCapture) {
                return;
            }
            errors.add(location + ": " + field + " must not be empty");
            return;
        }
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            errors.add(location + ": " + field + " regex exceeds maximum allowed length of "
                + MAX_PATTERN_LENGTH + " characters");
            return;
        }
        try {
            Pattern compiled = Pattern.compile(pattern);
            if (requireCapture && BoundedRegex.matcher(compiled, "").groupCount() < 1) {
                errors.add(location + ": " + field + " regex must contain at least one capture group (e.g. '(.*)')");
            }
        } catch (PatternSyntaxException e) {
            errors.add(location + ": Invalid regex in " + field + ": " + e.getMessage());
        }
    }

    private static void validateStateMachine(
        String location,
        FilterOverrideConfig.StageDef stage,
        List<String> errors
    ) {
        if (stage.initialState() == null || stage.initialState().isBlank()) {
            errors.add(location + ": 'initial_state' must not be empty");
        }
        if (stage.transitions() != null) {
            if (stage.transitions().size() > MAX_TRANSITIONS_COUNT) {
                errors.add(location + ": 'transitions' count exceeds maximum allowed limit of "
                    + MAX_TRANSITIONS_COUNT);
            }
            for (int tIdx = 0; tIdx < stage.transitions().size(); tIdx++) {
                FilterOverrideConfig.TransitionDef t = stage.transitions().get(tIdx);
                String tLoc = location + ".transitions[" + tIdx + "]";
                if (t.fromState() == null || t.fromState().isBlank()) {
                    errors.add(tLoc + ": 'from_state' must not be empty");
                }
                if (t.nextState() == null || t.nextState().isBlank()) {
                    errors.add(tLoc + ": 'next_state' must not be empty");
                }
                if (t.pattern() == null || t.pattern().isBlank()) {
                    errors.add(tLoc + ": 'pattern' must not be empty");
                } else if (t.pattern().length() > MAX_PATTERN_LENGTH) {
                    errors.add(tLoc + ": 'pattern' regex exceeds maximum allowed length of "
                        + MAX_PATTERN_LENGTH + " characters");
                } else {
                    try {
                        Pattern.compile(t.pattern());
                    } catch (PatternSyntaxException e) {
                        errors.add(tLoc + ": Invalid regex in 'pattern': " + e.getMessage());
                    }
                }
                if (t.action() != null && !t.action().isBlank()) {
                    try {
                        StateMachineStrategy.Action.valueOf(t.action().trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        errors.add(tLoc + ": Invalid 'action': '" + t.action()
                            + "'. Allowed: EMIT, DISCARD, COLLECT");
                    }
                }
            }
        }
        if (stage.defaultActions() != null) {
            for (Map.Entry<String, String> entry : stage.defaultActions().entrySet()) {
                String act = entry.getValue();
                if (act != null && !act.isBlank()) {
                    try {
                        StateMachineStrategy.Action.valueOf(act.trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        errors.add(location + ".default_actions['" + entry.getKey()
                            + "']: Invalid action: '" + act + "'. Allowed: EMIT, DISCARD, COLLECT");
                    }
                }
            }
        }
    }

    private static FilterStage buildStateMachine(FilterOverrideConfig.StageDef stageDef) {
        String initialState = stageDef.initialState() != null ? stageDef.initialState().trim() : "START";
        StateMachineStrategy.Builder builder = StateMachineStrategy.builder(initialState);
        if (stageDef.transitions() != null) {
            for (FilterOverrideConfig.TransitionDef t : stageDef.transitions()) {
                if (t.fromState() == null || t.pattern() == null || t.nextState() == null) {
                    continue;
                }
                Pattern p = Pattern.compile(t.pattern());
                StateMachineStrategy.Action action = parseAction(t.action());
                builder.on(t.fromState().trim(), p, action, t.nextState().trim(), REGEX_TIMEOUT_MS);
            }
        }
        if (stageDef.defaultActions() != null) {
            for (Map.Entry<String, String> entry : stageDef.defaultActions().entrySet()) {
                builder.defaultAction(entry.getKey().trim(), parseAction(entry.getValue()));
            }
        }
        return builder.build();
    }

    private static StateMachineStrategy.Action parseAction(String actionStr) {
        if (actionStr == null || actionStr.isBlank()) {
            return StateMachineStrategy.Action.EMIT;
        }
        try {
            return StateMachineStrategy.Action.valueOf(actionStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return StateMachineStrategy.Action.EMIT;
        }
    }
}
