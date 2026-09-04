package com.condense.corpus;

import com.condense.core.FilterResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class FidelityCorpusTest {

    @Test
    void everyEntryRetainsCriticalSignalsAndMeetsSavingsFloor() throws Exception {
        CorpusCatalog.Catalog catalog = CorpusCatalog.load();
        List<String> failures = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        report.append(String.format(Locale.ROOT,
            "%-32s %6s %6s %6s %s%n", "id", "save%", "floor", "filt", "exemption"));

        for (CorpusCatalog.Entry entry : catalog.entries()) {
            FilterResult result = CorpusRunner.apply(entry);
            String output = result.output() == null ? "" : result.output();

            for (String signal : entry.criticalSignals()) {
                if (!output.contains(signal)) {
                    failures.add(entry.id() + " missing '" + signal + "' in: "
                        + output.replace("\n", " | "));
                }
            }

            if (entry.claimsToCompress() && result.savingsPct() < entry.savingsFloor()) {
                failures.add(String.format(Locale.ROOT,
                    "%s savings %d%% < floor %d%% (raw=%d out=%d)",
                    entry.id(), result.savingsPct(), entry.savingsFloor(),
                    result.rawTokens(), result.outTokens()));
            }

            report.append(String.format(Locale.ROOT,
                "%-32s %5d%% %5s %6s %s%n",
                entry.id(),
                result.savingsPct(),
                entry.savingsFloor() == null ? "-" : entry.savingsFloor() + "%",
                result.wasFiltered(),
                entry.savingsExemption() == null ? "" : entry.savingsExemption().toJson()));
        }

        System.out.print(report);
        assertThat(failures)
            .as("fidelity corpus violations")
            .isEmpty();
    }
}
