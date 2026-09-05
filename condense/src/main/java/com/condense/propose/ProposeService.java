package com.condense.propose;

import com.condense.annotation.CommandFilter;
import com.condense.core.ProjectFingerprint;
import com.condense.core.StrategyRegistry;
import com.condense.core.TokenCounter;
import com.condense.core.TrackingRepository;
import com.condense.discover.DiscoverReport;
import com.condense.discover.DiscoverService;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.PipelineTrace;
import com.condense.filter.pipeline.config.BuiltinDefinition;
import com.condense.filter.pipeline.config.BuiltinDefinitionCatalog;
import com.condense.filter.pipeline.config.FilterOverrideConfig;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.pipeline.config.FilterOverrideValidationResult;
import com.condense.filter.pipeline.config.StageFactory;
import com.condense.filter.python.PythonFilter;
import com.condense.trust.Capability;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds reviewable project-override proposals. Never writes {@code filters.toml}
 * and is not on the proxy path.
 */
public final class ProposeService {

    private static final String SAMPLE_UNMATCHED = "line 1\nline 2\nline 3\n";

    private final DiscoverService discover;
    private final BuiltinDefinitionCatalog catalog;
    private final FilterOverrideLoader loader;
    private final ProposeLimits limits;
    private final Set<String> pythonPrefixes;
    private final Set<String> familyFirstTokens;

    public ProposeService() {
        this(
            new DiscoverService(),
            BuiltinDefinitionCatalog.standalone(),
            FilterOverrideLoader.standalone(),
            ProposeLimits.DEFAULT);
    }

    public ProposeService(
            DiscoverService discover,
            BuiltinDefinitionCatalog catalog,
            FilterOverrideLoader loader,
            ProposeLimits limits) {
        this.discover = discover == null ? new DiscoverService() : discover;
        this.catalog = catalog == null ? BuiltinDefinitionCatalog.standalone() : catalog;
        this.loader = loader == null ? FilterOverrideLoader.standalone() : loader;
        this.limits = limits == null ? ProposeLimits.DEFAULT : limits;
        this.pythonPrefixes = pythonPrefixes();
        this.familyFirstTokens = familyFirstTokens(this.catalog, this.pythonPrefixes);
    }

    public ProposeReport propose(Path cwd, Path rootOverride, TrackingRepository tracking) {
        DiscoverReport discovery = discover.discover(cwd, rootOverride);
        if (discovery.failed()) {
            return ProposeReport.failure(discovery.root(), discovery.error());
        }
        Path root = Path.of(discovery.root());
        Set<String> existing = loadExistingKeys(root);
        List<String> warnings = new ArrayList<>(discovery.warnings());
        List<ProposeReport.Proposal> proposals = new ArrayList<>();
        Set<String> claimed = new LinkedHashSet<>(existing);

        addCoverage(discovery, existing, claimed, proposals);

        boolean analyticsUnavailable = tracking == null
            || tracking.isMigrateFailed()
            || !tracking.databaseFileExists();
        if (!analyticsUnavailable) {
            try {
                addAnalytics(root, tracking, existing, claimed, proposals);
            } catch (RuntimeException e) {
                analyticsUnavailable = true;
                warnings.add("analytics unavailable: " + e.getMessage());
            }
        } else if (tracking != null && !tracking.databaseFileExists()) {
            warnings.add("analytics unavailable: no database");
        }

        proposals.sort(Comparator
            .comparing(ProposeReport.Proposal::kind)
            .thenComparing(ProposeReport.Proposal::command));
        boolean capped = false;
        if (proposals.size() > limits.maxProposals()) {
            proposals = new ArrayList<>(proposals.subList(0, limits.maxProposals()));
            warnings.add("truncated to " + limits.maxProposals() + " proposals");
            capped = true;
        }

        return new ProposeReport(
            ProposeReport.SCHEMA_VERSION,
            discovery.root(),
            discovery.recommend(),
            analyticsUnavailable,
            discovery.truncated() || capped,
            warnings,
            null,
            List.copyOf(proposals));
    }

