package com.condense.doctor;

import com.condense.core.Mappers;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code condense doctor} — explain why analytics are empty and whether persistence is healthy.
 */
@Command(
    name = "doctor",
    description = "Diagnose persistence, hooks, and empty analytics.",
    mixinStandardHelpOptions = true
)
@Dependent
@Unremovable
public class DoctorCommand implements Callable<Integer> {

    @Option(
        names = "--format",
        description = "Output format: 'text' (default) or 'json'.",
        defaultValue = "text",
        paramLabel = "FORMAT"
    )
    String format;

    @Inject
    DoctorService doctor;

    public DoctorCommand() {}

    public DoctorCommand(DoctorService doctor) {
        this.doctor = doctor;
    }

    @Override
    public Integer call() {
        try {
            DoctorReport report = doctor.diagnose();
            if ("json".equalsIgnoreCase(format)) {
                System.out.println(Mappers.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            } else {
                printText(report);
            }
            return report.ok() ? 0 : 1;
        } catch (Exception e) {
            System.err.println("condense doctor: error: " + e.getMessage());
            return 1;
        }
    }

    private static void printText(DoctorReport report) {
        System.out.println("Condense doctor");
        System.out.println("───────────────");
        System.out.println("Config dir:  " + report.configDir());
        System.out.println("Data dir:    " + report.dataDir());
        System.out.println("Database:    " + report.database());
        System.out.println("Schema:      " + report.schemaVersion() + " (target " + report.targetSchemaVersion() + ")");
        System.out.println("Journal:     " + report.journalMode());
        System.out.println("Commands:    " + report.commandCount());
        System.out.println("Outcomes:    " + report.outcomeCount());
        System.out.println("Tee files:   " + report.teeFiles());
        System.out.println("Hook events: " + report.hookEventCount());
        if (report.hookEvents() != null && !report.hookEvents().isEmpty()) {
            int shown = 0;
            for (DoctorReport.HookEvent event : report.hookEvents()) {
                System.out.println("  - " + event.tool() + " " + event.action()
                    + (event.success() ? "" : " failed"));
                if (++shown >= 20) {
                    break;
                }
            }
        }
        System.out.println("Write loss:  " + report.persistenceWriteFailures()
            + (report.persistenceWriteLastError() == null || report.persistenceWriteLastError().isBlank()
                ? ""
                : " (" + report.persistenceWriteLastError() + ")"));
        if (report.hooks() != null && !report.hooks().isEmpty()) {
            System.out.println("Hooks:");
            for (DoctorReport.HookStatus hook : report.hooks()) {
                System.out.println("  - " + hook.tool() + " "
                    + (hook.installed() ? "installed" : "absent")
                    + (hook.integrity() == null ? "" : " integrity=" + hook.integrity()));
            }
        }
        if (report.emptyTrackingReason() != null) {
            System.out.println("Empty gain:  " + report.emptyTrackingReason());
        } else {
            System.out.println("Empty gain:  (not empty)");
        }
        System.out.println();
        System.out.println(report.nextStep());
        if (report.warnings() != null && !report.warnings().isEmpty()) {
            System.out.println();
            System.out.println("Warnings:");
            for (String warning : report.warnings()) {
                System.out.println("  - " + warning);
            }
        }
    }
}
