package com.condense.filter.pipeline.config;

import com.condense.core.Mappers;
import com.condense.core.PlatformDirs;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.strategy.*;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Loads and validates declarative {@link FilterPipeline} overrides from TOML files.
 *
 * <p>Implements a 3-tier precedence model:
 * <ol>
 *   <li>Project-local override ({@code .condense/filters.toml} in project directory)</li>
 *   <li>User-global override ({@code filters.toml} in platform config directory)</li>
 *   <li>Built-in compiled default (the compiled Java pipeline)</li>
 * </ol>
 *
 * <p>Operates under a strict fail-open contract: if any override file is invalid,
 * unreadable, or violates security constraints, a warning is logged and the loader
 * safely falls back to the next precedence tier without crashing or interrupting execution.
 */
@ApplicationScoped
public class FilterOverrideLoader {

    private static final Logger log = Logger.getLogger(FilterOverrideLoader.class);
    private static final TomlMapper TOML = Mappers.TOML;

    public static final String PROJECT_OVERRIDE_REL_PATH = ".condense/filters.toml";
    public static final String GLOBAL_OVERRIDE_FILE_NAME = "filters.toml";

    /** Maximum allowed regex execution time in milliseconds for untrusted declarative patterns. */
    public static final long OVERRIDE_REGEX_TIMEOUT_MS = 200L;

    /** Maximum allowed character length for any declarative regex pattern. */
    public static final int MAX_PATTERN_LENGTH = 500;

    /** Maximum allowed number of transitions in a single state machine strategy. */
    public static final int MAX_TRANSITIONS_COUNT = 50;

    public static final Set<String> ALLOWED_STRATEGIES = Set.of(
        "ansi_strip", "ansi-strip", "ansi",
        "tree_compression", "tree-compression", "tree",
        "json_structure", "json-structure", "json",
        "deduplication", "dedup",
        "grouping", "group",
        "state_machine", "state-machine"
    );

    private final PlatformDirs platformDirs;
    private final Map<Path, CachedOverride> projectConfigCache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile CachedOverride globalConfigCache = null;
    private final Object globalCacheLock = new Object();

    public FilterOverrideLoader() {
        this(new PlatformDirs());
    }

    @Inject
    public FilterOverrideLoader(PlatformDirs platformDirs) {
        this.platformDirs = platformDirs;
    }

    /**
     * Resolves the active {@link FilterPipeline} for the given command using the default working directory.
     *
     * @param command         the command name or invocation (e.g. "ls", "npm install")
     * @param defaultPipeline the built-in default pipeline
     * @return the resolved pipeline, or defaultPipeline if no valid override exists
     */
    public FilterPipeline resolvePipeline(String command, FilterPipeline defaultPipeline) {
        return resolvePipeline(command, defaultPipeline, Path.of(System.getProperty("user.dir", ".")));
    }

    /**
     * Resolves the active {@link FilterPipeline} for the given command and project working directory.
     * Uses in-memory caching to avoid redundant filesystem I/O and TOML parsing on repeated invocations.
     *
     * @param command         the command name or invocation
     * @param defaultPipeline the built-in default pipeline
     * @param projectDir      the project working directory
     * @return the resolved pipeline, or defaultPipeline if no valid override exists
     */
    public FilterPipeline resolvePipeline(String command, FilterPipeline defaultPipeline, Path projectDir) {
        if (command == null || command.isBlank()) {
            return defaultPipeline;
        }

        String normalizedCmd = command.trim().toLowerCase(Locale.ROOT);

        // Tier 1: Project-local override (.condense/filters.toml)
        if (projectDir != null) {
            Path normalizedProjectDir = projectDir.toAbsolutePath().normalize();
            CachedOverride projectCached = projectConfigCache.computeIfAbsent(
                normalizedProjectDir,
                this::loadProjectOverride
            );
            FilterPipeline projectPipeline = projectCached.getOrCreatePipeline(normalizedCmd, this);
            if (projectPipeline != null) {
                log.debugf("Applied project-local override for '%s' from %s", command, normalizedProjectDir.resolve(PROJECT_OVERRIDE_REL_PATH));
                return projectPipeline;
            }
        }

        // Tier 2: User-global override ($CONFIG_DIR/filters.toml)
        if (platformDirs != null) {
            CachedOverride globalCached = globalConfigCache;
            if (globalCached == null) {
                synchronized (globalCacheLock) {
                    globalCached = globalConfigCache;
                    if (globalCached == null) {
                        globalCached = loadGlobalOverride();
                        globalConfigCache = globalCached;
                    }
                }
            }
            FilterPipeline globalPipeline = globalCached.getOrCreatePipeline(normalizedCmd, this);
            if (globalPipeline != null) {
                log.debugf("Applied user-global override for '%s'", command);
                return globalPipeline;
            }
        }

        // Tier 3: Built-in compiled default
        return defaultPipeline;
    }

