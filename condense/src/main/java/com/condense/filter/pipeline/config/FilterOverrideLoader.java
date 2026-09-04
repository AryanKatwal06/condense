package com.condense.filter.pipeline.config;

import com.condense.core.PlatformDirs;
import com.condense.core.SafePathValidator;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.trust.FilterRisk;
import com.condense.trust.TrustGate;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;

/**
 * Loads and validates declarative {@link FilterPipeline} overrides from TOML files.
 *
 * <p>Implements a 3-tier precedence model:
 * <ol>
 *   <li>Project-local override ({@code .condense/filters.toml} in project directory)</li>
 *   <li>User-global override ({@code filters.toml} in platform config directory)</li>
 *   <li>Built-in definition ({@code classpath:filters/&lt;name&gt;.toml} via {@code BuiltinDefinitionCatalog})</li>
 * </ol>
 *
 * <p>Operates under a strict fail-open contract: if any override file is invalid,
 * unreadable, or violates security constraints, a warning is logged and the loader
 * safely falls back to the next precedence tier without crashing or interrupting execution.
 */
@ApplicationScoped
public class FilterOverrideLoader {

    private static final Logger log = Logger.getLogger(FilterOverrideLoader.class);
    private static final TomlMapper TOML = DefinitionMappers.STRICT_TOML;

    public static final String PROJECT_OVERRIDE_REL_PATH = ".condense/filters.toml";
    public static final String GLOBAL_OVERRIDE_FILE_NAME = "filters.toml";

    /** Maximum allowed regex execution time in milliseconds for untrusted declarative patterns. */
    public static final long OVERRIDE_REGEX_TIMEOUT_MS = StageFactory.REGEX_TIMEOUT_MS;

    /** Maximum allowed character length for any declarative regex pattern. */
    public static final int MAX_PATTERN_LENGTH = StageFactory.MAX_PATTERN_LENGTH;

    /** Maximum allowed number of transitions in a single state machine strategy. */
    public static final int MAX_TRANSITIONS_COUNT = StageFactory.MAX_TRANSITIONS_COUNT;

    public static final Set<String> ALLOWED_STRATEGIES = StageFactory.ALLOWED_ALIASES;

    private final PlatformDirs platformDirs;
    private final TrustGate trustGate;
    private final Map<Path, CachedOverride> projectConfigCache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile CachedOverride globalConfigCache = null;
    private final Object globalCacheLock = new Object();

    private static final class StandaloneHolder {
        private static final FilterOverrideLoader INSTANCE = new FilterOverrideLoader();
    }

    /**
     * Shared loader for no-arg filter constructors (corpus, unit tests).
     * Production CDI uses the {@code @ApplicationScoped} bean instead.
     */
    public static FilterOverrideLoader standalone() {
        return StandaloneHolder.INSTANCE;
    }

    public FilterOverrideLoader() {
        this(new PlatformDirs());
    }

    @Inject
    public FilterOverrideLoader(PlatformDirs platformDirs) {
        this(platformDirs, platformDirs == null ? new TrustGate() : new TrustGate(platformDirs));
    }

    public FilterOverrideLoader(PlatformDirs platformDirs, TrustGate trustGate) {
        this.platformDirs = platformDirs;
        this.trustGate = trustGate != null ? trustGate : new TrustGate(platformDirs);
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

        // Tier 3: Builtin classpath definition (already compiled into defaultPipeline)
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

        if (expectedParent != null) {
            SafePathValidator.ContainmentResult containment = SafePathValidator.contain(file, expectedParent);
            if (!containment.contained()) {
                return ParsedFileResult.invalid(FilterOverrideValidationResult.securityViolation(
                    file, containment.reason()));
            }
        }

        byte[] sourceBytes;
        try {
            sourceBytes = Files.readAllBytes(file);
        } catch (Exception e) {
            return ParsedFileResult.invalid(FilterOverrideValidationResult.syntaxError(
                file, formatParseError("Cannot read override file", e)));
        }
        return parseAndValidateBytes(file, sourceBytes);
    }

