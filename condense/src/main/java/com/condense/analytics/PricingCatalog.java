package com.condense.analytics;

import com.condense.core.Mappers;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * In-memory catalog of LLM pricing data loaded from the versioned classpath resource
 * {@code /pricing/models.json}.
 *
 * <p>Fail-open: if the resource cannot be loaded or parsed, a safe fallback catalog
 * is instantiated so Condense operations never crash.
 */
@RegisterForReflection
public final class PricingCatalog {

    private static final Logger log = Logger.getLogger(PricingCatalog.class);
    private static final String RESOURCE_PATH = "/pricing/models.json";

    private static volatile PricingCatalog instance;

    private final int schemaVersion;
    private final String effectiveDate;
    private final String defaultModelId;
    private final List<ModelPricing> models;

    public PricingCatalog(
        int schemaVersion,
        String effectiveDate,
        String defaultModelId,
        List<ModelPricing> models
    ) {
        this.schemaVersion = schemaVersion;
        this.effectiveDate = effectiveDate != null ? effectiveDate : "";
        this.defaultModelId = defaultModelId != null ? defaultModelId : "claude-3-5-sonnet-20241022";
        this.models = models != null ? List.copyOf(models) : List.of();
    }

    public static PricingCatalog get() {
        PricingCatalog current = instance;
        if (current == null) {
            synchronized (PricingCatalog.class) {
                current = instance;
                if (current == null) {
                    current = load();
                    instance = current;
                }
            }
        }
        return current;
    }

    /**
     * Resets singleton cache (primarily for testing).
     */
    public static synchronized void reset() {
        instance = null;
    }

    public static PricingCatalog load() {
        try (InputStream in = PricingCatalog.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                log.warnf("Pricing catalog resource '%s' not found on classpath; using fallback", RESOURCE_PATH);
                return fallback();
            }
            PricingCatalogWire wire = Mappers.JSON.readValue(in, PricingCatalogWire.class);
            return new PricingCatalog(
                wire.schemaVersion(),
                wire.effectiveDate(),
                wire.defaultModel(),
                wire.models()
            );
        } catch (Exception e) {
            log.warnf(e, "Failed to load pricing catalog from '%s': %s; using fallback", RESOURCE_PATH, e.getMessage());
            return fallback();
        }
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public String effectiveDate() {
        return effectiveDate;
    }

    public String defaultModelId() {
        return defaultModelId;
    }

    public List<ModelPricing> allModels() {
        return models;
    }

    public Optional<ModelPricing> findModel(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        for (ModelPricing model : models) {
            if (model.matches(query)) {
                return Optional.of(model);
            }
        }
        return Optional.empty();
    }

    public ModelPricing defaultModel() {
        return findModel(defaultModelId)
            .orElseGet(() -> models.isEmpty() ? fallbackModel() : models.getFirst());
    }

    private static ModelPricing fallbackModel() {
        return new ModelPricing(
            "claude-3-5-sonnet-20241022",
            List.of("claude-3-5-sonnet", "sonnet-3-5", "claude-sonnet", "sonnet"),
            "Claude 3.5 Sonnet",
            "Anthropic",
            3.00,
            15.00,
            0.30,
            3.75,
            "2024-10-22",
            "https://www.anthropic.com/pricing"
        );
    }

    private static PricingCatalog fallback() {
        ModelPricing sonnet = fallbackModel();
        return new PricingCatalog(
            1,
            "2026-09-01",
            sonnet.id(),
            List.of(sonnet)
        );
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PricingCatalogWire(
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("effective_date") String effectiveDate,
        @JsonProperty("default_model") String defaultModel,
        @JsonProperty("models") List<ModelPricing> models
    ) {}
}
