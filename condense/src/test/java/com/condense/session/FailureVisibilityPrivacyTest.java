package com.condense.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FailureVisibilityPrivacyTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Guarantees zero network traffic without explicit opt-in consent")
    void zeroNetworkTrafficWithoutConsent() {
        Path consentFile = tempDir.resolve("telemetry-consent.json");
        TelemetryConsentManager consentManager = new TelemetryConsentManager(consentFile);

        AtomicInteger networkCalls = new AtomicInteger();
        TelemetryService service = new TelemetryService(consentManager, (endpoint, json) -> {
            networkCalls.incrementAndGet();
            return 200;
        });

        FailureReportPayload payload = new FailureReportPayload(
            "1.0.1", "Linux", "amd64", "PROXY_EXECUTION", "COMPILATION_ERROR", 1, 1200L, 5000L
        );

        TelemetryService.SendResult result = service.sendReport(payload, "https://telemetry.example.com");

        assertThat(result).isEqualTo(TelemetryService.SendResult.NO_CONSENT);
        assertThat(networkCalls.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("Sanitized payload contains zero raw command lines, file paths, or secrets")
    void payloadSanitizationAndBucketing() {
        FailureReportPayload payload = new FailureReportPayload(
            "1.0.1", "Linux", "x86_64", "STAGE_EXECUTION", "TEST_FAILURE", 1, 3500L, 45000L
        );

        TelemetryService service = new TelemetryService();
        String json = service.previewReport(payload);

        // Verify buckets
        assertThat(json).contains("\"duration_bucket\" : \"1s-5s\"");
        assertThat(json).contains("\"output_length_bucket\" : \"10KB-100KB\"");
        assertThat(json).contains("\"schema_version\" : 1");
        assertThat(json).contains("\"error_category\" : \"TEST_FAILURE\"");

        // Strict negative assertions: zero personal or execution context
        assertThat(json).doesNotContain("command");
        assertThat(json).doesNotContain("path");
        assertThat(json).doesNotContain("workspace");
        assertThat(json).doesNotContain("output_snippet");
        assertThat(json).doesNotContain("user");
        assertThat(json).doesNotContain("password");
        assertThat(json).doesNotContain("token");
    }

    @Test
    @DisplayName("Enforces persistent opt-in and opt-out consent lifecycle")
    void consentLifecycleManagement() {
        Path consentFile = tempDir.resolve("telemetry-consent.json");
        TelemetryConsentManager manager = new TelemetryConsentManager(consentFile);

        assertThat(manager.hasConsent()).isFalse();

        manager.grantConsent();
        assertThat(manager.hasConsent()).isTrue();
        assertThat(Files.exists(consentFile)).isTrue();

        // Reload from disk to verify persistence
        TelemetryConsentManager reloaded = new TelemetryConsentManager(consentFile);
        assertThat(reloaded.hasConsent()).isTrue();

        // Revoke consent
        reloaded.revokeConsent();
        assertThat(reloaded.hasConsent()).isFalse();

        // Re-read to ensure disk reflects revocation
        TelemetryConsentManager reloadedAgain = new TelemetryConsentManager(consentFile);
        assertThat(reloadedAgain.hasConsent()).isFalse();
    }

    @Test
    @DisplayName("Compile-time kill switch prevents any transmission regardless of consent")
    void compileTimeKillSwitch() {
        Path consentFile = tempDir.resolve("telemetry-consent.json");
        TelemetryConsentManager manager = new TelemetryConsentManager(consentFile);
        manager.grantConsent();

        AtomicInteger networkCalls = new AtomicInteger();
        TelemetryService service = new TelemetryService(manager, (endpoint, json) -> {
            networkCalls.incrementAndGet();
            return 200;
        });

        System.setProperty(TelemetryService.KILL_SWITCH_PROPERTY, "true");
        try {
            FailureReportPayload payload = new FailureReportPayload(
                "1.0.1", "macOS", "aarch64", "HOOK_DISPATCH", "TIMEOUT", 124, 60000L, 100L
            );

            TelemetryService.SendResult result = service.sendReport(payload, "https://telemetry.example.com");
            assertThat(result).isEqualTo(TelemetryService.SendResult.KILL_SWITCH_ACTIVE);
            assertThat(networkCalls.get()).isEqualTo(0);
        } finally {
            System.clearProperty(TelemetryService.KILL_SWITCH_PROPERTY);
        }
    }

    @Test
    @DisplayName("Enforces daily rate limiting to prevent reporting floods")
    void dailyRateLimiting() {
        Path consentFile = tempDir.resolve("telemetry-consent.json");
        TelemetryConsentManager manager = new TelemetryConsentManager(consentFile);
        manager.grantConsent();

        AtomicInteger networkCalls = new AtomicInteger();
        TelemetryService service = new TelemetryService(manager, (endpoint, json) -> {
            networkCalls.incrementAndGet();
            return 200;
        });

        FailureReportPayload payload = new FailureReportPayload(
            "1.0.1", "Windows", "x86_64", "FILTER_PIPELINE", "GENERAL_FAILURE", 1, 50L, 500L
        );

        // Send up to the max daily quota (10)
        for (int i = 0; i < TelemetryConsentManager.MAX_DAILY_REPORTS; i++) {
            TelemetryService.SendResult res = service.sendReport(payload, "https://telemetry.example.com");
            assertThat(res).isEqualTo(TelemetryService.SendResult.SUCCESS);
        }

        assertThat(networkCalls.get()).isEqualTo(10);
        assertThat(manager.isRateLimited()).isTrue();

        // 11th send must be blocked by rate limit without network contact
        TelemetryService.SendResult rateLimitedResult = service.sendReport(payload, "https://telemetry.example.com");
        assertThat(rateLimitedResult).isEqualTo(TelemetryService.SendResult.RATE_LIMITED);
        assertThat(networkCalls.get()).isEqualTo(10); // Still 10, no 11th call
    }

    @Test
    @DisplayName("Purge removes local telemetry state completely")
    void purgeRemovesLocalData() {
        Path consentFile = tempDir.resolve("telemetry-consent.json");
        TelemetryConsentManager manager = new TelemetryConsentManager(consentFile);
        manager.grantConsent();
        assertThat(Files.exists(consentFile)).isTrue();

        manager.purge();
        assertThat(Files.exists(consentFile)).isFalse();
        assertThat(manager.hasConsent()).isFalse();
    }

    @Test
    @DisplayName("ReportCommand CLI provides preview, consent management, export, and purge")
    void reportCommandCliWorkflow() throws Exception {
        Path consentFile = tempDir.resolve("cli-consent.json");
        TelemetryConsentManager manager = new TelemetryConsentManager(consentFile);
        AtomicReference<String> transmittedJson = new AtomicReference<>();
        TelemetryService service = new TelemetryService(manager, (endpoint, json) -> {
            transmittedJson.set(json);
            return 200;
        });

        // 1. Preview
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ReportCommand cmd = new ReportCommand(service, new PrintStream(baos, true, StandardCharsets.UTF_8));
        cmd.preview = true;
        assertThat(cmd.call()).isEqualTo(0);
        assertThat(baos.toString(StandardCharsets.UTF_8)).contains("\"schema_version\" : 1");

        // 2. Opt-in
        baos.reset();
        cmd = new ReportCommand(service, new PrintStream(baos, true, StandardCharsets.UTF_8));
        cmd.optIn = true;
        assertThat(cmd.call()).isEqualTo(0);
        assertThat(manager.hasConsent()).isTrue();

        // 3. Export
        Path exportFile = tempDir.resolve("exported-failure.json");
        baos.reset();
        cmd = new ReportCommand(service, new PrintStream(baos, true, StandardCharsets.UTF_8));
        cmd.exportPath = exportFile;
        assertThat(cmd.call()).isEqualTo(0);
        assertThat(Files.exists(exportFile)).isTrue();
        assertThat(Files.readString(exportFile)).contains("\"duration_bucket\"");

        // 4. Send after opt-in
        baos.reset();
        cmd = new ReportCommand(service, new PrintStream(baos, true, StandardCharsets.UTF_8));
        cmd.send = true;
        assertThat(cmd.call()).isEqualTo(0);
        assertThat(transmittedJson.get()).isNotNull();
        assertThat(transmittedJson.get()).contains("\"schema_version\" : 1");

        // 5. Opt-out
        baos.reset();
        cmd = new ReportCommand(service, new PrintStream(baos, true, StandardCharsets.UTF_8));
        cmd.optOut = true;
        assertThat(cmd.call()).isEqualTo(0);
        assertThat(manager.hasConsent()).isFalse();

        // 6. Purge
        baos.reset();
        cmd = new ReportCommand(service, new PrintStream(baos, true, StandardCharsets.UTF_8));
        cmd.purge = true;
        assertThat(cmd.call()).isEqualTo(0);
        assertThat(Files.exists(consentFile)).isFalse();
    }
}
