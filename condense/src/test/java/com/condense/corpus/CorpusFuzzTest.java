package com.condense.corpus;

import com.condense.core.FilterResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CorpusFuzzTest {

    @Test
    void seededMutationsNeverThrowAndNeverDropPreservedSignals() throws Exception {
        CorpusCatalog.Catalog catalog = CorpusCatalog.load();
        Random random = new Random(CorpusCatalog.FUZZ_SEED);
        int cases = 0;

        for (CorpusCatalog.Entry entry : catalog.entries()) {
            if (!entry.claimsToCompress()) {
                continue;
            }
            String original = CorpusRunner.loadFixture(entry.fixture());
            for (int i = 0; i < CorpusCatalog.FUZZ_ITERATIONS_PER_ENTRY; i++) {
                String mutated = mutate(original, random);
                for (String signal : entry.criticalSignals()) {
                    if (original.contains(signal)) {
                        assertThat(mutated)
                            .as("fuzz mutation must keep signal '%s' that was in the original fixture for %s",
                                signal, entry.id())
                            .contains(signal);
                    }
                }
                FilterResult[] holder = new FilterResult[1];
                assertThatCode(() -> holder[0] = CorpusRunner.apply(entry, mutated))
                    .as("%s iteration %d must not throw", entry.id(), i)
                    .doesNotThrowAnyException();
                String output = holder[0].output() == null ? "" : holder[0].output();
                for (String signal : entry.criticalSignals()) {
                    if (original.contains(signal) && mutated.contains(signal)) {
                        assertThat(output)
                            .as("%s iteration %d dropped signal '%s' that was still in the mutated input",
                                entry.id(), i, signal)
                            .contains(signal);
                    }
                }
                cases++;
            }
        }

        assertThat(cases).isGreaterThan(0);
    }

    static String mutate(String original, Random random) {
        String trimmed = original.stripLeading();
        boolean jsonPayload = trimmed.startsWith("{") || trimmed.startsWith("[");
        if (jsonPayload) {
            return original;
        }
        List<String> lines = new ArrayList<>(original.lines().toList());
        if (lines.isEmpty()) {
            lines.add("");
        }

        if (!looksLikeGitPorcelain(lines)) {
            int prefixCount = random.nextInt(4);
            for (int i = 0; i < prefixCount; i++) {
                lines.add(0, noiseLine(random, i));
            }
        }

        int blanks = random.nextInt(3);
        for (int i = 0; i < blanks; i++) {
            int at = lines.isEmpty() ? 0 : 1 + random.nextInt(Math.max(1, lines.size() - 1));
            at = Math.min(at, lines.size());
            lines.add(at, "");
        }

        return String.join("\n", lines);
    }

    private static boolean looksLikeGitPorcelain(List<String> lines) {
        List<String> sample = lines.stream().filter(l -> !l.isBlank()).limit(5).toList();
        if (sample.isEmpty()) {
            return false;
        }
        return sample.stream().allMatch(line ->
            line.length() >= 3
                && line.charAt(2) == ' '
                && "MADRCU?! ".indexOf(line.charAt(0)) >= 0
                && "MADRCU?! ".indexOf(line.charAt(1)) >= 0);
    }

    private static String noiseLine(Random random, int index) {
        return "noise_" + index + "_" + Integer.toHexString(random.nextInt());
    }
}
