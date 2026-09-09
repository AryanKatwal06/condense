package com.condense.analytics;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PricingCatalogTest {

    @Test
    void catalogLoadsCleanlyFromClasspath() {
        PricingCatalog catalog = PricingCatalog.get();
        assertThat(catalog).isNotNull();
        assertThat(catalog.schemaVersion()).isEqualTo(1);
        assertThat(catalog.effectiveDate()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(catalog.defaultModelId()).isNotEmpty();
        assertThat(catalog.allModels()).hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    void allModelsHaveValidFieldsAndHttpsSources() {
        PricingCatalog catalog = PricingCatalog.get();
        for (ModelPricing model : catalog.allModels()) {
            assertThat(model.id()).as("model id for %s", model.name()).isNotBlank();
            assertThat(model.name()).as("model name for %s", model.id()).isNotBlank();
            assertThat(model.provider()).as("provider for %s", model.id()).isNotBlank();
            assertThat(model.inputCostPerMillion()).as("input rate for %s", model.id()).isPositive();
            assertThat(model.outputCostPerMillion()).as("output rate for %s", model.id()).isPositive();
            assertThat(model.cacheReadCostPerMillion()).as("cache read rate for %s", model.id()).isGreaterThanOrEqualTo(0.0);
            assertThat(model.cacheWriteCostPerMillion()).as("cache write rate for %s", model.id()).isGreaterThanOrEqualTo(0.0);
            assertThat(model.effectiveDate()).as("effective date for %s", model.id()).matches("\\d{4}-\\d{2}-\\d{2}");
            assertThat(model.sourceUrl()).as("source url for %s", model.id()).startsWith("https://");
        }
    }

    @Test
    void aliasesContainNoCollisions() {
        PricingCatalog catalog = PricingCatalog.get();
        Set<String> seenIdentifiers = new HashSet<>();

        for (ModelPricing model : catalog.allModels()) {
            String id = model.id().toLowerCase();
            assertThat(seenIdentifiers.add(id))
                .as("Duplicate model id: %s", id)
                .isTrue();

            for (String alias : model.aliases()) {
                String normalized = alias.toLowerCase();
                assertThat(seenIdentifiers.add(normalized))
                    .as("Alias collision '%s' in model %s", normalized, model.id())
                    .isTrue();
            }
        }
    }

    @Test
    void lookupFindsModelsByIdAndAliasCaseInsensitively() {
        PricingCatalog catalog = PricingCatalog.get();

        Optional<ModelPricing> byId = catalog.findModel("claude-3-5-sonnet-20241022");
        assertThat(byId).isPresent();
        assertThat(byId.get().name()).isEqualTo("Claude 3.5 Sonnet");

        Optional<ModelPricing> byAlias = catalog.findModel("sonnet");
        assertThat(byAlias).isPresent();
        assertThat(byAlias.get().id()).isEqualTo("claude-3-5-sonnet-20241022");

        Optional<ModelPricing> byAliasUpper = catalog.findModel("GPT-4O");
        assertThat(byAliasUpper).isPresent();
        assertThat(byAliasUpper.get().provider()).isEqualTo("OpenAI");

        Optional<ModelPricing> deepseek = catalog.findModel("deepseek-r1");
        assertThat(deepseek).isPresent();
        assertThat(deepseek.get().name()).isEqualTo("DeepSeek-R1");
    }

    @Test
    void defaultModelReturnsValidSonnetEntry() {
        PricingCatalog catalog = PricingCatalog.get();
        ModelPricing defaultModel = catalog.defaultModel();
        assertThat(defaultModel).isNotNull();
        assertThat(defaultModel.id()).isEqualTo("claude-3-5-sonnet-20241022");
        assertThat(defaultModel.inputCostPerMillion()).isEqualTo(3.00);
        assertThat(defaultModel.outputCostPerMillion()).isEqualTo(15.00);
    }

    @Test
    void unknownModelReturnsEmpty() {
        PricingCatalog catalog = PricingCatalog.get();
        assertThat(catalog.findModel("non-existent-ai-model-xyz")).isEmpty();
        assertThat(catalog.findModel("")).isEmpty();
        assertThat(catalog.findModel(null)).isEmpty();
    }
}
