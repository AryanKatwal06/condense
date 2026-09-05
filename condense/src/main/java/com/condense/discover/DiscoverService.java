package com.condense.discover;

import com.condense.core.SafePathValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded, path-contained discovery. Recommends filter definition names; never dispatches.
 */
public final class DiscoverService {

    private final DiscoverRuleCatalog catalog;
    private final DiscoverLimits limits;

    public DiscoverService() {
        this(DiscoverRuleCatalog.standalone(), DiscoverLimits.DEFAULT);
    }

    public DiscoverService(DiscoverRuleCatalog catalog, DiscoverLimits limits) {
        this.catalog = catalog;
        this.limits = limits == null ? DiscoverLimits.DEFAULT : limits;
    }

    public DiscoverReport discover(Path cwd, Path rootOverride) {
        Path work = cwd == null ? Path.of(System.getProperty("user.dir", ".")) : cwd;
        Path defaultRoot = SafePathValidator.resolveWorkspaceRoot(work);
        Path root = defaultRoot;
        if (rootOverride != null) {
            Path requested = rootOverride.toAbsolutePath().normalize();
            if (!Files.isDirectory(requested)) {
                return DiscoverReport.failure(
                    requested.toString(), "root is not a directory");
            }
            if (!SafePathValidator.isAtOrUnder(requested, defaultRoot)) {
                return DiscoverReport.failure(
                    requested.toString(), "root may only narrow the workspace, not widen it");
            }
            root = requested;
        }
        Path canonicalRoot;
        try {
            canonicalRoot = Files.exists(root) ? root.toRealPath() : root.toAbsolutePath().normalize();
        } catch (IOException e) {
            return DiscoverReport.failure(root.toString(), "cannot resolve root: " + e.getMessage());
        }

        Budget budget = new Budget();
        Map<String, Boolean> existence = new LinkedHashMap<>();
        List<DiscoverReport.FamilyHit> hits = new ArrayList<>();
        Set<String> claimedFamilies = new LinkedHashSet<>();
        Set<String> recommend = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();

        for (DiscoverDefinition rule : catalog.rules()) {
            if (budget.truncated) {
                break;
            }
            if (claimedFamilies.contains(rule.family())) {
                continue;
            }
            List<String> fired = match(rule, canonicalRoot, existence, budget, warnings);
            if (fired == null) {
                continue;
            }
            claimedFamilies.add(rule.family());
            hits.add(new DiscoverReport.FamilyHit(rule.family(), rule.name(), fired, rule.recommend()));
            recommend.addAll(rule.recommend());
        }

        return new DiscoverReport(
            DiscoverReport.SCHEMA_VERSION,
            canonicalRoot.toString(),
            hits,
            List.copyOf(recommend),
            budget.probes,
            budget.reads,
            budget.bytes,
            budget.truncated,
            warnings,
            null
        );
    }

    private List<String> match(
            DiscoverDefinition rule,
            Path root,
            Map<String, Boolean> existence,
            Budget budget,
            List<String> warnings
    ) {
        List<String> fired = new ArrayList<>();
        if (rule.workspaceGitMarker()) {
            if (budget.probes >= limits.maxProbes()) {
                budget.truncated = true;
                return null;
            }
            budget.probes++;
            Path git = root.resolve(".git");
            if (Files.exists(git, LinkOption.NOFOLLOW_LINKS)) {
                fired.add(".git");
            }
        }
        for (String signal : rule.signals()) {
            if (budget.truncated) {
                break;
            }
            if (probeExists(signal, root, existence, budget, warnings)) {
                fired.add(signal);
            }
        }
        for (DiscoverDefinition.Extra extra : rule.extras()) {
            if (budget.truncated) {
                break;
            }
            if (probeExists(extra.path(), root, existence, budget, warnings)
                && containsAll(extra, root, budget, warnings)) {
                fired.add(extra.path());
            }
        }
        return fired.isEmpty() ? null : fired;
    }

    private boolean probeExists(
            String relative,
            Path root,
            Map<String, Boolean> existence,
            Budget budget,
            List<String> warnings
    ) {
        Boolean cached = existence.get(relative);
        if (cached != null) {
            return cached;
        }
        if (budget.probes >= limits.maxProbes()) {
            budget.truncated = true;
            return false;
        }
        budget.probes++;
        Path candidate = root.resolve(relative).normalize();
        SafePathValidator.ContainmentResult contained = SafePathValidator.contain(candidate, root);
        if (!contained.contained()) {
            warnings.add("skipped " + relative + ": " + contained.reason());
            existence.put(relative, false);
            return false;
        }
        Path resolved = contained.realFile();
        boolean present = resolved != null
            && Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS);
        if (present) {
            try {
                Path real = resolved.toRealPath();
                if (!real.startsWith(root.toRealPath()) && !SafePathValidator.isAtOrUnder(real, root)) {
                    warnings.add("skipped " + relative + ": symlink escape");
                    existence.put(relative, false);
                    return false;
                }
            } catch (IOException e) {
                warnings.add("skipped " + relative + ": " + e.getMessage());
                existence.put(relative, false);
                return false;
            }
        }
        existence.put(relative, present);
        return present;
    }

    private boolean containsAll(
            DiscoverDefinition.Extra extra,
            Path root,
            Budget budget,
            List<String> warnings
    ) {
        if (extra.contains() == null || extra.contains().isEmpty()) {
            return true;
        }
        if (budget.reads >= limits.maxReads() || budget.bytes >= limits.maxTotalBytes()) {
            budget.truncated = true;
            return false;
        }
        Path candidate = root.resolve(extra.path()).normalize();
        SafePathValidator.ContainmentResult contained = SafePathValidator.containReadable(candidate, root);
        if (!contained.contained()) {
            warnings.add("skipped read " + extra.path() + ": " + contained.reason());
            return false;
        }
        try {
            long size = Files.size(contained.realFile());
            int cap = (int) Math.min(limits.maxBytesPerFile(), limits.maxTotalBytes() - budget.bytes);
            if (cap <= 0) {
                budget.truncated = true;
                return false;
            }
            byte[] body = Files.readAllBytes(contained.realFile());
            if (body.length > limits.maxBytesPerFile()) {
                body = java.util.Arrays.copyOf(body, limits.maxBytesPerFile());
            }
            if (size > limits.maxBytesPerFile()) {
                warnings.add("truncated read of " + extra.path());
            }
            budget.reads++;
            budget.bytes += body.length;
            if (body.length > cap) {
                budget.truncated = true;
            }
            String prefix = new String(body, StandardCharsets.UTF_8);
            for (String needle : extra.contains()) {
                if (needle != null && !needle.isEmpty() && !prefix.contains(needle)) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            warnings.add("skipped read " + extra.path() + ": " + e.getMessage());
            return false;
        }
    }

    private static final class Budget {
        int probes;
        int reads;
        long bytes;
        boolean truncated;
    }
}