    private void addCoverage(
            DiscoverReport discovery,
            Set<String> existing,
            Set<String> claimed,
            List<ProposeReport.Proposal> proposals) {
        for (String name : discovery.recommend()) {
            BuiltinDefinition definition = findDefinition(name);
            if (definition == null) {
                continue;
            }
            for (String command : definition.commands()) {
                String key = normalizeCommand(command);
                if (key.isEmpty()) {
                    continue;
                }
                if (existing.contains(key)) {
                    proposals.add(skipped(ProposeReport.KIND_COVERAGE, key, definition.name(),
                        "project override already has this command"));
                    claimed.add(key);
                    continue;
                }
                if (claimed.contains(key)) {
                    continue;
                }
                claimed.add(key);
                proposals.add(coverageProposal(definition, key));
            }
        }
    }

    ProposeReport.Proposal coverageForTest(String definitionName, String command) {
        BuiltinDefinition definition = findDefinition(definitionName);
        if (definition == null) {
            throw new IllegalArgumentException("unknown definition: " + definitionName);
        }
        return coverageProposal(definition, normalizeCommand(command));
    }

    private ProposeReport.Proposal coverageProposal(BuiltinDefinition definition, String command) {
        if (notRepresentable(definition)) {
            return proposal(
                ProposeReport.KIND_COVERAGE,
                ProposeReport.STATUS_BLOCKED_NOT_REPRESENTABLE,
                command,
                Capability.REDUCE.token(),
                "",
                new ProposeReport.Evidence(definition.name(), null, null, null,
                    "builtin uses select_input or gate which overrides cannot represent"),
                stageNames(definition.stages()),
                List.of(),
                0,
                0);
        }
        List<FilterOverrideConfig.StageDef> stages = definition.stages();
        String toml = ProposeToml.fragment(command, stages);
        List<String> failures = replayInline(definition, stages);
        if (!failures.isEmpty()) {
            return proposal(
                ProposeReport.KIND_COVERAGE,
                ProposeReport.STATUS_BLOCKED_INLINE_TEST,
                command,
                capabilityToken(stages),
                toml,
                new ProposeReport.Evidence(definition.name(), null, null, null,
                    String.join("; ", failures)),
                stageNames(stages),
                stageNames(stages),
                0,
                0);
        }
        Preview preview = preview(command, definition.stages(), stages, sampleInput(definition));
        return proposal(
            ProposeReport.KIND_COVERAGE,
            ProposeReport.STATUS_READY,
            command,
            capabilityToken(stages),
            toml,
            new ProposeReport.Evidence(definition.name(), null, null, null, "discover recommendation pin"),
            preview.before,
            preview.after,
            preview.rawTokens,
            preview.outTokens);
    }

