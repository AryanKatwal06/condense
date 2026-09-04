package com.condense.core;

import java.nio.file.Path;

/**
 * Estimates token counts for analytics. Implementations must be native-image
 * safe (no locale, no default charset, no regex) and must use the same unit
 * for strings and files.
 */
public interface TokenEstimator {

    /**
     * Estimates tokens in {@code text}.
     *
     * @param text null and empty return 0
     * @return estimated token count, always &gt;= 0
     */
    int count(String text);

    /**
     * Estimates tokens in a UTF-8 file by decoding (malformed bytes replaced)
     * and delegating to {@link #count(String)}. Must not use {@code Files.size}.
     *
     * @param file null or unreadable returns 0 (fail-open)
     * @return estimated token count, always &gt;= 0
     */
    int count(Path file);
}
