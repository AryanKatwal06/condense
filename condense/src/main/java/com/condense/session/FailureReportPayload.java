package com.condense.session;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.UUID;

/**
 * Sanitized, privacy-preserving failure report payload.
 * Strictly guarantees ZERO raw command lines, file paths, repository names, or credentials.
 * Uses bucketed values to prevent statistical re-identification.
 */
@RegisterForReflection
public record FailureReportPayload(
    int schemaVersion,
    String reportId,
    String timestamp,
    String condenseVersion,
    String osFamily,
    String osArch,
    String failureStage,
    String errorCategory,
    int exitCode,
    String durationBucket,
    String outputLengthBucket
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public FailureReportPayload(
            String condenseVersion,
            String osFamily,
            String osArch,
            String failureStage,
            String errorCategory,
            int exitCode,
            long durationMillis,
            long outputBytes) {
        this(
            CURRENT_SCHEMA_VERSION,
            UUID.randomUUID().toString(),
            Instant.now().toString(),
            condenseVersion != null ? condenseVersion : "unknown",
            osFamily != null ? osFamily : System.getProperty("os.name", "unknown"),
            osArch != null ? osArch : System.getProperty("os.arch", "unknown"),
            failureStage != null ? failureStage : "unknown",
            errorCategory != null ? errorCategory : "GENERAL_FAILURE",
            exitCode,
            bucketDuration(durationMillis),
            bucketOutputLength(outputBytes)
        );
    }

    public static String bucketDuration(long durationMillis) {
        if (durationMillis < 100) return "<100ms";
        if (durationMillis < 1000) return "100ms-1s";
        if (durationMillis < 5000) return "1s-5s";
        return ">5s";
    }

    public static String bucketOutputLength(long charOrByteCount) {
        if (charOrByteCount < 1024) return "<1KB";
        if (charOrByteCount < 10240) return "1KB-10KB";
        if (charOrByteCount < 102400) return "10KB-100KB";
        return ">100KB";
    }
}
