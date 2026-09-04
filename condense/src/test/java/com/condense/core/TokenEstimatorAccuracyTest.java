package com.condense.core;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gates {@link Utf8WeightedTokenEstimator} against cl100k_base on the filter
 * fixtures plus the Unicode token corpus. Fails if a silent estimator change
 * blows the published p95 relative error.
 */
class TokenEstimatorAccuracyTest {

    @Test
    void p95RelativeErrorVsCl100kStaysWithinPublishedBound() throws Exception {
        Encoding encoding = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);
        TokenEstimator estimator = Utf8WeightedTokenEstimator.INSTANCE;

        List<Path> corpus = corpusFiles();
        assertThat(corpus)
            .as("expected filter fixtures plus token-corpus samples")
            .hasSizeGreaterThanOrEqualTo(40);

        List<Double> errors = new ArrayList<>(corpus.size());
        List<String> rows = new ArrayList<>(corpus.size());
        for (Path file : corpus) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            int estimated = estimator.count(text);
            int reference = encoding.countTokens(text);
            double rel = Math.abs(estimated - reference) / (double) Math.max(reference, 1);
            errors.add(rel);
            rows.add(String.format(Locale.ROOT, "%.4f  est=%d ref=%d  %s",
                rel, estimated, reference, corpusRoot().relativize(file)));
        }

        errors.sort(Comparator.naturalOrder());
        double p95 = percentile(errors, 0.95);
        double max = errors.get(errors.size() - 1);
        double mean = errors.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        System.out.printf(Locale.ROOT,
            "token estimator vs cl100k_base  n=%d  mean=%.4f  p95=%.4f  max=%.4f  published=%.2f  gate=%.2f%n",
            errors.size(), mean, p95, max,
            Utf8WeightedTokenEstimator.PUBLISHED_P95_REL_ERROR,
            Utf8WeightedTokenEstimator.PUBLISHED_P95_REL_ERROR
                + Utf8WeightedTokenEstimator.ACCURACY_GATE_CUSHION);

        assertThat(p95)
            .as("p95 relative error vs cl100k_base must stay within the published bound plus cushion; worst rows:%n%s",
                String.join("\n", rows.stream().sorted().toList().subList(Math.max(0, rows.size() - 5), rows.size())))
            .isLessThanOrEqualTo(
                Utf8WeightedTokenEstimator.PUBLISHED_P95_REL_ERROR
                    + Utf8WeightedTokenEstimator.ACCURACY_GATE_CUSHION);
    }

    private static double percentile(List<Double> sortedAscending, double p) {
        if (sortedAscending.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p * sortedAscending.size()) - 1;
        index = Math.max(0, Math.min(sortedAscending.size() - 1, index));
        return sortedAscending.get(index);
    }

    private static List<Path> corpusFiles() throws IOException {
        Path root = corpusRoot();
        List<Path> files = new ArrayList<>();
        collectTxt(root.resolve("fixtures"), files);
        collectTxt(root.resolve("token-corpus"), files);
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    private static void collectTxt(Path dir, List<Path> into) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".txt"))
                .forEach(into::add);
        }
    }

    private static Path corpusRoot() {
        Path fromModule = Path.of("src/test/resources");
        if (Files.isDirectory(fromModule)) {
            return fromModule;
        }
        Path fromRepo = Path.of("condense/src/test/resources");
        if (Files.isDirectory(fromRepo)) {
            return fromRepo;
        }
        throw new IllegalStateException("Cannot locate src/test/resources from " + Path.of(".").toAbsolutePath());
    }
}
