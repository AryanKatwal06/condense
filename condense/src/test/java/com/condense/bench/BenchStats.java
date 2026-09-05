package com.condense.bench;

import java.util.Arrays;

/**
 * Shared stats for JVM and native budget tests. Absolute microseconds are
 * informational; CI gates use {@link #MAX_RELATIVE_OVERHEAD}.
 */
public final class BenchStats {

    public static final double MAX_RELATIVE_OVERHEAD = 100.0;

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
        if (values.length == 0) {
            return 0.0;
        }
        double[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        int mid = copy.length / 2;
        if ((copy.length & 1) == 1) {
            return copy[mid];
        }
        return (copy[mid - 1] + copy[mid]) / 2.0;
    }

    public static double ratio(double numerator, double denominator) {
        return numerator / Math.max(denominator, 0.001);
    }
}
