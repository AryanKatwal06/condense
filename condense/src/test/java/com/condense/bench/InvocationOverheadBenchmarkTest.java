package com.condense.bench;

import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.StageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * General invocation-overhead baseline. Absolute times are informational.
 * The CI assertion is a generous relative bound so wall-clock noise cannot
 * flake the build. Native cold-start and size gates live in {@code NativeBudgetIT}.
 */
class InvocationOverheadBenchmarkTest {

    private static final int WARMUP = 300;
    private static final int ITERATIONS = 500;
    private static final String SAMPLE = "line one\nline two\nline three\n";

    @Test
    @DisplayName("Empty pipeline vs identity stage prints mean±stddev and stays within a generous bound")
    void identityStageOverheadIsBounded() {
        FilterPipeline empty = FilterPipeline.builder().build();
        FilterPipeline identity = FilterPipeline.builder()
            .addStage((input, ctx) -> StageResult.continueWith(input))
            .build();

        // Warmup: interleaved, discarded
        for (int i = 0; i < WARMUP; i++) {
            if ((i & 1) == 0) {
                empty.execute(SAMPLE);
                identity.execute(SAMPLE);
            } else {
                identity.execute(SAMPLE);
                empty.execute(SAMPLE);
            }
        }

        double[] emptyNanos = new double[ITERATIONS];
        double[] identityNanos = new double[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            if ((i & 1) == 0) {
                long t0 = System.nanoTime();
                empty.execute(SAMPLE);
                emptyNanos[i] = System.nanoTime() - t0;

                long t2 = System.nanoTime();
                identity.execute(SAMPLE);
                identityNanos[i] = System.nanoTime() - t2;
            } else {
                long t2 = System.nanoTime();
                identity.execute(SAMPLE);
                identityNanos[i] = System.nanoTime() - t2;

                long t0 = System.nanoTime();
                empty.execute(SAMPLE);
                emptyNanos[i] = System.nanoTime() - t0;
            }
        }

        double meanEmptyUs = BenchStats.mean(emptyNanos) / 1_000.0;
        double stdEmptyUs = BenchStats.stdDev(emptyNanos, BenchStats.mean(emptyNanos)) / 1_000.0;
        double meanIdentityUs = BenchStats.mean(identityNanos) / 1_000.0;
        double stdIdentityUs = BenchStats.stdDev(identityNanos, BenchStats.mean(identityNanos)) / 1_000.0;
        double ratio = BenchStats.ratio(meanIdentityUs, meanEmptyUs);

        System.out.println("==========================================================================");
        System.out.println("INVOCATION OVERHEAD BASELINE (empty pipeline vs identity stage)");
        System.out.printf("Empty pipeline:    %.2f ± %.2f µs%n", meanEmptyUs, stdEmptyUs);
        System.out.printf("Identity stage:    %.2f ± %.2f µs%n", meanIdentityUs, stdIdentityUs);
        System.out.printf("Relative overhead: %.1fx (gate: < %.0fx)%n",
            ratio, BenchStats.MAX_RELATIVE_OVERHEAD);
        System.out.println("Absolute times are informational. The relative bound is the CI gate.");
        System.out.println("==========================================================================");

        assertThat(ratio)
            .as("identity-stage mean should stay within a generous multiple of the empty pipeline")
            .isLessThan(BenchStats.MAX_RELATIVE_OVERHEAD);
        assertThat(identity.execute(SAMPLE)).isEqualTo(SAMPLE);
    }
}
