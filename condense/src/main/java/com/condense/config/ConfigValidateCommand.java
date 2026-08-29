package com.condense.config;

import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.pipeline.config.FilterOverrideValidationResult;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code condense config validate} — validates declarative filter override configuration files.
 */
@Command(
    name = "validate",
    description = "Validate declarative filter override files.",
    mixinStandardHelpOptions = true
)
@Dependent
public class ConfigValidateCommand implements Callable<Integer> {

    @Option(
        names = {"-f", "--file"},
        description = "Explicit path to a filter override file to validate.",
        paramLabel = "PATH"
    )
    Path explicitFile;

    @Option(
        names = "--project",
        description = "Validate only the project-local override file (.condense/filters.toml)."
    )
    boolean projectOnly;

    @Option(
        names = "--global",
        description = "Validate only the user-global override file (filters.toml in config dir)."
    )
    boolean globalOnly;

    @Inject
    FilterOverrideLoader overrideLoader;

    public ConfigValidateCommand() {
        this.overrideLoader = new FilterOverrideLoader();
    }

    @Inject
    public ConfigValidateCommand(FilterOverrideLoader overrideLoader) {
        this.overrideLoader = overrideLoader != null ? overrideLoader : new FilterOverrideLoader();
    }

    @Override
    public Integer call() {
        if (overrideLoader == null) {
            overrideLoader = new FilterOverrideLoader();
        }

        if (explicitFile != null) {
            Path parent = explicitFile.getParent() != null ? explicitFile.getParent() : Path.of(".");
            FilterOverrideValidationResult result = overrideLoader.validateFile(explicitFile, parent);
            printResult("file", explicitFile.toString(), result);
            return result.isValid() ? 0 : 1;
        }

        boolean hasError = false;

        if (!globalOnly) {
            FilterOverrideValidationResult projectResult = overrideLoader.validateProjectOverrides(null);
            printResult("project", FilterOverrideLoader.PROJECT_OVERRIDE_REL_PATH, projectResult);
            if (!projectResult.isValid() && projectResult.status() != FilterOverrideValidationResult.Status.NOT_FOUND) {
                hasError = true;
            }
        }

        if (!projectOnly) {
            FilterOverrideValidationResult globalResult = overrideLoader.validateGlobalOverrides();
            printResult("global", "filters.toml", globalResult);
            if (!globalResult.isValid() && globalResult.status() != FilterOverrideValidationResult.Status.NOT_FOUND) {
                hasError = true;
            }
        }

        return hasError ? 1 : 0;
    }

    private void printResult(String scope, String label, FilterOverrideValidationResult result) {
        switch (result.status()) {
            case VALID -> {
                System.out.println(String.format("✓ [%s] %s is valid (%d filter override%s defined)",
                    scope, label, result.filterCount(), result.filterCount() == 1 ? "" : "s"));
            }
            case NOT_FOUND -> {
                System.out.println(String.format("○ [%s] %s (not present)", scope, label));
            }
            case SYNTAX_ERROR -> {
                System.err.println(String.format("✗ [%s] %s has syntax errors:", scope, label));
                for (String err : result.errors()) {
                    System.err.println("    " + err);
                }
            }
            case SEMANTIC_ERROR -> {
                System.err.println(String.format("✗ [%s] %s has semantic validation errors:", scope, label));
                for (String err : result.errors()) {
                    System.err.println("    " + err);
                }
            }
            case SECURITY_VIOLATION -> {
                System.err.println(String.format("✗ [%s] %s security refusal:", scope, label));
                for (String err : result.errors()) {
                    System.err.println("    " + err);
                }
            }
            case ERROR -> {
                System.err.println(String.format("✗ [%s] %s validation error:", scope, label));
                for (String err : result.errors()) {
                    System.err.println("    " + err);
                }
            }
        }
    }
}
