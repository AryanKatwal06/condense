package com.condense.filter.strategy;

import java.util.Objects;

/**
 * A {@link CharSequence} wrapper that monitors elapsed execution time and throws
 * a {@link RegexTimeoutException} if character access continues beyond a specified deadline.
 *
 * <p>Java's standard backtracking regex engine ({@link java.util.regex.Matcher}) accesses
 * candidate input characters strictly via {@link CharSequence#charAt(int)} and {@link CharSequence#length()}.
 * In a catastrophic-backtracking scenario (such as {@code (a+)+$} against an adversarial input),
 * the matcher invokes {@code charAt(int)} hundreds of thousands or millions of times as it attempts
 * exponential search branches.
 *
 * <p>By periodically checking elapsed execution time against a hard deadline during {@code charAt(int)},
 * this decorator guarantees that pathological regular expressions are cleanly aborted in-thread
 * without relying on background worker threads, thread interruption, or Substrate VM threading primitives.
 */
public final class TimeoutCharSequence implements CharSequence {

    private static final int CHECK_FREQUENCY_MASK = 0xFF; // Check clock every 256 charAt invocations

    private final CharSequence inner;
    private final long timeoutNanos;
    private final long startTimeNanos;
    private final String patternDescription;
    private int checkCounter;

    public TimeoutCharSequence(CharSequence inner, long timeoutMillis) {
        this(inner, timeoutMillis, null);
    }

    public TimeoutCharSequence(CharSequence inner, long timeoutMillis, String patternDescription) {
        this(
            Objects.requireNonNull(inner, "inner CharSequence must not be null"),
            timeoutMillis * 1_000_000L,
            System.nanoTime(),
            patternDescription
        );
    }

    private TimeoutCharSequence(CharSequence inner, long timeoutNanos, long startTimeNanos, String patternDescription) {
        this.inner = inner;
        this.timeoutNanos = timeoutNanos;
        this.startTimeNanos = startTimeNanos;
        this.patternDescription = patternDescription;
        this.checkCounter = 0;
    }

    @Override
    public int length() {
        return inner.length();
    }

    @Override
    public char charAt(int index) {
        if ((checkCounter++ & CHECK_FREQUENCY_MASK) == 0) {
            checkTimeout();
        }
        return inner.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return new TimeoutCharSequence(inner.subSequence(start, end), timeoutNanos, startTimeNanos, patternDescription);
    }

    @Override
    public String toString() {
        return inner.toString();
    }

    private void checkTimeout() {
        if (timeoutNanos > 0 && (System.nanoTime() - startTimeNanos) > timeoutNanos) {
            long limitMillis = timeoutNanos / 1_000_000L;
            if (patternDescription != null && !patternDescription.isBlank()) {
                throw new RegexTimeoutException(limitMillis, patternDescription);
            } else {
                throw new RegexTimeoutException(limitMillis);
            }
        }
    }
}
