package com.zapproxy.filter.cargo;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

@CommandFilter("cargo test")
@ApplicationScoped
public class CargoTestFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(CargoTestFilter.class);


    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        try {
            long sizeOut = java.nio.file.Files.size(result.stdoutFile());
            long sizeErr = java.nio.file.Files.size(result.stderrFile());
            java.util.stream.Stream<String> stream;
            
            if (sizeOut == 0 && sizeErr > 0) {
                stream = result.stderrLines();
            } else {
                stream = result.stdoutLines();
            }

            List<String> failures = new ArrayList<>();
            String[] resultLine = {null};
            boolean[] hasCompile = {false};
            List<String> errors = new ArrayList<>();

            try (stream) {
                stream.forEach(line -> {
                    if (line.startsWith("test ") && line.contains("...") && line.endsWith("FAILED")) {
                        failures.add("  FAILED: " + line.substring(5, line.indexOf(" ...")));
                    } else if (line.startsWith("test result: ") && (line.contains("ok.") || line.contains("FAILED."))) {
                        resultLine[0] = line.trim();
                    } else if (line.trim().startsWith("Compiling ")) {
                        hasCompile[0] = true;
                    } else if (line.startsWith("error") || line.startsWith("  -->")) {
                        if (errors.size() < 10) errors.add(line);
                    }
                });
            }

            if (failures.isEmpty()) {
                // Check for compile error
                if (result.exitCode() != 0 && hasCompile[0]) {
                    String out = "cargo test: compile error\n" + String.join("\n", errors);
                    return FilterResult.of(result, out);
                }
                String summary = resultLine[0] != null ? resultLine[0] : "✓ all tests passed";
                return FilterResult.of(result, summary);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("cargo test: ").append(failures.size()).append(" failure(s)\n");
            failures.forEach(f -> sb.append(f).append('\n'));
            if (resultLine[0] != null) sb.append(resultLine[0]);

            return FilterResult.of(result, sb.toString().stripTrailing());

        } catch (Exception e) {
            log.warnf("CargoTestFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result);
        }
    }
}