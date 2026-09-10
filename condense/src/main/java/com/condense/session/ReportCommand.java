package com.condense.session;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code condense report} — Opt-in failure visibility and crash diagnostics.
 */
@Command(
    name = "report",
    description = "Opt-in failure visibility and sanitized crash diagnostics.",
    mixinStandardHelpOptions = true
)
@Dependent
@Unremovable
public class ReportCommand implements Callable<Integer> {

    @Option(names = "--preview", description = "Preview the exact sanitized JSON payload that would be reported.")
    boolean preview;

    @Option(names = "--consent", description = "Display the current telemetry consent and rate limit status.")
    boolean checkConsent;

    @Option(names = "--opt-in", description = "Grant explicit consent to send sanitized failure reports.")
    boolean optIn;

    @Option(names = "--opt-out", description = "Revoke consent and immediately disable all failure reporting.")
    boolean optOut;

    @Option(names = "--export", description = "Export the sanitized failure report to a local file.", paramLabel = "PATH")
    Path exportPath;

    @Option(names = "--analyze", description = "Analyze an offline directory or file of exported failure reports.", paramLabel = "PATH")
    Path analyzePath;

    @Option(names = "--purge", description = "Purge all locally stored telemetry and consent data.")
    boolean purge;

    @Option(names = "--send", description = "Transmit the sanitized failure report (requires explicit prior opt-in).")
    boolean send;

    @Option(names = "--endpoint", description = "Override the default pinned reporting endpoint.", paramLabel = "URL")
    String endpoint;

    private final TelemetryService service;
    private final PrintStream out;

    public ReportCommand() {
        this(new TelemetryService(), System.out);
    }

    public ReportCommand(TelemetryService service, PrintStream out) {
        this.service = service;
        this.out = out != null ? out : System.out;
    }

    @Override
    public Integer call() {
        try {
            if (purge) {
                service.purgeLocalData();
                out.println("Local telemetry state and consent purged successfully.");
                return 0;
            }

            if (optIn) {
                service.consentManager().grantConsent();
                out.println("Explicit consent granted for sanitized failure reporting.");
                out.println("No commands, file paths, repository names, or credentials will ever be sent.");
                return 0;
            }

            if (optOut) {
                service.consentManager().revokeConsent();
                out.println("Failure reporting consent revoked. All telemetry is disabled.");
                return 0;
            }

            if (checkConsent) {
                var state = service.consentManager().getState();
                out.println("=== Condense Failure Reporting Consent ===");
                out.println("Status:          " + (state.optedIn() ? "OPTED-IN (Active)" : "OPTED-OUT (Disabled)"));
                out.println("Consent Date:    " + (state.consentTimestamp() != null ? state.consentTimestamp() : "None"));
                out.println("Daily Count:     " + state.dailyCount() + " / " + TelemetryConsentManager.MAX_DAILY_REPORTS);
                out.println("Kill Switch:     " + (TelemetryService.isKillSwitchActive() ? "ACTIVE (Disabled)" : "Inactive"));
                return 0;
            }

            FailureReportPayload samplePayload = new FailureReportPayload(
                "1.0.1",
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.arch", "unknown"),
                "PROXY_EXECUTION",
                "CHILD_NON_ZERO_EXIT",
                1,
                1500L,
                4096L
            );

            if (preview) {
                out.println(service.previewReport(samplePayload));
                return 0;
            }

            if (analyzePath != null) {
                FailureExportAnalyzer analyzer = new FailureExportAnalyzer();
                FailureExportAnalyzer.AnalysisSummary summary = analyzer.analyze(analyzePath);
                out.println(summary.renderText());
                return 0;
            }

            if (exportPath != null) {
                service.exportReport(samplePayload, exportPath);
                out.println("Exported sanitized failure report to: " + exportPath.toAbsolutePath());
                return 0;
            }

            if (send) {
                TelemetryService.SendResult result = service.sendReport(samplePayload, endpoint);
                switch (result) {
                    case SUCCESS -> {
                        out.println("Sanitized failure report sent successfully.");
                        return 0;
                    }
                    case NO_CONSENT -> {
                        out.println("Cannot send report: User has not opted in. Run 'condense report --opt-in' to grant consent.");
                        return 1;
                    }
                    case RATE_LIMITED -> {
                        out.println("Daily failure reporting rate limit reached (" + TelemetryConsentManager.MAX_DAILY_REPORTS + "/day).");
                        return 1;
                    }
                    case KILL_SWITCH_ACTIVE -> {
                        out.println("Telemetry is globally disabled via compile-time kill switch.");
                        return 1;
                    }
                    case NETWORK_ERROR -> {
                        out.println("Failed to send report due to network error. Fails open (no proxy impact).");
                        return 1;
                    }
                }
            }

            // Default help / status overview
            var state = service.consentManager().getState();
            out.println("=== Condense Failure Reporting ===");
            out.println("Status: " + (state.optedIn() ? "OPTED-IN" : "OPTED-OUT (Default)"));
            out.println("Use '--preview' to inspect the sanitized payload schema.");
            out.println("Use '--opt-in' or '--opt-out' to manage reporting consent.");
            out.println("Use '--export <path>' to export the report to disk.");
            return 0;
        } catch (Exception e) {
            out.println("condense report: error: " + e.getMessage());
            return 1;
        }
    }
}
