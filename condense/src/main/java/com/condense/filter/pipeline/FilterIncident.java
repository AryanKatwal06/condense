package com.condense.filter.pipeline;

/**
 * A fail-open event captured during filtering. Persisted only when a stage or
 * {@code apply} falls back; intentional {@code beforePipeline} passthroughs
 * produce no incident.
 */
public record FilterIncident(
    String kind,
    String filterName,
    String stageName,
    boolean fallbackSucceeded,
    String detail
) {
    public static final String KIND_STAGE_EXCEPTION = "stage_exception";
    public static final String KIND_APPLY_FALLBACK = "apply_fallback";
    public static final String KIND_IR_FALLBACK = "ir_fallback";
    public static final int DETAIL_MAX = 500;

    public FilterIncident {
        detail = truncate(detail);
    }

    public static FilterIncident stageException(String stageName, String detail) {
        return new FilterIncident(KIND_STAGE_EXCEPTION, null, stageName, true, detail);
    }

    public static FilterIncident applyFallback(String filterName, String detail) {
        return new FilterIncident(KIND_APPLY_FALLBACK, filterName, null, true, detail);
    }

    public static FilterIncident irFallback(String stageName, String detail) {
        return new FilterIncident(KIND_IR_FALLBACK, null, stageName, true, detail);
    }

    public FilterIncident withFilterName(String name) {
        if (name == null || name.equals(filterName)) {
            return this;
        }
        return new FilterIncident(kind, name, stageName, fallbackSucceeded, detail);
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= DETAIL_MAX ? text : text.substring(0, DETAIL_MAX);
    }
}
