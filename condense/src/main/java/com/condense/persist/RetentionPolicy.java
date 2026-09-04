package com.condense.persist;

/**
 * Hardcoded retention window for analytics rows and tee dumps.
 *
 * <p>Not configurable in this phase — a {@code [analytics]} config section would
 * churn TOML schema and reflection registration for a single constant.
 */
public final class RetentionPolicy {

    public static final int RETENTION_DAYS = 90;
    public static final int TEE_SWEEP_LIMIT = 256;

    private RetentionPolicy() {}

    public static long cutoffEpochSeconds() {
        return cutoffEpochSeconds(System.currentTimeMillis() / 1000L);
    }

    public static long cutoffEpochSeconds(long nowEpochSeconds) {
        return nowEpochSeconds - (long) RETENTION_DAYS * 86400L;
    }
}
