package com.condense.filter.cargo;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.*;
import com.condense.filter.strategy.AnsiStripStrategy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandFilters({
    @CommandFilter("cargo install"),
    @CommandFilter("cargo build")
})
@ApplicationScoped
public class CargoInstallFilter implements FilterStrategy {


    private static final Pattern FINISHED =
        Pattern.compile("Finished .+ in (.+)");
    private static final Pattern ERROR_LINE =
        Pattern.compile("^error(\\[.+?\\])?:", Pattern.MULTILINE);

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        String raw = result.readStdout().isBlank() ? result.readStderr() : result.readStdout();
        String clean = AnsiStripStrategy.strip(raw);

        if (!result.succeeded()) {
            // Return compiler errors only
            List<String> errors = clean.lines()
                .filter(l -> ERROR_LINE.matcher(l).find())
                .limit(15)
                .toList();
            String errOut = errors.isEmpty() ? clean : String.join("\n", errors);
            return FilterResult.of(result, errOut);
        }

        Matcher fin = FINISHED.matcher(clean);
        if (fin.find()) return FilterResult.of(result, "✓ " + fin.group(0).trim());

        return FilterResult.of(result, "✓ " + command.split(" ")[1] + " complete");
    }


}