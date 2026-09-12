package com.condense.filter.stage;

import com.condense.annotation.DeclarativeStage;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;
import com.condense.filter.strategy.HeadTailStage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Semantic condensation for GitHub CLI ({@code gh}) commands.
 * <p>
 * Handles {@code gh pr view}, {@code gh issue view}, {@code gh pr checks},
 * {@code gh run view}, and falls back to balanced head-tail truncation for
 * tabular listings like {@code gh pr list} or generic text.
 * Maintains strict fail-open safety on non-zero exit codes or unparseable input.
 */
@DeclarativeStage(aliases = {"gh_summary"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class GhSummaryStage implements FilterStage {

    public static final GhSummaryStage INSTANCE = new GhSummaryStage();
    private static final HeadTailStage DEFAULT_HEAD_TAIL = new HeadTailStage(6, 6);

    private static final Pattern WRAPPER_TAG =
        Pattern.compile("^\\s*</?(?:details|summary|blockquote|ul|ol|table|tbody|thead|tr)[^>]*>(?:.*?</(?:summary|details)>)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PURE_TAG =
        Pattern.compile("^\\s*</?[a-zA-Z0-9_-]+[^>]*>\\s*$");
    private static final Pattern BADGE_OR_COMMENT =
        Pattern.compile("^\\s*(?:\\[!\\[.*?\\]\\(.*?\\)\\]\\(.*?\\)|<!--.*?-->|\\[//\\]:.*)\\s*$");

    private GhSummaryStage() {}

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
            if (isPrOrIssueView(cmd, raw)) {
                return StageResult.continueWith(compactView(raw));
            }
            if (isPrChecks(cmd, raw)) {
                return StageResult.continueWith(compactChecks(raw));
            }
            if (isRunView(cmd, raw)) {
                return StageResult.continueWith(compactRunView(raw));
            }
            return DEFAULT_HEAD_TAIL.process(raw, context);
        } catch (Exception ignored) {
            // Defensive fail-open: any parsing irregularity falls open to raw output
            return StageResult.continueWith(raw);
        }
    }

    private static boolean isPrOrIssueView(String cmd, String raw) {
        if (cmd.contains("view") && (cmd.contains("pr") || cmd.contains("issue"))) {
            return true;
        }
        if (cmd.isEmpty() || !cmd.contains("list")) {
            return (raw.startsWith("title:\t") || raw.startsWith("title: "))
                && raw.contains("\n--");
        }
        return false;
    }

    private static boolean isPrChecks(String cmd, String raw) {
        if (cmd.contains("checks")) {
            return true;
        }
        if (cmd.isEmpty()) {
            return raw.lines().limit(5).anyMatch(l -> {
                String[] parts = l.split("\t");
                if (parts.length >= 2) {
                    String status = parts[1].trim().toLowerCase(Locale.ROOT);
                    return status.equals("pass") || status.equals("fail") || status.equals("failure")
                        || status.equals("pending") || status.equals("skipping");
                }
                return false;
            });
        }
        return false;
    }

    private static boolean isRunView(String cmd, String raw) {
        if (cmd.contains("run") && cmd.contains("view")) {
            return true;
        }
        if (cmd.isEmpty()) {
            return (raw.startsWith("✓ ") || raw.startsWith("X ")) && raw.contains("JOBS");
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
            if (BoundedRegex.matcher(WRAPPER_TAG, trimmed).matches()
                || BoundedRegex.matcher(PURE_TAG, trimmed).matches()
                || BoundedRegex.matcher(BADGE_OR_COMMENT, trimmed).matches()) {
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

    private static String compactChecks(String raw) {
        List<String> lines = raw.lines().toList();
        List<String> failures = new ArrayList<>();
        int passCount = 0;
        int total = 0;

        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            total++;
            String[] parts = line.split("\t");
            if (parts.length >= 2) {
                String name = parts[0].trim();
                String status = parts[1].trim().toLowerCase(Locale.ROOT);
                String duration = parts.length > 2 ? parts[2].trim() : "";
                String url = parts.length > 3 ? parts[3].trim() : "";
                if (status.contains("pass") || status.contains("success")) {
                    passCount++;
                } else {
                    StringBuilder failEntry = new StringBuilder("✗ ").append(name);
                    failEntry.append(" (").append(status);
                    if (!duration.isEmpty()) {
                        failEntry.append(", ").append(duration);
                    }
                    failEntry.append(")");
                    if (!url.isEmpty()) {
                        failEntry.append(" ").append(url);
                    }
                    failures.add(failEntry.toString());
                }
            } else {
                if (line.toLowerCase(Locale.ROOT).contains("fail") || line.toLowerCase(Locale.ROOT).contains("error")) {
                    failures.add("✗ " + line.trim());
                } else if (line.toLowerCase(Locale.ROOT).contains("pass")) {
                    passCount++;
                }
            }
        }

        if (total == 0) {
            return raw;
        }

        if (failures.isEmpty()) {
            return "✓ all checks passed (" + passCount + " checks)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("✗ ").append(failures.size()).append(" of ").append(total).append(" checks failed:\n");
        for (String fail : failures) {
            sb.append("  ").append(fail).append('\n');
        }
        if (passCount > 0) {
            sb.append("✓ ").append(passCount).append(" checks passed\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String compactRunView(String raw) {
        List<String> lines = raw.lines().toList();
        List<String> header = new ArrayList<>();
        List<String> jobs = new ArrayList<>();
        List<String> annotations = new ArrayList<>();
        List<String> artifacts = new ArrayList<>();

        String section = "HEADER";
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.equals("JOBS")) {
                section = "JOBS";
                continue;
            } else if (trimmed.equals("ANNOTATIONS")) {
                section = "ANNOTATIONS";
                continue;
            } else if (trimmed.equals("ARTIFACTS")) {
                section = "ARTIFACTS";
                continue;
            } else if (trimmed.startsWith("For more information")
                || trimmed.startsWith("To see what failed")
                || trimmed.startsWith("View this run on GitHub")) {
                section = "FOOTER";
                continue;
            }

            switch (section) {
                case "HEADER" -> {
                    if (!trimmed.isEmpty()) {
                        header.add(line);
                    }
                }
                case "JOBS" -> jobs.add(line);
                case "ANNOTATIONS" -> annotations.add(line);
                case "ARTIFACTS" -> {
                    if (!trimmed.isEmpty()) {
                        artifacts.add(line);
                    }
                }
                default -> {}
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String h : header) {
            sb.append(h).append('\n');
        }
        sb.append('\n');

        // Filter jobs: keep top-level job status. For failed jobs, keep only failing steps.
        if (!jobs.isEmpty()) {
            sb.append("JOBS\n");
            boolean inFailedJob = false;
            for (String jobLine : jobs) {
                if (jobLine.isBlank()) {
                    continue;
                }
                boolean isTopLevel = !jobLine.startsWith("  ") && !jobLine.startsWith("\t");
                if (isTopLevel) {
                    sb.append(jobLine).append('\n');
                    inFailedJob = jobLine.startsWith("X ") || jobLine.contains("failed");
                } else if (inFailedJob) {
                    String trimmedStep = jobLine.trim();
                    if (trimmedStep.startsWith("X ") || trimmedStep.contains("failed")) {
                        sb.append("  ").append(trimmedStep).append('\n');
                    }
                }
            }
            sb.append('\n');
        }

        // Filter annotations: keep only failure annotations
        List<String> failureAnnotations = new ArrayList<>();
        for (String ann : annotations) {
            String trimmed = ann.trim();
            if (trimmed.startsWith("X ")
                || trimmed.toLowerCase(Locale.ROOT).contains("exit code")
                || trimmed.toLowerCase(Locale.ROOT).contains("error")
                || trimmed.toLowerCase(Locale.ROOT).contains("failed")) {
                failureAnnotations.add(ann);
            }
        }

        if (!failureAnnotations.isEmpty()) {
            sb.append("ANNOTATIONS\n");
            for (String fa : failureAnnotations) {
                sb.append(fa).append('\n');
            }
            sb.append('\n');
        }

        if (!artifacts.isEmpty()) {
            sb.append("ARTIFACTS\n");
            for (String art : artifacts) {
                sb.append(art).append('\n');
            }
        }

        return sb.toString().stripTrailing();
    }
}
