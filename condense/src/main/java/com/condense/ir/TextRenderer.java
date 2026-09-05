package com.condense.ir;

import com.condense.filter.strategy.GroupingStrategy;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact-text renderer. Default CLI output for migrated families is this
 * string, then {@code FilterResult.of} stamps it. Ultra-compact is a text-only
 * concern; JSON is unaffected.
 */
@RegisterForReflection
public final class TextRenderer {

    private TextRenderer() {}

    public static String render(Document document) {
        if (document == null || document.document() == null) {
            return "";
        }
        return switch (document.kind()) {
            case TEST -> renderTest(cast(document.document(), Document.TestDocument.class));
            case DIAGNOSTIC -> renderDiagnostic(cast(document.document(), Document.DiagnosticDocument.class));
            case DEPENDENCY -> renderDependency(cast(document.document(), Document.DependencyDocument.class));
            case RESOURCE -> renderResource(cast(document.document(), Document.ResourceDocument.class));
            case OPAQUE -> renderOpaque(cast(document.document(), Document.OpaqueDocument.class));
        };
    }

    public static String renderTest(Document.TestDocument payload) {
        if (payload == null) {
            return "✓ all tests passed";
        }
        List<String> lines = payload.lines();
        if (lines == null || lines.isEmpty()) {
            String fallback = payload.emptyFallback();
            return fallback == null || fallback.isBlank() ? "✓ all tests passed" : fallback;
        }
        return String.join("\n", lines);
    }

    public static String renderDiagnostic(Document.DiagnosticDocument payload) {
        if (payload == null) {
            return "";
        }
        if (payload.clean()) {
            return "✓ no lint issues";
        }
        StringBuilder sb = new StringBuilder("eslint: ")
            .append(payload.errors())
            .append(" error(s), ")
            .append(payload.warnings())
            .append(" warning(s)");
        String groups = formatGroups(payload);
        if (!groups.isBlank()) {
            sb.append('\n').append(groups);
        }
        return sb.toString().stripTrailing();
    }

    public static String renderDependency(Document.DependencyDocument payload) {
        if (payload == null) {
            return "✓ npm install";
        }
        StringBuilder sb = new StringBuilder();
        if (payload.irrevocable() != null) {
            for (String line : payload.irrevocable()) {
                if (line == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        if (payload.failed()) {
            if (!payload.irrevocable().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("npm install failed");
            }
            return sb.toString();
        }
        StringBuilder summary = new StringBuilder("✓ npm install");
        if (payload.addedPackages() != null) {
            summary.append(": ").append(payload.addedPackages()).append(" packages");
        }
        if (payload.vulnerabilityText() != null && !payload.vulnerabilityText().isBlank()) {
            summary.append(" | ").append(payload.vulnerabilityText());
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(summary);
        return sb.toString();
    }

    public static String renderResource(Document.ResourceDocument payload) {
        if (payload == null || payload.empty()) {
            return "(no containers running)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("ID       IMAGE                STATUS    NAME\n");
        sb.append("─".repeat(55));
        for (Document.ResourceRow row : payload.rows()) {
            sb.append('\n');
            if (row.raw() != null && !row.raw().isBlank()
                && (row.id() == null || row.id().isBlank())) {
                sb.append(row.raw());
            } else {
                sb.append(String.format("%-8s %-20s %-10s %s",
                    row.id(), row.image(), row.status(), row.name()));
            }
        }
        return sb.toString();
    }

    public static String renderOpaque(Document.OpaqueDocument payload) {
        return payload == null || payload.body() == null ? "" : payload.body();
    }

    private static String formatGroups(Document.DiagnosticDocument payload) {
        if (payload.groups() == null || payload.groups().isEmpty()) {
            return "";
        }
        if (Document.DiagnosticDocument.GROUP_COLON.equals(payload.groupStyle())) {
            StringBuilder sb = new StringBuilder();
            for (Document.GroupCount group : payload.groups()) {
                sb.append("  ").append(group.key())
                    .append(": ").append(group.count()).append('\n');
            }
            return sb.toString().stripTrailing();
        }
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Document.GroupCount group : payload.groups()) {
            map.put(group.key(), group.count());
        }
        return GroupingStrategy.format(map);
    }

    private static <T> T cast(Object value, Class<T> type) {
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new IllegalStateException(
                "document payload is " + value.getClass().getName() + ", expected " + type.getName());
        }
        return type.cast(value);
    }
}
