package com.condense.filter.stage;

import com.condense.annotation.DeclarativeStage;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.HeadTailStage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Semantic condensation for GitLab CLI ({@code glab}) commands.
 * <p>
 * Handles {@code glab mr view}, {@code glab issue view}, {@code glab ci view},
 * and falls back to balanced head-tail truncation for tabular listings like
 * {@code glab mr list} or generic text.
 * Maintains strict fail-open safety on non-zero exit codes or unparseable input.
 */
@DeclarativeStage(aliases = {"glab_summary"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class GlabSummaryStage implements FilterStage {

    public static final GlabSummaryStage INSTANCE = new GlabSummaryStage();
    private static final HeadTailStage DEFAULT_HEAD_TAIL = new HeadTailStage(6, 6);

    private static final Pattern WRAPPER_TAG =
        Pattern.compile("^\\s*</?(?:details|summary|blockquote|ul|ol|table|tbody|thead|tr)[^>]*>(?:.*?</(?:summary|details)>)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PURE_TAG =
        Pattern.compile("^\\s*</?[a-zA-Z0-9_-]+[^>]*>\\s*$");
    private static final Pattern BADGE_OR_COMMENT =
        Pattern.compile("^\\s*(?:\\[!\\[.*?\\]\\(.*?\\)\\]\\(.*?\\)|<!--.*?-->|\\[//\\]:.*)\\s*$");

    private GlabSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        if (raw == null || raw.isBlank()) {
            return StageResult.continueWith(raw != null ? raw : "");
        }

        ExecutionResult result = context != null ? context.result() : null;
        if (result != null && !result.succeeded()) {
            // Strict fail-open: never filter error messages or failure exit codes
            return StageResult.continueWith(raw);
        }

        String cmd = context != null && context.command() != null
            ? context.command().toLowerCase(Locale.ROOT)
            : "";

        try {
            if (isMrOrIssueView(cmd, raw)) {
                return StageResult.continueWith(compactView(raw));
            }
            if (isCiView(cmd, raw)) {
                return StageResult.continueWith(compactCiView(raw));
            }
            return DEFAULT_HEAD_TAIL.process(raw, context);
        } catch (Exception ignored) {
            // Defensive fail-open: any parsing irregularity falls open to raw output
            return StageResult.continueWith(raw);
        }
    }

    private static boolean isMrOrIssueView(String cmd, String raw) {
        if (cmd.contains("view") && (cmd.contains("mr") || cmd.contains("issue"))) {
            return true;
        }
        if (cmd.isEmpty() || !cmd.contains("list")) {
            return (raw.startsWith("title:\t") || raw.startsWith("title: "))
                && raw.contains("\n--");
        }
        return false;
    }

    private static boolean isCiView(String cmd, String raw) {
        if (cmd.contains("ci") && cmd.contains("view")) {
            return true;
        }
        if (cmd.isEmpty()) {
            String lower = raw.toLowerCase(Locale.ROOT);
            return lower.contains("pipeline #") || (lower.contains("pipeline") && lower.contains("jobs:"));
        }
        return false;
    }

    private static String compactView(String raw) {
        List<String> lines = raw.lines().toList();
        int dividerIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.equals("--")) {
                dividerIndex = i;
                break;
            }
        }

        if (dividerIndex == -1) {
            return raw;
        }

        List<String> metadataLines = new ArrayList<>();
        for (int i = 0; i < dividerIndex; i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            int tabIndex = line.indexOf('\t');
            int colonIndex = line.indexOf(':');
            String value = "";
            if (tabIndex > 0) {
                value = line.substring(tabIndex + 1).trim();
            } else if (colonIndex > 0) {
                value = line.substring(colonIndex + 1).trim();
            }
            // Retain key if value is non-empty
            if (!value.isEmpty()) {
                metadataLines.add(line);
            }
        }

        // Substantive body extraction
        List<String> substantive = new ArrayList<>();
        boolean lastWasBlank = false;
        for (int i = dividerIndex + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (!lastWasBlank && !substantive.isEmpty()) {
                    substantive.add("");
                    lastWasBlank = true;
                }
                continue;
            }
            if (WRAPPER_TAG.matcher(trimmed).matches()
                || PURE_TAG.matcher(trimmed).matches()
                || BADGE_OR_COMMENT.matcher(trimmed).matches()) {
                continue;
            }

            // Clean inline tags like <li>, <code>, <em>, <strong>
            String cleaned = trimmed
                .replaceAll("^\\s*<li>", "- ")
                .replaceAll("</?li>", "")
                .replaceAll("</?(?:p|em|strong|b|i|code|a)[^>]*>", "")
                .trim();

            if (cleaned.isEmpty()) {
                continue;
            }

            substantive.add(cleaned);
            lastWasBlank = false;
        }

        // Remove trailing empty line if any
        while (!substantive.isEmpty() && substantive.getLast().isBlank()) {
            substantive.removeLast();
        }

        StringBuilder sb = new StringBuilder();
        for (String meta : metadataLines) {
            sb.append(meta).append('\n');
        }
        sb.append("--\n");

        int maxBodyLines = 12;
        if (substantive.size() <= maxBodyLines) {
            for (String bodyLine : substantive) {
                sb.append(bodyLine).append('\n');
            }
        } else {
            for (int i = 0; i < maxBodyLines; i++) {
                sb.append(substantive.get(i)).append('\n');
            }
            int omitted = substantive.size() - maxBodyLines;
            sb.append("... (").append(omitted).append(" lines omitted) ...\n");
        }

        return sb.toString().stripTrailing();
    }

    private static String compactCiView(String raw) {
        List<String> lines = raw.lines().toList();
        List<String> header = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int passCount = 0;
        int total = 0;

        boolean inJobsSection = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.equalsIgnoreCase("Jobs:") || trimmed.equalsIgnoreCase("Stages:")) {
                inJobsSection = true;
                continue;
            }

            if (!inJobsSection) {
                if (!trimmed.isEmpty()) {
                    header.add(line);
                }
                continue;
            }

            if (trimmed.isEmpty()) {
                continue;
            }

            total++;
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.contains("fail") || lower.contains("error") || trimmed.startsWith("✗") || trimmed.startsWith("X ")) {
                failures.add(trimmed);
            } else if (lower.contains("pass") || lower.contains("success") || trimmed.startsWith("✓")) {
                passCount++;
            } else {
                // Pending, running, or unknown
                failures.add(trimmed);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String h : header) {
            sb.append(h).append('\n');
        }

        if (total == 0) {
            return sb.toString().stripTrailing();
        }

        sb.append('\n');
        if (failures.isEmpty()) {
            sb.append("✓ all jobs passed (").append(passCount).append(" jobs)");
        } else {
            sb.append("✗ ").append(failures.size()).append(" of ").append(total).append(" jobs failed:\n");
            for (String fail : failures) {
                sb.append("  ").append(fail).append('\n');
            }
            if (passCount > 0) {
                sb.append("✓ ").append(passCount).append(" jobs passed\n");
            }
        }

        return sb.toString().stripTrailing();
    }
}
