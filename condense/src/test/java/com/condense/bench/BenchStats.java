package com.condense.bench;

import java.util.Arrays;

/**
 * Shared stats for JVM and native budget tests. Absolute microseconds are
 * informational; CI gates use {@link #MAX_RELATIVE_OVERHEAD}.
 */
public final class BenchStats {

    public static final double MAX_RELATIVE_OVERHEAD = 100.0;
    public static final double TIGHT_RELATIVE_OVERHEAD = 20.0;

    private BenchStats() {}

    public static double mean(double[] values) {
        if (values.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    public static double stdDev(double[] values, double mean) {
        if (values.length == 0) {
            return 0.0;
        }
        double sumSq = 0.0;
        for (double v : values) {
            double diff = v - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / values.length);
    }

    public static double median(double[] values) {
        return percentile(values, 50.0);
    }

    public static double percentile(double[] values, double p) {
        if (values.length == 0) {
            return 0.0;
        }
        if (values.length == 1) {
            return values[0];
        }
        double[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        if (p <= 0.0) {
            return copy[0];
        }
        if (p >= 100.0) {
            return copy[copy.length - 1];
        }
        double rank = (p / 100.0) * (copy.length - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        double fraction = rank - lower;
        return copy[lower] + fraction * (copy[upper] - copy[lower]);
    }

    public static double slope(double[] values) {
        int n = values.length;
        if (n < 2) {
            return 0.0;
        }
        double xMean = (n - 1) / 2.0;
        double yMean = mean(values);
        double numerator = 0.0;
        double denominator = 0.0;
        for (int i = 0; i < n; i++) {
            double xDiff = i - xMean;
            numerator += xDiff * (values[i] - yMean);
            denominator += xDiff * xDiff;
        }
        return denominator == 0.0 ? 0.0 : numerator / denominator;
    }

    public static double ratio(double numerator, double denominator) {
        return numerator / Math.max(denominator, 0.001);
    }

    public static long currentThreadAllocatedBytes() {
        try {
            java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
            if (bean instanceof com.sun.management.ThreadMXBean sunBean) {
                if (sunBean.isThreadAllocatedMemorySupported()) {
                    if (!sunBean.isThreadAllocatedMemoryEnabled()) {
                        sunBean.setThreadAllocatedMemoryEnabled(true);
                    }
                    return sunBean.getCurrentThreadAllocatedBytes();
                }
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }
}
