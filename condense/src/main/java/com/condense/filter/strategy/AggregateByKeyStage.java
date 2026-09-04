package com.condense.filter.strategy;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Counts non-blank lines by a derived key and formats the top keys.
 */
public final class AggregateByKeyStage implements FilterStage {

    public interface HeaderFormatter {
        String format(int lineCount, int keyCount);
    }

    private final Function<String, String> keyOf;
    private final HeaderFormatter header;
    private final int topN;

    public AggregateByKeyStage(Function<String, String> keyOf, HeaderFormatter header, int topN) {
        this.keyOf = keyOf;
        this.header = header;
        this.topN = topN;
    }

    @Override
    public StageResult process(String input, FilterContext context) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int lineCount = 0;
        for (String line : (input != null ? input : "").lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            lineCount++;
            counts.merge(keyOf.apply(line), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder(header.format(lineCount, counts.size()));
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(topN)
            .forEach(e -> sb.append("  ").append(e.getKey()).append(": ")
                .append(e.getValue()).append('\n'));
        return StageResult.continueWith(sb.toString().stripTrailing());
    }
}