    private void addAnalytics(
            Path root,
            TrackingRepository tracking,
            Set<String> existing,
            Set<String> claimed,
            List<ProposeReport.Proposal> proposals) {
        long since = Math.max(0L, (System.currentTimeMillis() / 1000L) - limits.lookbackSeconds());
        List<TrackingRepository.ProposeCommandRow> commandRows =
            tracking.queryProposeCommands(limits.maxCommandRows(), since);
        List<TrackingRepository.ProposeOutcomeRow> outcomeRows =
            tracking.queryProposeOutcomes(limits.maxOutcomeRows(), since);

        String rootFingerprint = ProjectFingerprint.of(root.toString());
        Set<String> scopedProjects = new LinkedHashSet<>();
        scopedProjects.add(rootFingerprint);
        List<TrackingRepository.ProposeCommandRow> scopedCommands = new ArrayList<>();
        for (TrackingRepository.ProposeCommandRow row : commandRows) {
            if (inScope(row.cwd(), row.project(), root, scopedProjects)) {
                scopedCommands.add(row);
                if (row.project() != null && !row.project().isBlank()) {
                    scopedProjects.add(row.project());
                }
            }
        }

        Map<String, Long> incidents = new LinkedHashMap<>();
        for (TrackingRepository.ProposeOutcomeRow row : outcomeRows) {
            if (!scopedProjects.contains(row.project() == null ? "" : row.project())
                && !commandMatchesScoped(row.command(), scopedCommands)) {
                continue;
            }
            String key = overrideKey(row.command());
            incidents.merge(key, 1L, Long::sum);
        }
        for (Map.Entry<String, Long> entry : incidents.entrySet()) {
            String key = entry.getKey();
            if (entry.getValue() < limits.minIncidents()) {
                continue;
            }
            if (existing.contains(key) || claimed.contains(key)) {
                if (existing.contains(key)) {
                    proposals.add(skipped(ProposeReport.KIND_SAFETY, key, null,
                        "project override already has this command"));
                }
                continue;
            }
            claimed.add(key);
            proposals.add(safetyProposal(key, entry.getValue()));
        }

        Map<String, long[]> unmatched = new LinkedHashMap<>();
        for (TrackingRepository.ProposeCommandRow row : scopedCommands) {
            String command = normalizeCommand(row.command());
            if (command.isEmpty() || hasFilter(command)) {
                continue;
            }
            String token = firstNonFlagToken(command);
            if (token.isEmpty() || "condense".equals(token) || familyFirstTokens.contains(token)) {
                continue;
            }
            long[] agg = unmatched.computeIfAbsent(token, ignored -> new long[2]);
            agg[0] += 1;
            agg[1] += Math.max(0, row.rawTokens());
        }
        for (Map.Entry<String, long[]> entry : unmatched.entrySet()) {
            String key = entry.getKey();
            long uses = entry.getValue()[0];
            long sumRaw = entry.getValue()[1];
            if (uses < limits.minRuns() || sumRaw < limits.minRawTokens()) {
                continue;
            }
            if (existing.contains(key) || claimed.contains(key)) {
                if (existing.contains(key)) {
                    proposals.add(skipped(ProposeReport.KIND_UNMATCHED, key, null,
                        "project override already has this command"));
                }
                continue;
            }
            claimed.add(key);
            proposals.add(unmatchedProposal(key, uses, sumRaw));
        }
    }

    private ProposeReport.Proposal safetyProposal(String command, long incidents) {
        List<FilterOverrideConfig.StageDef> stages = List.of();
        String toml = ProposeToml.fragment(command, stages);
        BuiltinDefinition current = catalog.findByCommand(command);
        Preview preview = preview(
            command,
            current == null ? List.of() : current.stages(),
            stages,
            current == null ? SAMPLE_UNMATCHED : sampleInput(current));
        return proposal(
            ProposeReport.KIND_SAFETY,
            ProposeReport.STATUS_READY,
            command,
            Capability.REDUCE.token(),
            toml,
            new ProposeReport.Evidence(
                current == null ? null : current.name(),
                null,
                incidents,
                null,
                "identity pipeline after repeated fail-open incidents"),
            preview.before.isEmpty() ? List.of("passthrough") : preview.before,
            List.of("identity"),
            preview.rawTokens,
            preview.outTokens);
    }

    private ProposeReport.Proposal unmatchedProposal(String command, long uses, long sumRaw) {
        List<FilterOverrideConfig.StageDef> stages = ProposeToml.unmatchedStages(limits.tailLines());
        String toml = ProposeToml.fragment(command, stages);
        Preview preview = preview(command, List.of(), stages, SAMPLE_UNMATCHED);
        return proposal(
            ProposeReport.KIND_UNMATCHED,
            ProposeReport.STATUS_READY,
            command,
            Capability.REDUCE.token(),
            toml,
            new ProposeReport.Evidence(null, uses, null, sumRaw,
                "high-volume command with no catalog filter"),
            List.of("passthrough"),
            preview.after,
            preview.rawTokens,
            preview.outTokens);
    }

    private ProposeReport.Proposal skipped(String kind, String command, String definition, String reason) {
        return proposal(
            kind,
            ProposeReport.STATUS_SKIPPED_EXISTING,
            command,
            Capability.REDUCE.token(),
            "",
            new ProposeReport.Evidence(definition, null, null, null, reason),
            List.of(),
            List.of(),
            0,
            0);
    }

    private ProposeReport.Proposal proposal(
            String kind,
            String status,
            String command,
            String capability,
            String toml,
            ProposeReport.Evidence evidence,
            List<String> before,
            List<String> after,
            int rawTokens,
            int outTokens) {
        return new ProposeReport.Proposal(
            proposalId(kind, command, toml),
            kind,
            status,
            command,
            capability,
            toml,
            evidence,
            before,
            after,
            rawTokens,
            outTokens);
    }

