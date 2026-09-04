package com.condense.filter.stage;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.AnsiStripStrategy;

import java.util.List;

public final class PipInstallSummaryStage implements FilterStage {
    public static final PipInstallSummaryStage INSTANCE = new PipInstallSummaryStage();

    private PipInstallSummaryStage() {}

    @Override
    public StageResult process(String clean, FilterContext context) {
        List<String> installed = clean.lines()
            .filter(l -> l.startsWith("Successfully installed"))
            .toList();
        if (installed.isEmpty()) {
            String lastLine = AnsiStripStrategy.lastMeaningfulLine(clean);
            return StageResult.continueWith(lastLine.isBlank() ? "✓ pip install" : lastLine);
        }
        return StageResult.continueWith(installed.get(installed.size() - 1).trim());
    }
}
