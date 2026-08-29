package com.condense.filter.pipeline;

import com.condense.filter.strategy.AnsiStripStrategy;
import com.condense.filter.strategy.GroupingStrategy;
import com.condense.filter.strategy.TreeCompressionStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class FilterPipelineBenchmarkTest {

    private static final Pattern ADDED_PATTERN = Pattern.compile("added (\\d+) packages?");
    private static final Pattern AUDIT_PATTERN = Pattern.compile("found (\\d+) vulnerabilit");
    private static final Pattern RULE_PATTERN = Pattern.compile("\\s+\\d+:\\d+\\s+(?:error|warning)\\s+.+?\\s+(\\S+)$");

    @Test
    @DisplayName("Compare pure transformation execution time (in-memory pipeline vs direct strategy)")
    void benchmarkPurePipelineTransformation() {
        System.out.println("==========================================================================================================================");
        System.out.println("PHASE 1 RIGOROUS PURE TRANSFORMATION LATENCY: DIRECT STRATEGY VS FILTER PIPELINE");
        System.out.println("==========================================================================================================================");
        System.out.printf("%-32s | %-18s | %-18s | %-12s | %-10s%n",
            "Strategy / Pipeline & Size", "Direct (µs ± std)", "Pipeline (µs ± std)", "Diff (µs)", "Overhead");
        System.out.println("--------------------------------------------------------------------------------------------------------------------------");

        // 1. AnsiStrip / Npm Pipeline
        String npmSmall = "added 15 packages in 1.2s\nfound 0 vulnerabilities\n";
        String npmMed = ("\u001B[32mprogress...\u001B[0m\r").repeat(500) + npmSmall;
        String npmLarge = ("\u001B[34m[INFO] fetching package...\u001B[0m\n").repeat(10000) + npmSmall;

        FilterPipeline npmPipeline = FilterPipeline.builder()
            .addStage(AnsiStripStrategy.INSTANCE)
            .addStage((clean, ctx) -> {
                Matcher added = ADDED_PATTERN.matcher(clean);
                Matcher audit = AUDIT_PATTERN.matcher(clean);
                StringBuilder sb = new StringBuilder("✓ npm install");
                if (added.find()) sb.append(": ").append(added.group(1)).append(" packages");
                if (audit.find()) sb.append(" | ").append(audit.group(0));
                return StageResult.continueWith(sb.toString());
            })
            .build();

        compare("Npm Pipeline (Small <1KB)",
            () -> directNpm(npmSmall),
            () -> npmPipeline.execute(npmSmall));

        compare("Npm Pipeline (Medium ~50KB)",
            () -> directNpm(npmMed),
            () -> npmPipeline.execute(npmMed));

        compare("Npm Pipeline (Large ~1MB)",
            () -> directNpm(npmLarge),
            () -> npmPipeline.execute(npmLarge));

        System.out.println("--------------------------------------------------------------------------------------------------------------------------");

        // 2. TreeCompression / Ls Pipeline
        String lsSmall = generatePaths(20);
        String lsMed = generatePaths(2000);
        String lsLarge = generatePaths(35000);

        FilterPipeline lsPipeline = FilterPipeline.builder()
            .addStage(TreeCompressionStrategy.INSTANCE)
            .build();

        compare("Ls Pipeline (Small <1KB)",
            () -> directLs(lsSmall),
            () -> lsPipeline.execute(lsSmall));

        compare("Ls Pipeline (Medium ~50KB)",
            () -> directLs(lsMed),
            () -> lsPipeline.execute(lsMed));

        compare("Ls Pipeline (Large ~1MB)",
            () -> directLs(lsLarge),
            () -> lsPipeline.execute(lsLarge));

        System.out.println("--------------------------------------------------------------------------------------------------------------------------");

        // 3. Grouping / ESLint Pipeline
        String eslintSmall = generateEslintText(5);
        String eslintMed = generateEslintText(800);
        String eslintLarge = generateEslintText(15000);

        GroupingStrategy groupingStage = new GroupingStrategy(RULE_PATTERN, false);
        FilterPipeline eslintPipeline = FilterPipeline.builder()
            .addStage((raw, ctx) -> {
                List<String> lines = raw.lines().toList();
                long errors   = lines.stream().filter(l -> l.contains("  error  ")).count();
                long warnings = lines.stream().filter(l -> l.contains("  warning  ")).count();
                if (errors == 0 && warnings == 0) return StageResult.stopWith("✓ no lint issues");
                String formattedGroups = groupingStage.process(raw, ctx).output();
                StringBuilder sb = new StringBuilder("eslint: ").append(errors)
                    .append(" error(s), ").append(warnings).append(" warning(s)\n");
                if (!formattedGroups.isBlank()) sb.append(formattedGroups);
                return StageResult.continueWith(sb.toString().stripTrailing());
            })
            .build();

        compare("ESLint Pipeline (Small <1KB)",
            () -> directEslint(eslintSmall),
            () -> eslintPipeline.execute(eslintSmall));

        compare("ESLint Pipeline (Medium ~50KB)",
            () -> directEslint(eslintMed),
            () -> eslintPipeline.execute(eslintMed));

        compare("ESLint Pipeline (Large ~1MB)",
            () -> directEslint(eslintLarge),
            () -> eslintPipeline.execute(eslintLarge));

        System.out.println("==========================================================================================================================");
    }

    private void compare(String label, Runnable directTask, Runnable pipelineTask) {
        // 1. Warmup: 300 iterations interleaved and discarded
        for (int i = 0; i < 300; i++) {
            if ((i & 1) == 0) {
                directTask.run();
                pipelineTask.run();
            } else {
                pipelineTask.run();
                directTask.run();
            }
        }

        // 2. Measurement: 500 interleaved iterations with per-run nanosecond measurement
        int iterations = 500;
        double[] directNanos = new double[iterations];
        double[] pipeNanos = new double[iterations];

        for (int i = 0; i < iterations; i++) {
            if ((i & 1) == 0) {
                long t0 = System.nanoTime();
                directTask.run();
                long t1 = System.nanoTime();
                directNanos[i] = (t1 - t0);

                long t2 = System.nanoTime();
                pipelineTask.run();
                long t3 = System.nanoTime();
                pipeNanos[i] = (t3 - t2);
            } else {
                long t2 = System.nanoTime();
                pipelineTask.run();
                long t3 = System.nanoTime();
                pipeNanos[i] = (t3 - t2);

                long t0 = System.nanoTime();
                directTask.run();
                long t1 = System.nanoTime();
                directNanos[i] = (t1 - t0);
            }
        }

        double meanDirectUs = mean(directNanos) / 1_000.0;
        double stdDirectUs = stdDev(directNanos, mean(directNanos)) / 1_000.0;
        double meanPipeUs = mean(pipeNanos) / 1_000.0;
        double stdPipeUs = stdDev(pipeNanos, mean(pipeNanos)) / 1_000.0;

        double diffUs = meanPipeUs - meanDirectUs;
        double overheadPct = meanDirectUs > 0 ? (diffUs / meanDirectUs) * 100.0 : 0.0;

        String directStr = String.format("%.2f ± %.1f", meanDirectUs, stdDirectUs);
        String pipeStr = String.format("%.2f ± %.1f", meanPipeUs, stdPipeUs);

        System.out.printf("%-32s | %18s | %18s | %+10.2f µs | %+6.1f%%%n",
            label, directStr, pipeStr, diffUs, overheadPct);
    }

    private static double mean(double[] values) {
        double sum = 0.0;
        for (double v : values) sum += v;
        return sum / values.length;
    }

    private static double stdDev(double[] values, double mean) {
        double sumSq = 0.0;
        for (double v : values) {
            double diff = v - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / values.length);
    }

    private String directNpm(String raw) {
        String clean = AnsiStripStrategy.strip(raw);
        Matcher added = ADDED_PATTERN.matcher(clean);
        Matcher audit = AUDIT_PATTERN.matcher(clean);
        StringBuilder sb = new StringBuilder("✓ npm install");
        if (added.find()) sb.append(": ").append(added.group(1)).append(" packages");
        if (audit.find()) sb.append(" | ").append(audit.group(0));
        return sb.toString();
    }

    private String directLs(String raw) {
        List<String> lines = raw.lines().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty()) return "(empty directory)";
        if (lines.size() <= 10) return raw;
        String tree = TreeCompressionStrategy.compress(lines);
        if (tree.isBlank()) return lines.size() + " items";
        return tree;
    }

    private String directEslint(String raw) {
        List<String> lines = raw.lines().toList();
        long errors = lines.stream().filter(l -> l.contains("  error  ")).count();
        long warnings = lines.stream().filter(l -> l.contains("  warning  ")).count();
        if (errors == 0 && warnings == 0) return "✓ no lint issues";
        Map<String, Integer> groups = GroupingStrategy.group(lines, RULE_PATTERN, false);
        StringBuilder sb = new StringBuilder("eslint: ").append(errors)
            .append(" error(s), ").append(warnings).append(" warning(s)\n");
        sb.append(GroupingStrategy.format(groups));
        return sb.toString().stripTrailing();
    }

    private static String generatePaths(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int dirId = i % 20;
            sb.append("src/main/com/example/module").append(dirId)
              .append("/Class").append(i).append(".java\n");
        }
        return sb.toString();
    }

    private static String generateEslintText(int errorCount) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < errorCount; i++) {
            int line = (i % 500) + 1;
            int col = (i % 80) + 1;
            String rule = (i % 2 == 0) ? "no-unused-vars" : "quotes";
            sb.append("  ").append(line).append(":").append(col)
              .append("  error  Rule violation message here  ").append(rule).append("\n");
        }
        return sb.toString();
    }
}
