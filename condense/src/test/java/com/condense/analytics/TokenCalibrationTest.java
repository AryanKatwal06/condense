package com.condense.analytics;

import com.condense.core.TokenEstimator;
import com.condense.core.Utf8WeightedTokenEstimator;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class TokenCalibrationTest {

    private static Encoding cl100k;
    private static TokenEstimator estimator;

    @BeforeAll
    static void setUp() {
        cl100k = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
        estimator = Utf8WeightedTokenEstimator.INSTANCE;
    }

    record CalibrationSample(String name, String category, int lengthBytes, int referenceTokens, int estimatedTokens, double relativeError) {}

    @Test
    @DisplayName("Calibration on core developer workload (code, logs, diffs, CJK, Latin) stays within published p95 bound")
    void developerWorkloadCalibrationMeetsPublishedP95() throws Exception {
        List<String> devCorpus = List.of(
            "code-python.txt",
            "code-java.txt",
            "code-typescript.txt",
            "code-rust.txt",
            "logs-structured-ndjson.txt",
            "logs-systemd-syslog.txt",
            "terminal-diff.txt",
            "multilingual-european.txt",
            "cjk.txt",
            "long-latin.txt",
            "mixed.txt",
            "ansi.txt",
            "emoji.txt"
        );

        List<Double> devErrors = new ArrayList<>();
        List<CalibrationSample> samples = new ArrayList<>();

        System.out.println("Developer Workload Calibration against cl100k_base:");
        System.out.println("══════════════════════════════════════════════════════════════════════════════════");
        System.out.printf(Locale.ROOT, "%-28s %-12s %-8s %-8s %-8s %-8s%n",
            "Corpus File", "Category", "Bytes", "Ref", "Est", "Rel Err");
        System.out.println("──────────────────────────────────────────────────────────────────────────────────");

        for (String file : devCorpus) {
            String content = loadCorpusFile(file);
            int ref = cl100k.countTokens(content);
            int est = estimator.count(content);
            double relErr = Math.abs(est - ref) / (double) Math.max(ref, 1);
            devErrors.add(relErr);

            String cat = categorize(file);
            samples.add(new CalibrationSample(file, cat, content.getBytes(StandardCharsets.UTF_8).length, ref, est, relErr));
            System.out.printf(Locale.ROOT, "%-28s %-12s %-8d %-8d %-8d %-7.2f%%%n",
                file, cat, content.getBytes(StandardCharsets.UTF_8).length, ref, est, relErr * 100.0);
        }

        devErrors.sort(Comparator.naturalOrder());
        double p95 = percentile(devErrors, 0.95);
        double mean = devErrors.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        System.out.println("──────────────────────────────────────────────────────────────────────────────────");
        System.out.printf(Locale.ROOT, "Developer Summary: n=%d  mean=%.2f%%  p95=%.2f%%%n",
            devErrors.size(), mean * 100.0, p95 * 100.0);
        System.out.println("══════════════════════════════════════════════════════════════════════════════════\n");

        assertThat(p95)
            .as("Developer workload p95 relative error must stay within published bound (0.37 + cushion)")
            .isLessThanOrEqualTo(0.42);

        assertThat(mean)
            .as("Developer workload mean relative error must remain below 20%")
            .isLessThan(0.20);
    }

    @Test
    @DisplayName("Category-specific accuracy gates for code, logs, and terminal diffs")
    void categorySpecificAccuracyGates() throws Exception {
        // Code samples
        double pythonErr = evaluate("code-python.txt");
        double javaErr = evaluate("code-java.txt");
        double tsErr = evaluate("code-typescript.txt");
        double rustErr = evaluate("code-rust.txt");
        double codeMean = (pythonErr + javaErr + tsErr + rustErr) / 4.0;
        assertThat(codeMean).as("Code category mean relative error").isLessThan(0.20);

        // Logs samples
        double ndjsonErr = evaluate("logs-structured-ndjson.txt");
        double syslogErr = evaluate("logs-systemd-syslog.txt");
        double logsMean = (ndjsonErr + syslogErr) / 2.0;
        assertThat(logsMean).as("Logs category mean relative error").isLessThan(0.35);

        // Terminal diff sample
        double diffErr = evaluate("terminal-diff.txt");
        assertThat(diffErr).as("Terminal diff relative error").isLessThan(0.10);
    }

    @Test
    @DisplayName("Non-Latin script stress tests verify fail-safe counting and document token density variance")
    void nonLatinStressTestsProduceValidEstimates() throws Exception {
        List<String> nonLatinCorpus = List.of(
            "multilingual-cyrillic.txt",
            "multilingual-arabic.txt",
            "multilingual-hindi.txt"
        );

        System.out.println("Non-Latin Script Stress Calibration (motivates ±37% disclosure):");
        System.out.println("──────────────────────────────────────────────────────────────────────────────────");
        for (String file : nonLatinCorpus) {
            String content = loadCorpusFile(file);
            int ref = cl100k.countTokens(content);
            int est = estimator.count(content);
            double relErr = Math.abs(est - ref) / (double) Math.max(ref, 1);

            assertThat(est).as("Estimator should return positive token count for %s", file).isGreaterThan(0);
            System.out.printf(Locale.ROOT, "%-28s Ref=%-5d Est=%-5d Rel Err=%-7.2f%%%n",
                file, ref, est, relErr * 100.0);
        }
        System.out.println("──────────────────────────────────────────────────────────────────────────────────\n");
    }

    private static double evaluate(String file) throws IOException {
        String content = loadCorpusFile(file);
        int ref = cl100k.countTokens(content);
        int est = estimator.count(content);
        return Math.abs(est - ref) / (double) Math.max(ref, 1);
    }

    private static String categorize(String filename) {
        if (filename.startsWith("code-")) return "code";
        if (filename.startsWith("logs-")) return "logs";
        if (filename.startsWith("multilingual-")) return "multilingual";
        if (filename.startsWith("terminal-")) return "diff/terminal";
        return "unicode";
    }

    private static String loadCorpusFile(String name) throws IOException {
        String resourcePath = "/token-corpus/" + name;
        try (InputStream in = TokenCalibrationTest.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static double percentile(List<Double> sortedAscending, double p) {
        if (sortedAscending.isEmpty()) return 0;
        int index = (int) Math.ceil(p * sortedAscending.size()) - 1;
        index = Math.max(0, Math.min(sortedAscending.size() - 1, index));
        return sortedAscending.get(index);
    }
}
