package com.condense.analytics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Model pricing metadata defining token rates, effective dates, and audit source URLs.
 * All rates are expressed in USD per 1,000,000 tokens.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelPricing(

    @JsonProperty("id")
    String id,

    @JsonProperty("aliases")
    List<String> aliases,

    @JsonProperty("name")
    String name,

    @JsonProperty("provider")
    String provider,

    @JsonProperty("input_cost_per_million")
    double inputCostPerMillion,

    @JsonProperty("output_cost_per_million")
    double outputCostPerMillion,

    @JsonProperty("cache_read_cost_per_million")
    double cacheReadCostPerMillion,

    @JsonProperty("cache_write_cost_per_million")
    double cacheWriteCostPerMillion,

    @JsonProperty("effective_date")
    String effectiveDate,

    @JsonProperty("source_url")
    String sourceUrl

) {
    public ModelPricing {
        if (aliases == null) {
            aliases = List.of();
        }
    }

    /**
     * Checks if this model matches the provided name, ID, or alias (case-insensitive).
     */
    public boolean matches(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String normalized = query.trim().toLowerCase();
        if (id != null && id.toLowerCase().equals(normalized)) {
            return true;
        }
        if (name != null && name.toLowerCase().equals(normalized)) {
            return true;
        }
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias != null && alias.toLowerCase().equals(normalized)) {
                    return true;
                }
            }
        }
        return false;
    }
}
