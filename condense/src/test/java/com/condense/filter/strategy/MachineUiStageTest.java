package com.condense.filter.strategy;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterIncident;
import com.condense.filter.pipeline.StageResult;
import com.condense.ir.Document;
import com.condense.ir.TextRenderer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MachineUiStageTest {

    @Test
    void planJsonKeepsCreateUpdateDeleteReplaceAndDrift() {
        FilterContext context = FilterContext.empty();
        StageResult result = MachineUiStage.INSTANCE.process(fixture("plan-json.ndjson"), context);

        assertThat(result.shortCircuit()).isTrue();
        assertThat(result.output()).contains("aws_instance.web create");
        assertThat(result.output()).contains("aws_security_group.web update");
        assertThat(result.output()).contains("aws_eip.old delete");
        assertThat(result.output()).contains("aws_instance.api replace (cannot_update)");
        assertThat(result.output()).contains("aws_s3_bucket.logs update");
        assertThat(result.output()).contains("Missing required argument");
        assertThat(result.output()).doesNotContain("random_pet.unused");
        assertThat(result.output()).doesNotContain("ID       IMAGE");
        assertThat(context.documentBuilder().isPopulated()).isTrue();
        Document built = context.documentBuilder().build(
            "terraform plan", "terraform", 1, true, null);
        assertThat(built.kind()).isEqualTo(Document.DocumentKind.RESOURCE);
        assertThat(TextRenderer.render(built)).isEqualTo(result.output());
    }

    @Test
    void applyJsonOmitsSensitiveOutputValues() {
        StageResult result = MachineUiStage.INSTANCE.process(fixture("apply-json.ndjson"), FilterContext.empty());
        assertThat(result.shortCircuit()).isTrue();
        assertThat(result.output()).contains("random_pet.animal create");
        assertThat(result.output()).contains("pets output");
        assertThat(result.output()).contains("secret output (sensitive)");
        assertThat(result.output()).doesNotContain("should-not-appear");
        assertThat(result.output()).doesNotContain("redacted-name");
    }

    @Test
    void tofuPlanJsonIsAccepted() {
        StageResult result = MachineUiStage.INSTANCE.process(tofuFixture(), FilterContext.empty());
        assertThat(result.shortCircuit()).isTrue();
        assertThat(result.output()).contains("aws_instance.web create");
        assertThat(result.output()).contains("Plan: 1 to add, 0 to change, 0 to destroy");
    }

    @Test
    void humanTextFallsThrough() {
        String raw = fixtureFrom("typical.txt");
        StageResult result = MachineUiStage.INSTANCE.process(raw, FilterContext.empty());
        assertThat(result.shortCircuit()).isFalse();
        assertThat(result.output()).isEqualTo(raw);
    }

    @Test
    void uiMajorTwoFallsThroughAndRecordsIncident() {
        FilterContext context = FilterContext.empty();
        String raw = fixture("ui2.ndjson");
        StageResult result = MachineUiStage.INSTANCE.process(raw, context);
        assertThat(result.shortCircuit()).isFalse();
        assertThat(result.output()).isEqualTo(raw);
        assertThat(context.incidents())
            .extracting(FilterIncident::kind)
            .contains(FilterIncident.KIND_MACHINE_UI_VERSION);
    }

    @Test
    void malformedLineKeepsPriorEvents() {
        StageResult result = MachineUiStage.INSTANCE.process(fixture("malformed.ndjson"), FilterContext.empty());
        assertThat(result.shortCircuit()).isTrue();
        assertThat(result.output()).contains("aws_instance.web create");
        assertThat(result.output()).contains("Plan: 1 to add, 0 to change, 0 to destroy");
    }

    @Test
    void truncatedLineKeepsPriorEvents() {
        StageResult result = MachineUiStage.INSTANCE.process(fixture("truncated.ndjson"), FilterContext.empty());
        assertThat(result.shortCircuit()).isTrue();
        assertThat(result.output()).contains("aws_instance.web create");
    }

    @Test
    void objectCapKeepsSummaryAndAddresses() {
        StringBuilder ndjson = new StringBuilder();
        ndjson.append("{\"type\":\"version\",\"ui\":\"1.0\",\"@module\":\"terraform.ui\"}\n");
        ndjson.append("{\"type\":\"planned_change\",\"@module\":\"terraform.ui\",\"change\":{\"resource\":{\"addr\":\"aws_instance.web\",\"resource_type\":\"aws_instance\"},\"action\":\"create\"}}\n");
        ndjson.append("{\"type\":\"change_summary\",\"@module\":\"terraform.ui\",\"changes\":{\"add\":1,\"change\":0,\"remove\":0}}\n");
        for (int i = 0; i < MachineUiStage.MAX_JSON_OBJECTS; i++) {
            ndjson.append("{\"type\":\"refresh_start\",\"@module\":\"terraform.ui\",\"hook\":{\"resource\":{\"addr\":\"mod.n")
                .append(i).append("\"}}}\n");
        }
        StageResult result = MachineUiStage.INSTANCE.process(ndjson.toString(), FilterContext.empty());
        assertThat(result.shortCircuit()).isTrue();
        assertThat(result.output()).contains("aws_instance.web create");
        assertThat(result.output()).contains("Plan: 1 to add, 0 to change, 0 to destroy");
        assertThat(result.output()).contains(MachineUiStage.CAPPED_LINE);
    }

    private static String fixture(String name) {
        return read("/fixtures/terraform/" + name);
    }

    private static String fixtureFrom(String name) {
        return read("/fixtures/terraform/" + name);
    }

    private static String tofuFixture() {
        return read("/fixtures/tofu/plan-json.ndjson");
    }

    private static String read(String resource) {
        try (var in = MachineUiStageTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(resource, e);
        }
    }
}
