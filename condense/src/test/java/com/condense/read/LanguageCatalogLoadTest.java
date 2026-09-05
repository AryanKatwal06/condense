package com.condense.read;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LanguageCatalogLoadTest {

    @Test
    void catalogLoadsEveryIndexedLanguage() {
        LanguageDefinitionCatalog catalog = LanguageDefinitionCatalog.standalone();
        assertThat(catalog.names()).contains(
            "java", "javascript", "python", "go", "json", "rust");
        assertThat(catalog.detect("App.java")).isNotNull().extracting(CompiledLanguage::name)
            .isEqualTo("java");
        assertThat(catalog.detect("package.json")).isNotNull().extracting(CompiledLanguage::name)
            .isEqualTo("json");
        assertThat(catalog.detect("mystery.unknown")).isNull();
        assertThat(catalog.required("java").family()).isEqualTo(LanguageFamily.C_LIKE);
    }

    @Test
    void unknownLanguageNameFails() {
        assertThatThrownBy(() -> LanguageDefinitionCatalog.standalone().required("not-a-lang"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown language");
    }

    @Test
    void validatorPassesInlineTests() {
        List<String> errors = LanguageDefinitionValidator.validate();
        assertThat(errors).isEmpty();
    }

    @Test
    void dataFamilyRejectsCommentFields() {
        List<String> errors = new ArrayList<>();
        LanguageDefinitionCatalog.load(errors);
        assertThat(errors).isEmpty();
        assertThat(LanguageDefinitionCatalog.standalone().required("json").family())
            .isEqualTo(LanguageFamily.DATA);
        assertThat(LanguageDefinitionCatalog.standalone().required("json").definition().lineComment())
            .isNull();
    }
}
