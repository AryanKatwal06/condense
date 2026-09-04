package com.condense.config;

import com.condense.core.PlatformDirs;
import com.condense.core.SafePathValidator;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.trust.Capability;
import com.condense.trust.FilterRisk;
import com.condense.trust.TrustGate;
import com.condense.trust.TrustRecord;
import com.condense.trust.TrustStore;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code condense config trust} — review a project filter override once, then
 * persist or revoke trust. Proxied commands never prompt.
 */
@Command(
    name = "trust",
    description = "Review and trust a project filter override (.condense/filters.toml).",
    mixinStandardHelpOptions = true
)
@Dependent
public class ConfigTrustCommand implements Callable<Integer> {

    @Option(
        names = "--accept",
        description = "Trust the bytes printed in this invocation (default grant: reduce)."
    )
    boolean accept;

    @Option(
        names = "--grant",
        description = "Comma-separated capabilities to grant (reduce, reshape, rewrite). Default: reduce.",
        paramLabel = "CAPS"
    )
    String grant;

    @Option(
        names = "--revoke",
        description = "Remove trust for the project override file."
    )
    boolean revoke;

    @Option(
        names = "--status",
        description = "List trusted project override files."
    )
    boolean status;

    PlatformDirs platformDirs;
    TrustGate trustGate;
    FilterOverrideLoader overrideLoader;
    Path workingDirectory;

    public ConfigTrustCommand() {
        this(new PlatformDirs());
    }

    public ConfigTrustCommand(PlatformDirs platformDirs) {
        this.platformDirs = platformDirs != null ? platformDirs : new PlatformDirs();
        this.trustGate = new TrustGate(this.platformDirs);
        this.overrideLoader = new FilterOverrideLoader(this.platformDirs, this.trustGate);
    }

    @Inject
    public ConfigTrustCommand(PlatformDirs platformDirs, TrustGate trustGate, FilterOverrideLoader overrideLoader) {
        this.platformDirs = platformDirs != null ? platformDirs : new PlatformDirs();
        this.trustGate = trustGate != null ? trustGate : new TrustGate(this.platformDirs);
        this.overrideLoader = overrideLoader != null
            ? overrideLoader
            : new FilterOverrideLoader(this.platformDirs, this.trustGate);
    }

    @Override
    public Integer call() {
        if (status) {
            return printStatus();
        }

        Path projectDir = resolveProjectDir();
        Path file = projectDir.resolve(FilterOverrideLoader.PROJECT_OVERRIDE_REL_PATH);

        if (revoke) {
            Path canonical = TrustStore.canonicalize(file);
            trustGate.revoke(canonical);
            overrideLoader.invalidateCache();
            System.out.println("Revoked trust for " + canonical);
            return 0;
        }

        if (!Files.exists(file)) {
            System.err.println("condense: no project filter override at " + FilterOverrideLoader.PROJECT_OVERRIDE_REL_PATH);
            return 1;
        }

        SafePathValidator.ContainmentResult containment = SafePathValidator.contain(file, projectDir);
        if (!containment.contained()) {
            System.err.println("condense: " + containment.reason());
            return 1;
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (Exception e) {
            System.err.println("condense: cannot read " + file + ": " + e.getMessage());
            return 1;
        }

        FilterOverrideLoader.ParsedFileResult parsed = overrideLoader.parseAndValidateBytes(file, bytes);
        if (!parsed.validationResult().isValid()) {
            System.err.println("condense: " + FilterOverrideLoader.PROJECT_OVERRIDE_REL_PATH + " is not valid:");
            for (String error : parsed.validationResult().errors()) {
                System.err.println("  " + error);
            }
            return 1;
        }

        FilterRisk.Report risk = FilterRisk.classify(parsed.fileConfig());
        System.out.print(risk.format());
        System.out.println("---");
        System.out.print(new String(bytes, StandardCharsets.UTF_8));
        if (bytes.length > 0 && bytes[bytes.length - 1] != '\n') {
            System.out.println();
        }
        System.out.println("---");

        if (!accept) {
            if (!isInteractive()) {
                System.err.println("condense: non-interactive review requires --accept");
                return 1;
            }
            if (!confirmTrust()) {
                System.out.println("Trust not recorded.");
                return 1;
            }
        }

        Set<Capability> grants = parseGrants();
        Path canonical = TrustStore.canonicalize(file);
        trustGate.accept(canonical, bytes, grants);
        overrideLoader.invalidateCache();
        System.out.println("Trusted " + canonical + " with " + formatGrants(grants));
        return 0;
    }

    private int printStatus() {
        var records = trustGate.status();
        if (records.isEmpty()) {
            System.out.println("No trusted project filter overrides.");
            return 0;
        }
        for (TrustRecord record : records) {
            System.out.println(record.path()
                + "  sha256=" + record.sha256()
                + "  capabilities=" + String.join(",", record.capabilities()));
        }
        return 0;
    }

    private Set<Capability> parseGrants() {
        if (grant == null || grant.isBlank()) {
            return EnumSet.of(Capability.REDUCE);
        }
        Set<Capability> parsed = Capability.parseCsv(grant);
        return parsed.isEmpty() ? EnumSet.of(Capability.REDUCE) : parsed;
    }

    private static String formatGrants(Set<Capability> grants) {
        return grants.stream().map(Capability::token).reduce((a, b) -> a + "," + b).orElse("reduce");
    }

    private Path resolveProjectDir() {
        if (workingDirectory != null) {
            return workingDirectory.toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    private static boolean isInteractive() {
        return System.console() != null || System.getProperty("condense.test.interactive") != null;
    }

    private static boolean confirmTrust() {
        System.out.print("Trust this file? [y/N]: ");
        System.out.flush();
        String line = null;
        if (System.console() != null) {
            line = System.console().readLine();
        } else {
            Scanner scanner = new Scanner(System.in);
            if (scanner.hasNextLine()) {
                line = scanner.nextLine();
            }
        }
        return line != null && line.trim().equalsIgnoreCase("y");
    }
}
