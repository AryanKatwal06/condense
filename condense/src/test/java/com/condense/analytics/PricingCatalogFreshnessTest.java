package com.condense.analytics;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PricingCatalogFreshnessTest {

    private static PricingCatalog catalog;

    @BeforeAll
    static void setUp() {
        catalog = PricingCatalog.get();
    }

    @Test
    void catalogMetadataIsValid() {
        assertThat(catalog.schemaVersion()).isGreaterThanOrEqualTo(1);
        assertThat(catalog.effectiveDate()).isNotBlank();
        assertValidIsoDate(catalog.effectiveDate());
        assertThat(catalog.defaultModelId()).isNotBlank();
        assertThat(catalog.findModel(catalog.defaultModelId())).isPresent();
    }

    @Test
    void allModelsHaveValidPricingAndHttpsSources() {
        List<ModelPricing> models = catalog.allModels();
        assertThat(models).hasSizeGreaterThanOrEqualTo(10);

        for (ModelPricing m : models) {
            assertThat(m.id()).as("model id for %s", m.id()).isNotBlank();
            assertThat(m.name()).as("model name for %s", m.id()).isNotBlank();
            assertThat(m.provider()).as("provider for %s", m.id()).isNotBlank();
            assertThat(m.inputCostPerMillion())
                .as("input rate for %s", m.id())
                .isGreaterThan(0.0);
            assertThat(m.outputCostPerMillion())
                .as("output rate for %s", m.id())
                .isGreaterThan(0.0);
            assertThat(m.effectiveDate()).as("effective date for %s", m.id()).isNotBlank();
            assertValidIsoDate(m.effectiveDate());

            assertThat(m.sourceUrl()).as("source url for %s", m.id()).startsWith("https://");
            URI uri = URI.create(m.sourceUrl());
            assertThat(uri.getScheme()).isEqualTo("https");
            assertThat(uri.getHost()).isNotBlank();
        }
    }

    @Test
    void noDuplicateAliasesAcrossModels() {
        Set<String> seenAliases = new HashSet<>();
        for (ModelPricing m : catalog.allModels()) {
            assertThat(seenAliases.add(m.id().toLowerCase()))
                .as("model id %s should be globally unique", m.id())
                .isTrue();
            for (String alias : m.aliases()) {
                assertThat(seenAliases.add(alias.toLowerCase()))
                    .as("alias '%s' from model %s collided with another model or alias", alias, m.id())
                    .isTrue();
            }
        }
    }

    @Test
    void defaultModelIsPresentAndHasSensibleRates() {
        ModelPricing def = catalog.defaultModel();
        assertThat(def.id()).isEqualTo("claude-3-5-sonnet-20241022");
        assertThat(def.provider()).isEqualTo("Anthropic");
        assertThat(def.inputCostPerMillion()).isEqualTo(3.00);
        assertThat(def.outputCostPerMillion()).isEqualTo(15.00);
    }

    @Test
    void effectiveDatesAreSensible() {
        // Assert dates are after 2024-01-01 and before 2030-01-01
        LocalDate minDate = LocalDate.of(2024, 1, 1);
        LocalDate maxDate = LocalDate.of(2030, 1, 1);

        for (ModelPricing m : catalog.allModels()) {
            LocalDate d = LocalDate.parse(m.effectiveDate(), DateTimeFormatter.ISO_LOCAL_DATE);
            assertThat(d)
                .as("model %s effective date %s should be within reasonable bounds", m.id(), d)
                .isAfter(minDate)
                .isBefore(maxDate);
        }
    }

    private static void assertValidIsoDate(String dateStr) {
        try {
            LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw new AssertionError("Invalid ISO date format: " + dateStr, e);
        }
    }
}
