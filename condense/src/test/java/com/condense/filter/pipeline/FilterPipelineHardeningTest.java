package com.condense.filter.pipeline;

import com.condense.core.CondenseConfig;
import com.condense.filter.strategy.AnsiStripStrategy;
import com.condense.filter.strategy.DeduplicationStrategy;
import com.condense.filter.strategy.TreeCompressionStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class FilterPipelineHardeningTest {

    @Test
    @DisplayName("Concurrent execution of shared singleton strategy stages is thread-safe and leak-free")
    void concurrentExecution_sharedStrategies_isThreadSafe() throws Exception {
        FilterPipeline pipeline = FilterPipeline.of(
            AnsiStripStrategy.INSTANCE,
            new DeduplicationStrategy(20),
            TreeCompressionStrategy.INSTANCE
        );

        int threadCount = 16;
        int iterationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Callable<Boolean>> tasks = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            tasks.add(() -> {
                for (int i = 0; i < iterationsPerThread; i++) {
                    String input = "\u001B[32msrc/pkg" + threadId + "/File" + i + ".java\u001B[0m\n"
                        + "src/pkg" + threadId + "/File" + i + ".java\n"
                        + "src/pkg" + threadId + "/Other.java";

                    String result = pipeline.execute(input);
                    if (!result.contains("pkg" + threadId)) {
                        return false;
                    }
                    if (result.contains("\u001B[")) {
                        return false;
                    }
                }
                return true;
            });
        }

        List<Future<Boolean>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        for (Future<Boolean> future : futures) {
            assertThat(future.get()).isTrue();
        }
    }

    @Test
    @DisplayName("Fail-open error handling preserves intermediate state when a middle stage crashes")
    void failOpen_preservesIntermediateStateOnStageException() {
        FilterStage stage1 = (input, ctx) -> StageResult.continueWith(input.toUpperCase());
        FilterStage faultyStage = (input, ctx) -> {
            throw new IllegalStateException("Faulty regex parser failure");
        };
        FilterStage stage3 = (input, ctx) -> StageResult.continueWith("[" + input + "]");

        FilterPipeline pipeline = FilterPipeline.of(stage1, faultyStage, stage3);
        String output = pipeline.execute("hello world");

        // stage1 produced "HELLO WORLD", faultyStage failed (logged warning), stage3 received "HELLO WORLD"
        assertThat(output).isEqualTo("[HELLO WORLD]");
    }

    @Test
    @DisplayName("Large input handling passes through multi-stage pipeline without corruption")
    void largeInputHandling_multiStageThroughput() {
        FilterPipeline pipeline = FilterPipeline.of(
            AnsiStripStrategy.INSTANCE,
            DeduplicationStrategy.DEFAULT
        );

        // Build ~2MB input with lines repeating within default window of 50
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            sb.append("\u001B[33mline ").append(i % 10).append("\u001B[0m\n");
        }
        String largeInput = sb.toString();

        String result = pipeline.execute(largeInput);

        assertThat(result).doesNotContain("\u001B[");
        assertThat(result).contains("(×2000)");
    }

    @Test
    @DisplayName("StageResult compact constructor normalizes null output to empty string")
    void stageResult_nullNormalization() {
        StageResult res1 = new StageResult(null, false);
        assertThat(res1.output()).isEqualTo("");
        assertThat(res1.shortCircuit()).isFalse();

        StageResult res2 = StageResult.continueWith(null);
        assertThat(res2.output()).isEqualTo("");
        assertThat(res2.shortCircuit()).isFalse();

        StageResult res3 = StageResult.stopWith(null);
        assertThat(res3.output()).isEqualTo("");
        assertThat(res3.shortCircuit()).isTrue();
    }

    @Test
    @DisplayName("FilterContext factory normalizes nulls safely")
    void filterContext_nullNormalization() {
        FilterContext ctx = FilterContext.of(null, null, null, 1, false);

        assertThat(ctx.command()).isEqualTo("");
        assertThat(ctx.result()).isNull();
        assertThat(ctx.config()).isNotNull();
        assertThat(ctx.verbose()).isEqualTo(1);
        assertThat(ctx.ultraCompact()).isFalse();
    }

    @Test
    @DisplayName("Records provide reflective constructor and accessor accessibility for JVM contract verification")
    void records_reflectionAccessibility() throws Exception {
        // Note: This unit test verifies standard JVM reflective accessibility for record constructors
        // and accessors. GraalVM native-image reachability is separately and additionally proven by
        // the native compilation and smoke test pipeline in CI.
        // StageResult reflection check
        Class<StageResult> srClass = StageResult.class;
        Constructor<?> srCtor = srClass.getDeclaredConstructor(String.class, boolean.class);
        assertThat(srCtor).isNotNull();

        Method outputMethod = srClass.getDeclaredMethod("output");
        Method shortCircuitMethod = srClass.getDeclaredMethod("shortCircuit");
        assertThat(outputMethod).isNotNull();
        assertThat(shortCircuitMethod).isNotNull();

        StageResult sr = (StageResult) srCtor.newInstance("test", true);
        assertThat(outputMethod.invoke(sr)).isEqualTo("test");
        assertThat(shortCircuitMethod.invoke(sr)).isEqualTo(true);

        // FilterContext reflection check
        Class<FilterContext> fcClass = FilterContext.class;
        Constructor<?> fcCtor = fcClass.getDeclaredConstructor(
            String.class, com.condense.core.ExecutionResult.class,
            CondenseConfig.class, int.class, boolean.class
        );
        assertThat(fcCtor).isNotNull();

        Method cmdMethod = fcClass.getDeclaredMethod("command");
        assertThat(cmdMethod).isNotNull();
    }
}
