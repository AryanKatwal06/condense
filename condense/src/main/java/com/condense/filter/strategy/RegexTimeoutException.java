package com.condense.filter.strategy;

/**
 * Thrown when a regular-expression matching operation exceeds its allotted time limit.
 *
 * <p>Used as an in-thread mitigation against catastrophic-backtracking regular expression
 * denial-of-service (ReDoS) attacks when executing untrusted patterns from declarative overrides.
 */
public class RegexTimeoutException extends RuntimeException {

    private final long timeoutMillis;

    public RegexTimeoutException(String message) {
        super(message);
        this.timeoutMillis = -1;
    }

    public RegexTimeoutException(long timeoutMillis) {
        super("Regular expression execution timed out after " + timeoutMillis + "ms");
        this.timeoutMillis = timeoutMillis;
    }

    public RegexTimeoutException(long timeoutMillis, String pattern) {
        super("Regular expression execution timed out after " + timeoutMillis + "ms for pattern '" + pattern + "'");
        this.timeoutMillis = timeoutMillis;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }
}
