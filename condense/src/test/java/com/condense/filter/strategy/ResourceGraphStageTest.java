package com.condense.filter.strategy;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.config.FilterOverrideConfig;
import com.condense.ir.Document;
import com.condense.ir.TextRenderer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceGraphStageTest {

    @Test
    void stateListGroupsByTypeAndKeepsAddresses() {
        FilterContext context = FilterContext.empty();
        ResourceGraphStage stage = ResourceGraphStage.ofPreset(
            ResourceGraphStage.KEY_RESOURCE_TYPE, "{lines} resources in {keys} types", 20, 2000, "");
        StageResult result = stage.process(read("/fixtures/terraform/state-list.txt"), context);
        assertThat(result.shortCircuit()).isTrue();
        assertThat(result.output()).contains("aws_instance.web");
        assertThat(result.output()).contains("module.vpc.aws_subnet.private[0]");
        assertThat(result.output()).contains("data.aws_ami.ubuntu");
        assertThat(result.output()).contains("aws_instance: 2");
        assertThat(result.output()).doesNotContain("Plan:");
        Document built = context.documentBuilder().build("terraform state list", "terraform-state", 0, true, null);
        assertThat(built.kind()).isEqualTo(Document.DocumentKind.RESOURCE);
        assertThat(TextRenderer.render(built)).isEqualTo(result.output());
    }

    @Test
    void humanPlanAndFmtFilesFallThrough() {
        ResourceGraphStage stage = ResourceGraphStage.ofPreset(
            ResourceGraphStage.KEY_RESOURCE_TYPE, "", 20, 2000, "");
        String plan = read("/fixtures/terraform/typical.txt");
        assertThat(stage.process(plan, FilterContext.empty()).output()).isEqualTo(plan);
        String fmt = read("/fixtures/terraform/fmt-files.txt");
        assertThat(stage.process(fmt, FilterContext.empty()).output()).isEqualTo(fmt);
    }

    @Test
    void emptyInputUsesFallback() {
        ResourceGraphStage stage = ResourceGraphStage.ofPreset(
            ResourceGraphStage.KEY_RESOURCE_TYPE, "", 20, 2000, "no resources");
        StageResult result = stage.process("", FilterContext.empty());
        assertThat(result.shortCircuit()).isTrue();
        assertThat(result.output()).isEqualTo("no resources");
    }

    @Test
    void validateRejectsUnknownKey() {
        FilterOverrideConfig.StageDef def = new FilterOverrideConfig.StageDef(
            "resource_graph", null, null, null, null, List.of(), java.util.Map.of(),
            10, null, null, null, null, "nope", "", 5, null, null);
        List<String> errors = new ArrayList<>();
        ResourceGraphStage.validate("[stage]", def, errors);
        assertThat(errors).isNotEmpty();
    }

    @Test
    void mainTfIsNotAnAddress() {
        assertThat(ResourceGraphStage.isAddress("main.tf")).isFalse();
        assertThat(ResourceGraphStage.isAddress("aws_instance.web")).isTrue();
        assertThat(ResourceGraphStage.resourceTypeOf("module.vpc.aws_subnet.private[0]"))
            .isEqualTo("aws_subnet");
    }

    private static String read(String resource) {
        try (var in = ResourceGraphStageTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(resource, e);
        }
    }
}
