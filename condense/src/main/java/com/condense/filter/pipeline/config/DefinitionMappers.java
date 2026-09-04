package com.condense.filter.pipeline.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

/**
 * Dedicated TOML mapper for filter definitions and overrides.
 * Unknown keys are rejected. Do not reuse {@link com.condense.core.Mappers#TOML}
 * ({@code CondenseConfig} still ignores unknown keys).
 */
public final class DefinitionMappers {

    public static final TomlMapper STRICT_TOML;

    static {
        TomlMapper mapper = new TomlMapper();
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        STRICT_TOML = mapper;
    }

    private DefinitionMappers() {}
}