    private Set<String> loadExistingKeys(Path root) {
        Set<String> keys = new LinkedHashSet<>();
        Path file = root.resolve(FilterOverrideLoader.PROJECT_OVERRIDE_REL_PATH);
        FilterOverrideLoader.ParsedFileResult parsed = loader.parseAndValidateFile(file, root);
        FilterOverrideValidationResult result = parsed.validationResult();
        if (result == null || !result.isValid() || parsed.fileConfig() == null
            || parsed.fileConfig().filters() == null) {
            return keys;
        }
        for (String key : parsed.fileConfig().filters().keySet()) {
            String normalized = normalizeCommand(key);
            if (!normalized.isEmpty()) {
                keys.add(normalized);
            }
        }
        return keys;
    }

    Map<String, List<FilterOverrideConfig.StageDef>> readyFilters(ProposeReport report) {
        Map<String, List<FilterOverrideConfig.StageDef>> filters = new LinkedHashMap<>();
        if (report == null || report.failed()) {
            return filters;
        }
        Path root = report.root() == null ? null : Path.of(report.root());
        if (root != null) {
            Path file = root.resolve(FilterOverrideLoader.PROJECT_OVERRIDE_REL_PATH);
            FilterOverrideLoader.ParsedFileResult parsed = loader.parseAndValidateFile(file, root);
            if (parsed.validationResult() != null && parsed.validationResult().isValid()
                && parsed.fileConfig() != null && parsed.fileConfig().filters() != null) {
                for (Map.Entry<String, FilterOverrideConfig.FilterDef> entry
                    : parsed.fileConfig().filters().entrySet()) {
                    String key = normalizeCommand(entry.getKey());
                    List<FilterOverrideConfig.StageDef> stages = entry.getValue() == null
                        ? List.of()
                        : entry.getValue().stages();
                    filters.put(key, stages);
                }
            }
        }
        for (ProposeReport.Proposal proposal : report.proposals()) {
            if (!ProposeReport.STATUS_READY.equals(proposal.status())
                || proposal.toml() == null || proposal.toml().isBlank()) {
                continue;
            }
            if (filters.containsKey(proposal.command())) {
                continue;
            }
            try {
                FilterOverrideConfig.FileConfig parsed = ProposeToml.parse(
                    "schema_version = 1\n\n" + proposal.toml());
                FilterOverrideConfig.FilterDef def = parsed.filters().get(proposal.command());
                filters.put(proposal.command(), def == null ? List.of() : def.stages());
            } catch (Exception ignored) {
                // skip a fragment that cannot round-trip; the proposal stays in the report
            }
        }
        return filters;
    }

