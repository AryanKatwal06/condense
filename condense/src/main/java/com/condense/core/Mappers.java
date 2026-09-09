package com.condense.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

public final class Mappers {
    private Mappers() {}

    public static final ObjectMapper JSON = new ObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    public static final TomlMapper TOML = new TomlMapper();
}

