package com.zapproxy.core;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Default filter strategy used when no registered {@link FilterStrategy} matches
 * the command. Returns the raw command output unchanged.
 *
 * <p>Records rawTokens == outTokens (0% savings) in the analytics database, which
 * is correct — no compression was applied.
 */
@ApplicationScoped
public class PassthroughStrategy implements FilterStrategy {

    private static final Logger log = Logger.getLogger(PassthroughStrategy.class);

    @Override
    public FilterResult apply(
            String command,
            ExecutionResult result,
            ZapConfig config,
            int verbose,
            boolean ultraCompact) {

        log.debugf("No filter registered for '%s' — passing through", command);

        // For passthrough: use combined stdout+stderr so the AI sees everything.
        // If exit is non-zero, stderr is the important content.
        return FilterResult.passthrough(result);
    }
}