    private BuiltinDefinition findDefinition(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return catalog.requiredDefinition(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean notRepresentable(BuiltinDefinition definition) {
        if (definition.gate() != null) {
            return true;
        }
        String select = definition.selectInput();
        return select != null && !select.isBlank();
    }

    private static List<String> replayInline(
            BuiltinDefinition definition,
            List<FilterOverrideConfig.StageDef> stages) {
        List<String> failures = new ArrayList<>();
        FilterPipeline pipeline = StageFactory.buildPipeline(stages);
        for (BuiltinDefinition.InlineTest test : definition.tests()) {
            String id = test.id() != null ? test.id() : "(unnamed)";
            String input = test.input() != null ? test.input() : "";
            String expected = test.expected() != null ? test.expected() : "";
            String actual = pipeline.execute(input, FilterContext.empty());
            if (!normalizeNewlines(expected).equals(normalizeNewlines(actual))) {
                failures.add(definition.name() + "/" + id + " did not match");
            }
        }
        return failures;
    }

    private static Preview preview(
            String command,
            List<FilterOverrideConfig.StageDef> before,
            List<FilterOverrideConfig.StageDef> after,
            String sample) {
        String input = sample == null ? "" : sample;
        FilterPipeline pipeline = StageFactory.buildPipeline(after);
        PipelineTrace trace = pipeline.executeTraced(input, FilterContext.of(command, null, null, 0, false));
        return new Preview(
            stageNames(before),
            stageNames(after),
            TokenCounter.count(input),
            TokenCounter.count(trace.output()));
    }

    private static String sampleInput(BuiltinDefinition definition) {
        if (definition.tests() == null || definition.tests().isEmpty()) {
            return SAMPLE_UNMATCHED;
        }
        BuiltinDefinition.InlineTest test = definition.tests().getFirst();
        return test.input() == null ? SAMPLE_UNMATCHED : test.input();
    }

    private static List<String> stageNames(List<FilterOverrideConfig.StageDef> stages) {
        if (stages == null || stages.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (FilterOverrideConfig.StageDef stage : stages) {
            if (stage != null && stage.strategy() != null && !stage.strategy().isBlank()) {
                names.add(stage.strategy());
            }
        }
        return names;
    }

    static String capabilityToken(List<FilterOverrideConfig.StageDef> stages) {
        Set<Capability> required = StageFactory.requiredCapabilities(stages);
        if (required.contains(Capability.REWRITE)) {
            return Capability.REWRITE.token();
        }
        if (required.contains(Capability.RESHAPE)) {
            return Capability.RESHAPE.token();
        }
        return Capability.REDUCE.token();
    }

    boolean hasFilter(String command) {
        if (catalog.findByCommand(command) != null) {
            return true;
        }
        String normalized = normalizeCommand(command);
        for (String prefix : pythonPrefixes) {
            if (normalized.equals(prefix) || normalized.startsWith(prefix + " ")) {
                return true;
            }
        }
        return false;
    }

    static String firstNonFlagToken(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        for (String token : command.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!token.isEmpty() && !token.startsWith("-")) {
                return token;
            }
        }
        return "";
    }

    static String normalizeCommand(String command) {
        return command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
    }

    private String overrideKey(String command) {
        String normalized = normalizeCommand(command);
        BuiltinDefinition definition = catalog.findByCommand(normalized);
        if (definition == null) {
            return normalized;
        }
        String best = "";
        for (String prefix : definition.commands()) {
            String key = normalizeCommand(prefix);
            if ((normalized.equals(key) || normalized.startsWith(key + " "))
                && key.length() > best.length()) {
                best = key;
            }
        }
        return best.isEmpty() ? normalized : best;
    }

    private static boolean inScope(
            String cwd,
            String project,
            Path root,
            Set<String> scopedProjects) {
        if (cwdUnderRoot(cwd, root)) {
            return true;
        }
        return project != null && scopedProjects.contains(project);
    }

    static boolean cwdUnderRoot(String cwd, Path root) {
        if (cwd == null || cwd.isBlank() || root == null) {
            return false;
        }
        Path cwdPath = Path.of(cwd).toAbsolutePath().normalize();
        Path rootPath = root.toAbsolutePath().normalize();
        return cwdPath.startsWith(rootPath);
    }

    private static boolean commandMatchesScoped(
            String command,
            List<TrackingRepository.ProposeCommandRow> scoped) {
        String normalized = normalizeCommand(command);
        for (TrackingRepository.ProposeCommandRow row : scoped) {
            if (normalizeCommand(row.command()).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    static String proposalId(String kind, String command, String toml) {
        String material = (kind == null ? "" : kind)
            + '\0'
            + (command == null ? "" : command)
            + '\0'
            + (toml == null ? "" : toml);
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static Set<String> pythonPrefixes() {
        Set<String> prefixes = new LinkedHashSet<>();
        for (CommandFilter annotation : StrategyRegistry.prefixesOn(PythonFilter.class)) {
            prefixes.add(normalizeCommand(annotation.value()));
        }
        return Set.copyOf(prefixes);
    }

    private static Set<String> familyFirstTokens(
            BuiltinDefinitionCatalog catalog,
            Set<String> pythonPrefixes) {
        Set<String> tokens = new LinkedHashSet<>();
        for (BuiltinDefinition definition : catalog.all()) {
            for (String command : definition.commands()) {
                String token = firstNonFlagToken(command);
                if (!token.isEmpty()) {
                    tokens.add(token);
                }
            }
        }
        for (String prefix : pythonPrefixes) {
            String token = firstNonFlagToken(prefix);
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return Set.copyOf(tokens);
    }

    private static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private record Preview(List<String> before, List<String> after, int rawTokens, int outTokens) {}
}
