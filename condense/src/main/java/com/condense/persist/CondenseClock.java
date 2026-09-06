package com.condense.persist;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Injectable clock for durable timestamps. Production is {@link Clock#systemUTC()}.
 * Proxy timeouts stay on wall-clock {@code System.nanoTime} / {@code currentTimeMillis}.
 */
public final class CondenseClock {

    private static final AtomicReference<Clock> INSTALLED = new AtomicReference<>(Clock.systemUTC());

    private CondenseClock() {}

    public static Clock clock() {
        return INSTALLED.get();
    }

    public static Instant instant() {
        return clock().instant();
    }

    public static long millis() {
        return clock().millis();
    }

    public static long epochSeconds() {
        return instant().getEpochSecond();
    }

    /** Tests only. */
    public static void install(Clock clock) {
        INSTALLED.set(clock == null ? Clock.systemUTC() : clock);
    }

    public static void restore() {
        INSTALLED.set(Clock.systemUTC());
    }
}