    /**
     * Clears all cached filter override configurations and compiled pipelines.
     * Used in tests and when configuration files are modified or deleted.
     */
    public void invalidateCache() {
        projectConfigCache.clear();
        synchronized (globalCacheLock) {
            globalConfigCache = null;
        }
    }

    /**
     * Clears cached filter overrides for a specific project directory.
     *
     * @param projectDir the project directory whose cache should be invalidated
     */
    public void invalidateCache(Path projectDir) {
        if (projectDir != null) {
            projectConfigCache.remove(projectDir.toAbsolutePath().normalize());
        }
    }

    /**
     * Validates and parses a candidate filter override file at the given path against its expected parent directory.
     * Performs a single TOML parse via Jackson and applies both syntax and semantic validation.
     *
     * @param file           the file path to validate
     * @param expectedParent the parent directory the file must reside in (for symlink/traversal checks)
     * @return structured validation result paired with the parsed FileConfig (if valid)
     */
    public ParsedFileResult parseAndValidateFile(Path file, Path expectedParent) {
        if (file == null) {
            return ParsedFileResult.invalid(FilterOverrideValidationResult.error(null, "Target path is null"));
        }

        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return ParsedFileResult.invalid(FilterOverrideValidationResult.notFound(file));
        }

        // Security check: canonicalize and verify path safety
        try {
            Path realFile = file.toRealPath();
            if (expectedParent != null) {
                Path realParent = Files.exists(expectedParent)
                    ? expectedParent.toRealPath()
                    : expectedParent.toAbsolutePath().normalize();

                if (!realFile.startsWith(realParent)) {
                    return ParsedFileResult.invalid(FilterOverrideValidationResult.securityViolation(
                        file,
                        "Path traversal or symlink escape: file '" + realFile + "' resolves outside expected directory '" + realParent + "'"
                    ));
                }
            }
        } catch (IOException e) {
            return ParsedFileResult.invalid(FilterOverrideValidationResult.securityViolation(
                file,
                "Cannot resolve canonical path for '" + file + "': " + e.getMessage()
            ));
        }

        // Syntax validation: parse TOML once
        FilterOverrideConfig.FileConfig config;
        try {
            config = TOML.readValue(file.toFile(), FilterOverrideConfig.FileConfig.class);
            if (config == null || config.filters() == null) {
                return ParsedFileResult.of(FilterOverrideValidationResult.valid(file, 0), config);
            }
        } catch (Exception e) {
            return ParsedFileResult.invalid(FilterOverrideValidationResult.syntaxError(file, "TOML parse error: " + e.getMessage()));
        }

        // Semantic validation: inspect filters and stages
        List<String> errors = new ArrayList<>();
        int filterCount = 0;

        for (Map.Entry<String, FilterOverrideConfig.FilterDef> entry : config.filters().entrySet()) {
            String filterKey = entry.getKey();
            if (filterKey == null || filterKey.isBlank()) {
                errors.add("Filter command key must not be empty or blank");
                continue;
            }

            FilterOverrideConfig.FilterDef filterDef = entry.getValue();
            if (filterDef == null || filterDef.stages() == null) {
                filterCount++;
                continue;
            }

            List<FilterOverrideConfig.StageDef> stages = filterDef.stages();
            for (int i = 0; i < stages.size(); i++) {
                FilterOverrideConfig.StageDef stage = stages.get(i);
                validateStage(filterKey, i, stage, errors);
            }
            filterCount++;
        }

        if (!errors.isEmpty()) {
            return ParsedFileResult.invalid(FilterOverrideValidationResult.semanticError(file, errors));
        }

