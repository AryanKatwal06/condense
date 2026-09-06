package com.condense.filter.strategy;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.StageResult;
import com.condense.ir.Document;
import com.condense.ir.TextRenderer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ValidateJsonStageTest {

    @Test
    void validateJsonKeepsFilenameAndSummary() {
        FilterContext context = FilterContext.empty();
        StageResult result = ValidateJsonStage.INSTANCE.process(read("/fixtures/terraform/validate-json.json"), context);
        assertThat(result.shortCircuit()).isTrue();
        assertThat(result.output()).contains("Invalid resource type");
        assertThat(result.output()).contains("main.tf");
        assertThat(result.output()).startsWith("validate:");
        Document built = context.documentBuilder().build("terraform validate", "terraform", 1, true, null);
        assertThat(built.kind()).isEqualTo(Document.DocumentKind.DIAGNOSTIC);
        assertThat(TextRenderer.render(built)).isEqualTo(result.output());
    }

    @Test
    void cleanValidateJsonIsOk() {
        StageResult result = ValidateJsonStage.INSTANCE.process(
            "{\"valid\":true,\"error_count\":0,\"warning_count\":0,\"diagnostics\":[]}",
            FilterContext.empty());
        assertThat(result.shortCircuit()).isTrue();
        assertThat(result.output()).isEqualTo("validate: ok");
    }

    @Test
    void humanTextAndNdjsonFallThrough() {
        String text = read("/fixtures/terraform/validate-text.txt");
        assertThat(ValidateJsonStage.INSTANCE.process(text, FilterContext.empty()).shortCircuit()).isFalse();
        assertThat(ValidateJsonStage.INSTANCE.process(text, FilterContext.empty()).output()).isEqualTo(text);

        String ndjson = read("/fixtures/terraform/plan-json.ndjson");
        assertThat(ValidateJsonStage.INSTANCE.process(ndjson, FilterContext.empty()).shortCircuit()).isFalse();
    }

    @Test
    void oversizedOrDeepJsonFallsThrough() {
        String deep = "{\"valid\":true,\"diagnostics\":" + "[".repeat(10) + "]" + "]".repeat(10) + "}";
        assertThat(ValidateJsonStage.INSTANCE.process(deep, FilterContext.empty()).shortCircuit()).isFalse();
        String huge = "{\"valid\":true,\"pad\":\"" + "x".repeat(ValidateJsonStage.MAX_CHARS) + "\"}";
        assertThat(ValidateJsonStage.INSTANCE.process(huge, FilterContext.empty()).shortCircuit()).isFalse();
    }

    private static String read(String resource) {
        try (var in = ValidateJsonStageTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(resource, e);
        }
    }
}
