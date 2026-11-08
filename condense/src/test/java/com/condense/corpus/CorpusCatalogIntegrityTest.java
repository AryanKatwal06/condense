package com.condense.corpus;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Guards the baseline invariants that keep the corpus and CI trustworthy.
 *
 * <p>The raw-signal check is deliberately one-directional. It proves that every
 * declared {@code critical_signals} value is grounded in the raw command output,
 * except for the explicit allow-list below. It does not run any filter, so a
 * fidelity or golden test can never define success using the transformed output
 * it is supposed to be validating.
 */
class CorpusCatalogIntegrityTest {

    /**
     * Catalog entries whose {@code critical_signals} are intentionally rendered
     * summary strings rather than literal substrings of the raw fixture.
     *
     * <p>Every entry here must still contain at least one synthetic signal. That
     * makes each exemption self-expiring: once a stage starts preserving the raw
     * text, the exemption entry must be deleted or the test fails.
     *
     * <p>Note for Phase 4: {@code git-diff/typical} previously declared
     * {@code "+2 / -1"}, a lossy summary produced by {@code GitDiffSummaryStage}
     * instead of raw patch content. The catalog at this revision already declares
     * raw unified-diff lines, so the entry is intentionally not exempted below.
     * The rewritten stage and its golden output are still covered by
     * {@link FidelityCorpusTest} and {@link GoldenLockTest}.
     */
    private static final Set<String> KNOWN_SUMMARY_SIGNAL_ENTRIES = summarySignalEntries();

    private static final List<String> FABRICATED_ACTION_REFS = List.of(
        "1758c0c45731994e4604e3c9235d97e743acb5e8",
        "8df10395026330b699a77607b11ebf64432dce7c",
        "4cec3d8",
        "9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0",
        "actions/checkout@v7"
    );

    @Test
    void everyFixtureExistsIsNonEmptyAndEverySignalIsGroundedInRawInput() throws Exception {
        Path testResources = testResourcesRoot();
        CorpusCatalog.Catalog catalog = loadCatalog(testResources);
        List<String> violations = new ArrayList<>();

        for (CorpusCatalog.Entry entry : catalog.entries()) {
            if (isBlank(entry.id()) || isBlank(entry.command()) || isBlank(entry.fixture())) {
                violations.add(entry.id() + " has a blank id, command, or fixture");
                continue;
            }

            Path fixture = testResources.resolve(entry.fixture()).normalize();
            if (!Files.isRegularFile(fixture)) {
                violations.add(entry.id() + " fixture does not exist on disk: " + fixture);
                continue;
            }

            String raw = normalizeLineEndings(Files.readString(fixture, StandardCharsets.UTF_8));
            if (raw.isEmpty()) {
                violations.add(entry.id() + " fixture is empty: " + fixture);
                continue;
            }

            if (KNOWN_SUMMARY_SIGNAL_ENTRIES.contains(entry.id())) {
                continue;
            }

            for (String signal : entry.criticalSignals()) {
                if (!raw.contains(signal)) {
                    violations.add(entry.id() + " declares signal not present in raw fixture: '"
                        + signal + "'");
                }
            }
        }

        assertThat(violations)
            .as("corpus fixtures and critical signals must be grounded in raw command output")
            .isEmpty();
    }

    @Test
    void summarySignalExemptionsStillContainSyntheticOutput() throws Exception {
        Path testResources = testResourcesRoot();
        CorpusCatalog.Catalog catalog = loadCatalog(testResources);
        Set<String> seen = new LinkedHashSet<>();
        List<String> violations = new ArrayList<>();

        for (CorpusCatalog.Entry entry : catalog.entries()) {
            if (!KNOWN_SUMMARY_SIGNAL_ENTRIES.contains(entry.id())) {
                continue;
            }
            seen.add(entry.id());

            Path fixture = testResources.resolve(entry.fixture()).normalize();
            if (!Files.isRegularFile(fixture)) {
                violations.add(entry.id() + " is exempt but its fixture is missing: " + fixture);
                continue;
            }
            String raw = normalizeLineEndings(Files.readString(fixture, StandardCharsets.UTF_8));
            boolean hasSyntheticSignal = entry.criticalSignals().stream()
                .anyMatch(signal -> !raw.contains(signal));
            if (!hasSyntheticSignal) {
                violations.add(entry.id()
                    + " is exempt but every declared signal now appears in the raw fixture;"
                    + " remove it from KNOWN_SUMMARY_SIGNAL_ENTRIES");
            }
        }

        for (String exempted : KNOWN_SUMMARY_SIGNAL_ENTRIES) {
            if (!seen.contains(exempted)) {
                violations.add(exempted
                    + " is exempt but no longer exists in catalog.json; remove the stale exemption");
            }
        }

        assertThat(violations)
            .as("summary-signal exemptions must stay necessary and self-expire")
            .isEmpty();
    }