        return ParsedFileResult.of(FilterOverrideValidationResult.valid(file, filterCount), config);
    }

    /**
     * Validates a candidate filter override file at the given path against its expected parent directory.
     *
     * @param file           the file path to validate
     * @param expectedParent the parent directory the file must reside in (for symlink/traversal checks)
     * @return structured validation result
     */
    public FilterOverrideValidationResult validateFile(Path file, Path expectedParent) {
        return parseAndValidateFile(file, expectedParent).validationResult();
    }

    /**
     * Validates the project-local override file in the specified project directory.
     */
    public FilterOverrideValidationResult validateProjectOverrides(Path projectDir) {
        Path targetDir = projectDir != null ? projectDir : Path.of(System.getProperty("user.dir", "."));
        Path file = targetDir.resolve(PROJECT_OVERRIDE_REL_PATH);
        return validateFile(file, targetDir);
    }

    /**
     * Validates the user-global override file in the resolved configuration directory.
     */
    public FilterOverrideValidationResult validateGlobalOverrides() {
        if (platformDirs == null) {
            return FilterOverrideValidationResult.error(null, "PlatformDirs is null");
        }
        Path configDir = platformDirs.resolveConfigDir();
        if (configDir == null) {
            return FilterOverrideValidationResult.notFound(null);
        }
        Path file = configDir.resolve(GLOBAL_OVERRIDE_FILE_NAME);
        return validateFile(file, configDir);
    }

    private CachedOverride loadProjectOverride(Path normalizedProjectDir) {
        Path projectOverrideFile = normalizedProjectDir.resolve(PROJECT_OVERRIDE_REL_PATH);
        try {
            if (!Files.exists(projectOverrideFile)) {
                return CachedOverride.EMPTY;
            }

            ParsedFileResult parsed = parseAndValidateFile(projectOverrideFile, normalizedProjectDir);
            if (!parsed.validationResult().isValid()) {
                log.warnf("Filter override file at %s failed validation (%s): %s",
                    projectOverrideFile, parsed.validationResult().status(),
                    String.join("; ", parsed.validationResult().errors()));
                return CachedOverride.EMPTY;
            }

            if (parsed.fileConfig() == null || parsed.fileConfig().filters() == null) {
                return CachedOverride.EMPTY;
            }

            return new CachedOverride(true, parsed.fileConfig());
        } catch (Exception e) {
            log.warnf("Unexpected error reading filter override from %s: %s", projectOverrideFile, e.getMessage());
            return CachedOverride.EMPTY;
        }
    }

    private CachedOverride loadGlobalOverride() {
        Path configDir = platformDirs != null ? platformDirs.resolveConfigDir() : null;
        if (configDir == null) {
            return CachedOverride.EMPTY;
        }
        Path globalOverrideFile = configDir.resolve(GLOBAL_OVERRIDE_FILE_NAME);
        try {
            if (!Files.exists(globalOverrideFile)) {
                return CachedOverride.EMPTY;
            }

            ParsedFileResult parsed = parseAndValidateFile(globalOverrideFile, configDir);
            if (!parsed.validationResult().isValid()) {
                log.warnf("Filter override file at %s failed validation (%s): %s",
                    globalOverrideFile, parsed.validationResult().status(),
                    String.join("; ", parsed.validationResult().errors()));
                return CachedOverride.EMPTY;
            }

            if (parsed.fileConfig() == null || parsed.fileConfig().filters() == null) {
                return CachedOverride.EMPTY;
            }

            return new CachedOverride(true, parsed.fileConfig());
        } catch (Exception e) {
            log.warnf("Unexpected error reading filter override from %s: %s", globalOverrideFile, e.getMessage());
            return CachedOverride.EMPTY;
        }
    }

    static final class CachedOverride {
        static final CachedOverride EMPTY = new CachedOverride(false, null);

        final boolean exists;
        final FilterOverrideConfig.FileConfig config;
        final java.util.concurrent.ConcurrentHashMap<String, FilterPipeline> pipelineCache;

        CachedOverride(boolean exists, FilterOverrideConfig.FileConfig config) {
            this.exists = exists;
            this.config = config;
            this.pipelineCache = new java.util.concurrent.ConcurrentHashMap<>();
        }

        boolean hasPipelines() {
            return exists && config != null && config.filters() != null && !config.filters().isEmpty();
        }

        FilterPipeline getOrCreatePipeline(String command, FilterOverrideLoader loader) {
            if (!hasPipelines()) {
                return null;
            }
            FilterPipeline cached = pipelineCache.get(command);
            if (cached != null) {
                return cached;
            }
            FilterOverrideConfig.FilterDef def = loader.findMatchingFilterDef(config.filters(), command);
            if (def == null) {
                return null;
            }
            FilterPipeline built = loader.buildPipelineFromDef(def);
            if (built != null) {
                pipelineCache.put(command, built);
            }
            return built;
        }
    }

    public record ParsedFileResult(
        FilterOverrideValidationResult validationResult,
        FilterOverrideConfig.FileConfig fileConfig
    ) {
        public static ParsedFileResult of(FilterOverrideValidationResult result, FilterOverrideConfig.FileConfig config) {
            return new ParsedFileResult(result, config);
        }

        public static ParsedFileResult invalid(FilterOverrideValidationResult result) {
            return new ParsedFileResult(result, null);
        }
    }

    private FilterOverrideConfig.FilterDef findMatchingFilterDef(
        Map<String, FilterOverrideConfig.FilterDef> filters,
        String command
    ) {
        if (filters.containsKey(command)) {
            return filters.get(command);
        }

        // Try normalized key lookup
        for (Map.Entry<String, FilterOverrideConfig.FilterDef> entry : filters.entrySet()) {
            String key = entry.getKey().trim().toLowerCase(Locale.ROOT);
            if (key.equals(command)) {
                return entry.getValue();
            }
        }

        // Try prefix match for compound commands (e.g. "npm install" matching "npm install --verbose")
        for (Map.Entry<String, FilterOverrideConfig.FilterDef> entry : filters.entrySet()) {
            String key = entry.getKey().trim().toLowerCase(Locale.ROOT);
            if (command.startsWith(key + " ")) {
                return entry.getValue();
            }
        }

        return null;
    }

    private FilterPipeline buildPipelineFromDef(FilterOverrideConfig.FilterDef filterDef) {
        FilterPipeline.Builder builder = FilterPipeline.builder();
        if (filterDef.stages() == null || filterDef.stages().isEmpty()) {
            return builder.build();
        }

        for (FilterOverrideConfig.StageDef stageDef : filterDef.stages()) {
            FilterStage stage = instantiateStage(stageDef);
            if (stage != null) {
                builder.addStage(stage);
            }
        }

        return builder.build();
    }

    private FilterStage instantiateStage(FilterOverrideConfig.StageDef stageDef) {
        String strategyName = stageDef.strategy() != null
            ? stageDef.strategy().trim().toLowerCase(Locale.ROOT)
            : "";

        return switch (strategyName) {
            case "ansi_strip", "ansi-strip", "ansi" ->
                AnsiStripStrategy.INSTANCE;

            case "tree_compression", "tree-compression", "tree" ->
                TreeCompressionStrategy.INSTANCE;

            case "json_structure", "json-structure", "json" ->
                JsonStructureStrategy.INSTANCE;

            case "deduplication", "dedup" -> {
                int window = (stageDef.windowSize() != null && stageDef.windowSize() > 0)
                    ? stageDef.windowSize()
                    : 50;
                yield new DeduplicationStrategy(window);
            }

            case "grouping", "group" -> {
                Pattern pattern = stageDef.pattern() != null && !stageDef.pattern().isBlank()
                    ? Pattern.compile(stageDef.pattern())
                    : Pattern.compile("(.*)");
                boolean includeOther = Boolean.TRUE.equals(stageDef.includeOther());
                yield new GroupingStrategy(pattern, includeOther, OVERRIDE_REGEX_TIMEOUT_MS);
            }

            case "state_machine", "state-machine" ->
                buildStateMachine(stageDef);

            default -> {
                log.warnf("Cannot instantiate unknown strategy '%s'", stageDef.strategy());
                yield null;
            }
        };
    }

    private FilterStage buildStateMachine(FilterOverrideConfig.StageDef stageDef) {
        String initialState = stageDef.initialState() != null ? stageDef.initialState().trim() : "START";
        StateMachineStrategy.Builder builder = StateMachineStrategy.builder(initialState);

        if (stageDef.transitions() != null) {
            for (FilterOverrideConfig.TransitionDef t : stageDef.transitions()) {
                if (t.fromState() == null || t.pattern() == null || t.nextState() == null) {
                    continue;
                }
                Pattern p = Pattern.compile(t.pattern());
                StateMachineStrategy.Action action = parseAction(t.action());
                builder.on(t.fromState().trim(), p, action, t.nextState().trim(), OVERRIDE_REGEX_TIMEOUT_MS);
            }
        }

        if (stageDef.defaultActions() != null) {
            for (Map.Entry<String, String> entry : stageDef.defaultActions().entrySet()) {
                StateMachineStrategy.Action action = parseAction(entry.getValue());
                builder.defaultAction(entry.getKey().trim(), action);
            }
        }

        return builder.build();
    }

    private StateMachineStrategy.Action parseAction(String actionStr) {
        if (actionStr == null || actionStr.isBlank()) {
            return StateMachineStrategy.Action.EMIT;
        }
        try {
            return StateMachineStrategy.Action.valueOf(actionStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return StateMachineStrategy.Action.EMIT;
        }
    }

    private void validateStage(
        String filterKey,
        int stageIndex,
        FilterOverrideConfig.StageDef stage,
        List<String> errors
    ) {
        String location = String.format("[filters.\"%s\".stages[%d]]", filterKey, stageIndex);

        if (stage == null) {
            errors.add(location + " Stage configuration is null");
            return;
        }

        String strategy = stage.strategy();
        if (strategy == null || strategy.isBlank()) {
            errors.add(location + " Missing required 'strategy' field");
            return;
        }

        String normalized = strategy.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_STRATEGIES.contains(normalized)) {
            errors.add(location + " Unknown strategy: '" + strategy + "'. Allowed strategies: " +
                "ansi_strip, tree_compression, json_structure, deduplication, grouping, state_machine");
            return;
        }

        switch (normalized) {
            case "deduplication", "dedup" -> {
                if (stage.windowSize() != null) {
                    if (stage.windowSize() <= 0 || stage.windowSize() > 10000) {
                        errors.add(location + " 'window_size' must be between 1 and 10000, got: " + stage.windowSize());
                    }
                }
            }

            case "grouping", "group" -> {
                if (stage.pattern() != null && !stage.pattern().isBlank()) {
                    if (stage.pattern().length() > MAX_PATTERN_LENGTH) {
                        errors.add(location + " 'pattern' regex exceeds maximum allowed length of " + MAX_PATTERN_LENGTH + " characters");
                    } else {
                        try {
                            Pattern p = Pattern.compile(stage.pattern());
                            if (p.matcher("").groupCount() < 1) {
                                errors.add(location + " 'pattern' regex must contain at least one capture group (e.g. '(.*)')");
                            }
                        } catch (PatternSyntaxException e) {
                            errors.add(location + " Invalid regex in 'pattern': " + e.getMessage());
                        }
                    }
                }
            }

            case "state_machine", "state-machine" -> {
                if (stage.initialState() == null || stage.initialState().isBlank()) {
                    errors.add(location + " 'initial_state' must not be empty");
                }
                if (stage.transitions() != null) {
                    if (stage.transitions().size() > MAX_TRANSITIONS_COUNT) {
                        errors.add(location + " 'transitions' count exceeds maximum allowed limit of " + MAX_TRANSITIONS_COUNT);
                    }
                    for (int tIdx = 0; tIdx < stage.transitions().size(); tIdx++) {
                        FilterOverrideConfig.TransitionDef t = stage.transitions().get(tIdx);
                        String tLoc = location + String.format(".transitions[%d]", tIdx);
                        if (t.fromState() == null || t.fromState().isBlank()) {
                            errors.add(tLoc + " 'from_state' must not be empty");
                        }
                        if (t.nextState() == null || t.nextState().isBlank()) {
                            errors.add(tLoc + " 'next_state' must not be empty");
                        }
                        if (t.pattern() == null || t.pattern().isBlank()) {
                            errors.add(tLoc + " 'pattern' must not be empty");
                        } else if (t.pattern().length() > MAX_PATTERN_LENGTH) {
                            errors.add(tLoc + " 'pattern' regex exceeds maximum allowed length of " + MAX_PATTERN_LENGTH + " characters");
                        } else {
                            try {
                                Pattern.compile(t.pattern());
                            } catch (PatternSyntaxException e) {
                                errors.add(tLoc + " Invalid regex in 'pattern': " + e.getMessage());
                            }
                        }
                        if (t.action() != null && !t.action().isBlank()) {
                            try {
                                StateMachineStrategy.Action.valueOf(t.action().trim().toUpperCase(Locale.ROOT));
                            } catch (IllegalArgumentException e) {
                                errors.add(tLoc + " Invalid 'action': '" + t.action() + "'. Allowed: EMIT, DISCARD, COLLECT");
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
                                errors.add(location + ".default_actions['" + entry.getKey() + "'] Invalid action: '" + act + "'. Allowed: EMIT, DISCARD, COLLECT");
                            }
                        }
                    }
                }
            }

            default -> {
                // Stateless strategies (ansi_strip, tree_compression, json_structure) take no parameters
            }
        }
    }
}
