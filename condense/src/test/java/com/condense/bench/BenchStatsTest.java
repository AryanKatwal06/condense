package com.condense.bench;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BenchStatsTest {

    @Test
    void emptyArrayReturnsZeros() {
        double[] empty = new double[0];
        assertThat(BenchStats.mean(empty)).isZero();
        assertThat(BenchStats.stdDev(empty, 0.0)).isZero();
        assertThat(BenchStats.median(empty)).isZero();
        assertThat(BenchStats.percentile(empty, 95.0)).isZero();
        assertThat(BenchStats.slope(empty)).isZero();
    }

    @Test
    void singleElementPercentileAndStats() {
        double[] single = new double[] {42.0};
        assertThat(BenchStats.mean(single)).isEqualTo(42.0);
        assertThat(BenchStats.median(single)).isEqualTo(42.0);
        assertThat(BenchStats.percentile(single, 0.0)).isEqualTo(42.0);
        assertThat(BenchStats.percentile(single, 50.0)).isEqualTo(42.0);
        assertThat(BenchStats.percentile(single, 100.0)).isEqualTo(42.0);
        assertThat(BenchStats.slope(single)).isZero();
    }

    @Test
    void percentileCalculatesAccurateQuantiles() {
        double[] values = new double[] {10.0, 20.0, 30.0, 40.0, 50.0};
        assertThat(BenchStats.percentile(values, 0.0)).isEqualTo(10.0);
        assertThat(BenchStats.percentile(values, 50.0)).isEqualTo(30.0);
        assertThat(BenchStats.median(values)).isEqualTo(30.0);
        assertThat(BenchStats.percentile(values, 100.0)).isEqualTo(50.0);
        assertThat(BenchStats.percentile(values, 25.0)).isEqualTo(20.0);
        assertThat(BenchStats.percentile(values, 75.0)).isEqualTo(40.0);
    }

    @Test
    void linearSlopeDetectsMonotonicGrowthAndFlatLines() {
        double[] flat = new double[] {5.0, 5.0, 5.0, 5.0, 5.0};
        assertThat(BenchStats.slope(flat)).isCloseTo(0.0, within(1e-9));

        double[] increasing = new double[] {1.0, 2.0, 3.0, 4.0, 5.0};
        assertThat(BenchStats.slope(increasing)).isCloseTo(1.0, within(1e-9));

        double[] decreasing = new double[] {10.0, 8.0, 6.0, 4.0, 2.0};
        assertThat(BenchStats.slope(decreasing)).isCloseTo(-2.0, within(1e-9));
    }

    @Test
    void threadAllocatedBytesReturnsNonNegativeOrUnsupported() {
        long bytes = BenchStats.currentThreadAllocatedBytes();
        if (bytes >= 0) {
            byte[] dummy = new byte[10_000];
            long after = BenchStats.currentThreadAllocatedBytes();
            assertThat(after).isGreaterThanOrEqualTo(bytes);
        }
    }
}
