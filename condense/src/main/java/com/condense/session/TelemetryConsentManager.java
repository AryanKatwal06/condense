package com.condense.session;

import com.condense.core.Mappers;
import com.condense.core.PlatformDirs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Manages persistent opt-in consent and local rate limits for failure reporting.
 * Default is STRICTLY OPT-OUT (disabled). Network transmission is fundamentally blocked
 * unless explicit consent has been granted.
 */
public final class TelemetryConsentManager {

    public static final int MAX_DAILY_REPORTS = 10;
    private final Path consentFile;

    public TelemetryConsentManager() {
        this(new PlatformDirs().getConfigDir().resolve("telemetry-consent.json"));
    }

    public TelemetryConsentManager(Path consentFile) {
        this.consentFile = consentFile;
    }

    public record ConsentState(
        boolean optedIn,
        String consentTimestamp,
        int dailyCount,
        String dateIso
    ) {}

    public synchronized boolean hasConsent() {
        ConsentState state = readState();
        return state.optedIn();
    }

    public synchronized void grantConsent() {
        ConsentState current = readState();
        ConsentState updated = new ConsentState(
            true,
            java.time.Instant.now().toString(),
            current.dailyCount(),
            current.dateIso()
        );
        writeState(updated);
    }

    public synchronized void revokeConsent() {
        ConsentState updated = new ConsentState(
            false,
            null,
            0,
            LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        );
        writeState(updated);
    }

    public synchronized boolean isRateLimited() {
        ConsentState state = readState();
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        if (!today.equals(state.dateIso())) {
            return false;
        }
        return state.dailyCount() >= MAX_DAILY_REPORTS;
    }

    public synchronized void recordReportSent() {
        ConsentState state = readState();
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        int count = today.equals(state.dateIso()) ? state.dailyCount() + 1 : 1;
        ConsentState updated = new ConsentState(
            state.optedIn(),
            state.consentTimestamp(),
            count,
            today
        );
        writeState(updated);
    }

    public synchronized void purge() {
        try {
            Files.deleteIfExists(consentFile);
        } catch (IOException e) {
            // Ignore
        }
    }

    public synchronized ConsentState getState() {
        return readState();
    }

    private ConsentState readState() {
        if (!Files.exists(consentFile)) {
            return new ConsentState(false, null, 0, LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        }
        try {
            byte[] bytes = Files.readAllBytes(consentFile);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = Mappers.JSON.readValue(bytes, Map.class);
            boolean optedIn = Boolean.TRUE.equals(map.get("opted_in"));
            String consentTimestamp = (String) map.get("consent_timestamp");
            Number dailyCountNum = (Number) map.get("daily_count");
            int dailyCount = dailyCountNum != null ? dailyCountNum.intValue() : 0;
            String dateIso = (String) map.get("date_iso");
            return new ConsentState(optedIn, consentTimestamp, dailyCount, dateIso);
        } catch (Exception e) {
            return new ConsentState(false, null, 0, LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        }
    }

    private void writeState(ConsentState state) {
        try {
            if (consentFile.getParent() != null) {
                Files.createDirectories(consentFile.getParent());
            }
            Map<String, Object> map = Map.of(
                "opted_in", state.optedIn(),
                "consent_timestamp", state.consentTimestamp() != null ? state.consentTimestamp() : "",
                "daily_count", state.dailyCount(),
                "date_iso", state.dateIso() != null ? state.dateIso() : ""
            );
            byte[] bytes = Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(map);
            Files.write(consentFile, bytes);
        } catch (IOException e) {
            // Fail open
        }
    }
}
