package com.condense.filter.pipeline.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltinDefinitionValidationTest {

    @Test
    void shippedDefinitionsPassTheBuildTimeValidator() {
        List<String> errors = BuiltinDefinitionValidator.validate();
        assertThat(errors).isEmpty();
    }
}