    /**
     * Validates already-read bytes. Callers that must hash the displayed buffer
     * should read once and pass that same array here.
     */
    public ParsedFileResult parseAndValidateBytes(Path file, byte[] sourceBytes) {
        FilterOverrideConfig.FileConfig config;
        try {
            config = TOML.readValue(sourceBytes == null ? new byte[0] : sourceBytes, FilterOverrideConfig.FileConfig.class);
            if (config == null || config.filters() == null) {
                return ParsedFileResult.of(FilterOverrideValidationResult.valid(file, 0), config, sourceBytes);
            }
        } catch (UnrecognizedPropertyException e) {
            String text = sourceText(sourceBytes);
            return ParsedFileResult.invalid(FilterOverrideValidationResult.semanticError(
                file, List.of(unknownKeyError(e, text).format())));
        } catch (JsonMappingException e) {
            UnrecognizedPropertyException unknown = findUnknownKey(e);
            if (unknown != null) {
                String text = sourceText(sourceBytes);
                return ParsedFileResult.invalid(FilterOverrideValidationResult.semanticError(
                    file, List.of(unknownKeyError(unknown, text).format())));
            }
            return ParsedFileResult.invalid(FilterOverrideValidationResult.syntaxError(
                file, formatParseError("TOML parse error", e)));
        } catch (Exception e) {
            return ParsedFileResult.invalid(FilterOverrideValidationResult.syntaxError(
                file, formatParseError("TOML parse error", e)));
        }

        List<String> errors = new ArrayList<>();
        if (config.schemaVersion() == null || config.schemaVersion() != FilterOverrideConfig.SCHEMA_VERSION) {
            errors.add(new DefinitionError(
                "schema_version",
                null,
                null,
                "schema_version is required and must be " + FilterOverrideConfig.SCHEMA_VERSION
                    + (config.schemaVersion() == null ? "" : ", got " + config.schemaVersion())
            ).format());
        }

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
                String location = "[filters.\"" + filterKey + "\".stages[" + i + "]]";
                StageFactory.validate(location, stage, errors);
            }
            filterCount++;
        }

        if (!errors.isEmpty()) {
            return ParsedFileResult.invalid(FilterOverrideValidationResult.semanticError(file, errors));
        }

        return ParsedFileResult.of(FilterOverrideValidationResult.valid(file, filterCount), config, sourceBytes);
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

            Path canonical = projectOverrideFile.toRealPath();
            byte[] bytes = parsed.sourceBytes() != null ? parsed.sourceBytes() : Files.readAllBytes(canonical);
            var required = FilterRisk.requiredCapabilities(parsed.fileConfig());
            TrustGate.Result decision = trustGate.decide(canonical, bytes, required);
            if (!decision.apply()) {
                log.debugf("Skipping project filter override at %s (%s)", projectOverrideFile, decision.reason());
                System.err.println(TrustGate.SKIP_HINT);
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
        FilterOverrideConfig.FileConfig fileConfig,
        byte[] sourceBytes
    ) {
        public static ParsedFileResult of(
            FilterOverrideValidationResult result,
            FilterOverrideConfig.FileConfig config,
            byte[] sourceBytes
        ) {
            return new ParsedFileResult(result, config, sourceBytes);
        }

        public static ParsedFileResult invalid(FilterOverrideValidationResult result) {
            return new ParsedFileResult(result, null, null);
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
        return StageFactory.buildPipeline(filterDef.stages());
    }

    private static UnrecognizedPropertyException findUnknownKey(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof UnrecognizedPropertyException unknown) {
                return unknown;
            }
            current = current.getCause();
        }
        return null;
    }

    static DefinitionError unknownKeyError(UnrecognizedPropertyException e) {
        return unknownKeyError(e, null);
    }

    static DefinitionError unknownKeyError(UnrecognizedPropertyException e, String source) {
        String path = jacksonPath(e);
        JsonLocation loc = e.getLocation();
        Integer line = loc != null && loc.getLineNr() > 0 ? loc.getLineNr() : null;
        Integer column = loc != null && loc.getColumnNr() > 0 ? loc.getColumnNr() : null;
        if (line == null && source != null && e.getPropertyName() != null) {
            int found = findKeyLine(source, e.getPropertyName());
            if (found > 0) {
                line = found;
            }
        }
        return new DefinitionError(path, line, column, "Unknown key '" + e.getPropertyName() + "'");
    }

    static int findKeyLine(String source, String key) {
        if (source == null || key == null || key.isBlank()) {
            return -1;
        }
        String[] lines = source.split("\\R", -1);
        String prefix = key + " ";
        String equals = key + "=";
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.equals(key) || trimmed.startsWith(prefix) || trimmed.startsWith(equals)
                || trimmed.startsWith(key + "\t")) {
                return i + 1;
            }
        }
        return -1;
    }

    private static String sourceText(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    static String formatParseError(String prefix, Exception e) {
        JsonLocation loc = null;
        if (e instanceof JsonMappingException mapping) {
            loc = mapping.getLocation();
        }
        String message = prefix + ": " + e.getMessage();
        if (loc != null && loc.getLineNr() > 0) {
            return message + " (line " + loc.getLineNr() + ", col " + loc.getColumnNr() + ")";
        }
        return message;
    }

    private static String jacksonPath(UnrecognizedPropertyException e) {
        if (e.getPath() == null || e.getPath().isEmpty()) {
            return e.getPropertyName() != null ? e.getPropertyName() : "$";
        }
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference ref : e.getPath()) {
            if (ref.getFieldName() != null) {
                if (path.isEmpty()) {
                    path.append(ref.getFieldName());
                } else {
                    path.append('.').append(ref.getFieldName());
                }
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        if (e.getPropertyName() != null) {
            String current = path.toString();
            if (current.isEmpty()) {
                path.append(e.getPropertyName());
            } else if (!current.equals(e.getPropertyName()) && !current.endsWith("." + e.getPropertyName())) {
                path.append('.').append(e.getPropertyName());
            }
        }
        return path.isEmpty() ? "$" : path.toString();
    }
}