    @Test
    void gitDiffEntriesDeclareTheirRawCommandAndSignals() throws Exception {
        Path testResources = testResourcesRoot();
        CorpusCatalog.Catalog catalog = loadCatalog(testResources);

        CorpusCatalog.Entry stat = entryById(catalog, "git-diff/stat");
        assertThat(stat.command())
            .as("git-diff/stat must exercise the stat command, not plain git diff")
            .isEqualTo("git diff --stat");

        CorpusCatalog.Entry typical = entryById(catalog, "git-diff/typical");
        String raw = normalizeLineEndings(
            Files.readString(testResources.resolve(typical.fixture()), StandardCharsets.UTF_8));
        assertThat(typical.criticalSignals())
            .as("git-diff/typical signals must come from the raw patch fixture")
            .allMatch(raw::contains);
    }

    @Test
    void workflowActionReferencesContainNoFabricatedPins() throws Exception {
        Path workflows = repositoryRoot().resolve(".github").resolve("workflows");
        assertThat(workflows)
            .as("workflow directory must be resolvable from the Maven module or repository root")
            .isDirectory();

        List<Path> files;
        try (Stream<Path> listing = Files.list(workflows)) {
            files = listing
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String name = path.getFileName().toString();
                    return name.endsWith(".yml") || name.endsWith(".yaml");
                })
                .sorted()
                .toList();
        }

        assertThat(files)
            .as("at least one GitHub Actions workflow must exist")
            .isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Path workflow : files) {
            String text = normalizeLineEndings(Files.readString(workflow, StandardCharsets.UTF_8));
            if (text.isBlank()) {
                violations.add(workflow.getFileName() + " is empty");
                continue;
            }
            for (String fabricated : FABRICATED_ACTION_REFS) {
                if (text.contains(fabricated)) {
                    violations.add(workflow.getFileName() + " contains fabricated action ref '"
                        + fabricated + "'");
                }
            }
        }

        assertThat(violations)
            .as("workflows must not reference fabricated GitHub Action commits or versions")
            .isEmpty();
    }

    private static CorpusCatalog.Catalog loadCatalog(Path testResources) throws Exception {
        Path catalog = testResources.resolve("corpus").resolve("catalog.json");
        if (!Files.isRegularFile(catalog)) {
            fail("catalog.json not found at " + catalog);
        }
        return CorpusCatalog.parse(Files.readString(catalog, StandardCharsets.UTF_8));
    }

    private static CorpusCatalog.Entry entryById(CorpusCatalog.Catalog catalog, String id) {
        return catalog.entries().stream()
            .filter(entry -> id.equals(entry.id()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing catalog entry " + id));
    }

    private static Set<String> summarySignalEntries() {
        Set<String> entries = new LinkedHashSet<>();
        entries.add("jest/typical");
        entries.add("eslint/typical");
        entries.add("eslint/passing");
        entries.add("eslint-json/typical");
        entries.add("tsc/typical");
        entries.add("ruff-check/typical");
        entries.add("ruff-check/passing");
        entries.add("git-status/clean");
        entries.add("git-status/modified");
        entries.add("git-status/staged");
        entries.add("git-status/untracked");
        entries.add("git-status/mixed");
        entries.add("git-status/detached-head");
        entries.add("git-status/porcelain-mixed");
        entries.add("git-add/typical");
        entries.add("cat/large");
        entries.add("kubectl/pods-unhealthy");
        entries.add("go-test/json-passing");
        return Set.copyOf(entries);
    }

    private static Path repositoryRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = cwd; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve(".github").resolve("workflows"))
                && Files.isRegularFile(candidate.resolve("condense").resolve("pom.xml"))) {
                return candidate;
            }
        }
        throw new AssertionError("Cannot locate repository root from working directory " + cwd);
    }

    private static Path testResourcesRoot() {
        Path repositoryRoot = repositoryRoot();
        Path nested = repositoryRoot.resolve("condense").resolve("src")
            .resolve("test").resolve("resources");
        if (Files.isDirectory(nested)) {
            return nested;
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path local = cwd.resolve("src").resolve("test").resolve("resources");
        if (Files.isDirectory(local)) {
            return local;
        }
        throw new AssertionError("Cannot locate test resources from " + repositoryRoot);
    }

    private static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
