package com.zapproxy.filter.cargo;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandFilter("cargo test")
@ApplicationScoped
public class CargoTestFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(CargoTestFilter.class);

    private static final Pattern FAILED_TEST =
        Pattern.compile("^test (.+) \\.\\.\\.\\s+FAILED", Pattern.MULTILINE);
    private static final Pattern TEST_RESULT =
        Pattern.compile("^test result: (ok|FAILED)\\. (\\d+) passed.*", Pattern.MULTILINE);
    private static final Pattern COMPILING =
        Pattern.compile("^\\s*Compiling ", Pattern.MULTILINE);

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        try {
            String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();

            List<String> failures = new ArrayList<>();
            Matcher fm = FAILED_TEST.matcher(raw);
            while (fm.find()) failures.add("  FAILED: " + fm.group(1).trim());

            Matcher rm = TEST_RESULT.matcher(raw);
            String resultLine = rm.find() ? rm.group(0).trim() : null;

            if (failures.isEmpty()) {
                // Check for compile error
                if (result.exitCode() != 0 && COMPILING.matcher(raw).find()) {
                    // Extract compiler error lines
                    List<String> errors = raw.lines()
                        .filter(l -> l.startsWith("error") || l.startsWith("  -->"))
                        .limit(10)
                        .toList();
                    String out = "cargo test: compile error\n" + String.join("\n", errors);
                    return FilterResult.of(raw, out);
                }
                String summary = resultLine != null ? resultLine : "✓ all tests passed";
                return FilterResult.of(raw, summary);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("cargo test: ").append(failures.size()).append(" failure(s)\n");
            failures.forEach(f -> sb.append(f).append('\n'));
            if (resultLine != null) sb.append(resultLine);

            return FilterResult.of(raw, sb.toString().stripTrailing());

        } catch (Exception e) {
            log.warnf("CargoTestFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result.stdout());
        }
    }
}