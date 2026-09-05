package com.condense.discover;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoverServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void pnpmLockBeatsPackageLock() throws Exception {
        Path root = project();
        Files.writeString(root.resolve("pnpm-lock.yaml"), "lockfileVersion: 9\n");
        Files.writeString(root.resolve("package-lock.json"), "{}\n");
        Files.writeString(root.resolve("package.json"), "{}\n");

        DiscoverReport report = new DiscoverService().discover(root, null);
        assertThat(report.failed()).isFalse();
        assertThat(report.recommend()).contains("pnpm-install").doesNotContain("npm-install");
        assertThat(family(report, "js-install").rule()).isEqualTo("js-pnpm");
    }

    @Test
    void extrasFireWithoutFlippingInstallFamily() throws Exception {
        Path root = project();
        Files.writeString(root.resolve("pnpm-lock.yaml"), "lockfileVersion: 9\n");
        Files.createDirectories(root.resolve("prisma"));
        Files.writeString(root.resolve("prisma").resolve("schema.prisma"), "datasource db {}\n");

        DiscoverReport report = new DiscoverService().discover(root, null);
        assertThat(report.recommend()).contains("pnpm-install", "prisma", "git-status");
        assertThat(family(report, "js-install").rule()).isEqualTo("js-pnpm");
        assertThat(family(report, "prisma").rule()).isEqualTo("extra-prisma");
        assertThat(report.truncated()).isFalse();
        assertThat(report.filesProbed()).isLessThanOrEqualTo(DiscoverLimits.DEFAULT.maxProbes());
    }

    @Test
    void missingSignalsAreQuiet() throws Exception {
        Path root = project();
        DiscoverReport report = new DiscoverService().discover(root, null);
        assertThat(report.failed()).isFalse();
        assertThat(report.recommend()).contains("git-status").doesNotContain("pnpm-install", "npm-install");
        assertThat(report.error()).isNull();
    }

    @Test
    void rootOverrideCannotWiden() throws Exception {
        Path root = project();
        DiscoverReport report = new DiscoverService().discover(root, root.getParent());
        assertThat(report.failed()).isTrue();
        assertThat(report.error()).contains("narrow");
    }

    @Test
    void rootOverrideCanNarrow() throws Exception {
        Path root = project();
        Path nested = Files.createDirectories(root.resolve("apps").resolve("web"));
        Files.writeString(nested.resolve("package.json"), "{}\n");

        DiscoverReport wide = new DiscoverService().discover(root, null);
        assertThat(wide.recommend()).doesNotContain("npm-install");

        DiscoverReport narrow = new DiscoverService().discover(root, nested);
        assertThat(narrow.failed()).isFalse();
        assertThat(narrow.recommend()).contains("npm-install");
    }

    @Test
    void capsSetTruncatedAndStopProbes() throws Exception {
        Path root = project();
        Files.writeString(root.resolve("a.txt"), "a\n");
        Files.writeString(root.resolve("b.txt"), "b\n");
        Map<String, DiscoverDefinition> rules = new LinkedHashMap<>();
        rules.put("first", definition("first", "one", 10, "a.txt", "pytest"));
        rules.put("second", definition("second", "two", 10, "b.txt", "ruff"));
        DiscoverService service = new DiscoverService(
            new DiscoverRuleCatalog(rules), new DiscoverLimits(1, 8, 1024, 4096));

        DiscoverReport report = service.discover(root, null);
        assertThat(report.truncated()).isTrue();
        assertThat(report.filesProbed()).isEqualTo(1);
        assertThat(report.recommend()).contains("pytest").doesNotContain("ruff");
    }

    @Test
    void extraContainsNeedleMustBePresent() throws Exception {
        Path root = project();
        Files.writeString(root.resolve("package.json"), "{\"name\":\"app\"}\n");
        Map<String, DiscoverDefinition> rules = new LinkedHashMap<>();
        rules.put("nextish", new DiscoverDefinition(
            1, "nextish", "next", 10, List.of(),
            List.of(new DiscoverDefinition.Extra("package.json", List.of("\"next\""))),
            List.of("next-build"), false));
        DiscoverService service = new DiscoverService(
            new DiscoverRuleCatalog(rules), DiscoverLimits.DEFAULT);

        assertThat(service.discover(root, null).recommend()).doesNotContain("next-build");
        Files.writeString(root.resolve("package.json"), "{\"dependencies\":{\"next\":\"14.0.0\"}}\n");
        assertThat(service.discover(root, null).recommend()).contains("next-build");
        assertThat(service.discover(root, null).filesRead()).isEqualTo(1);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void symlinkEscapeIsSkipped() throws Exception {
        Path root = project();
        Path outside = tempDir.resolve("secret.txt");
        Files.writeString(outside, "secret\n");
        Files.createSymbolicLink(root.resolve("link.txt"), outside);
        Map<String, DiscoverDefinition> rules = new LinkedHashMap<>();
        rules.put("escape", definition("escape", "escape", 10, "link.txt", "pytest"));
        DiscoverReport report = new DiscoverService(
            new DiscoverRuleCatalog(rules), DiscoverLimits.DEFAULT).discover(root, null);
        assertThat(report.recommend()).doesNotContain("pytest");
    }

    private Path project() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("repo"));
        Files.createDirectories(root.resolve(".git"));
        return root;
    }

    private static DiscoverDefinition definition(
            String name, String family, int priority, String signal, String recommend
    ) {
        return new DiscoverDefinition(
            1, name, family, priority, List.of(signal), List.of(), List.of(recommend), false);
    }

    private static DiscoverReport.FamilyHit family(DiscoverReport report, String family) {
        return report.families().stream()
            .filter(hit -> family.equals(hit.family()))
            .findFirst()
            .orElseThrow();
    }
}
