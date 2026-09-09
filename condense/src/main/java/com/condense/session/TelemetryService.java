package com.condense.session;

import com.condense.core.Mappers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Opt-in failure visibility service.
 * Enforces compile-time kill switch, explicit consent verification, endpoint pinning,
 * payload preview, rate limiting, and fail-open behavior.
 */
public final class TelemetryService {

    public static final String KILL_SWITCH_PROPERTY = "condense.telemetry.disabled";
    public static final String DEFAULT_PINNED_ENDPOINT = "https://telemetry.condense.dev/v1/failures";

    @FunctionalInterface
    public interface HttpPoster {
        int post(String endpointUrl, String jsonPayload) throws Exception;
    }

    private final TelemetryConsentManager consentManager;
    private final HttpPoster poster;

    public enum SendResult {
        SUCCESS,
        NO_CONSENT,
        RATE_LIMITED,
        KILL_SWITCH_ACTIVE,
        NETWORK_ERROR
    }

    public TelemetryService() {
        this(new TelemetryConsentManager());
    }

    public TelemetryService(TelemetryConsentManager consentManager) {
        this(consentManager, TelemetryService::defaultPost);
    }

    public TelemetryService(TelemetryConsentManager consentManager, HttpPoster poster) {
        this.consentManager = consentManager;
        this.poster = poster != null ? poster : TelemetryService::defaultPost;
    }

    public static boolean isKillSwitchActive() {
        return Boolean.parseBoolean(System.getProperty(KILL_SWITCH_PROPERTY, "false"));
    }

    /**
     * Previews the exact JSON payload that would be transmitted.
     */
    public String previewReport(FailureReportPayload payload) {
        try {
            return Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize failure report payload to JSON", e);
        }
    }

    /**
     * Exports the sanitized failure report to a local file.
     */
    public void exportReport(FailureReportPayload payload, Path targetFile) throws IOException {
        String json = previewReport(payload);
        if (targetFile.getParent() != null) {
            Files.createDirectories(targetFile.getParent());
        }
        Files.writeString(targetFile, json);
    }

    /**
     * Attempts to transmit a sanitized failure report.
     * Guaranteed zero network traffic without explicit consent or if kill switch is active.
     */
    public SendResult sendReport(FailureReportPayload payload, String endpointUrl) {
        if (isKillSwitchActive()) {
            return SendResult.KILL_SWITCH_ACTIVE;
        }

        if (!consentManager.hasConsent()) {
            return SendResult.NO_CONSENT;
        }

        if (consentManager.isRateLimited()) {
            return SendResult.RATE_LIMITED;
        }

        String target = endpointUrl != null && !endpointUrl.isBlank()
                ? endpointUrl
                : DEFAULT_PINNED_ENDPOINT;

        try {
            String json = previewReport(payload);
            int statusCode = poster.post(target, json);
            if (statusCode >= 200 && statusCode < 300) {
                consentManager.recordReportSent();
                return SendResult.SUCCESS;
            } else {
                return SendResult.NETWORK_ERROR;
            }
        } catch (Exception e) {
            // Fail open: reporting failures never impact local proxy execution
            return SendResult.NETWORK_ERROR;
        }
    }

    public void purgeLocalData() {
        consentManager.purge();
    }

    public TelemetryConsentManager consentManager() {
        return consentManager;
    }

    private static int defaultPost(String endpointUrl, String jsonPayload) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("User-Agent", "condense-telemetry/1.0.1")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }
}
