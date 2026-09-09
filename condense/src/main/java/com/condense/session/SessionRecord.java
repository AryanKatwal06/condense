package com.condense.session;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable representation of a single agent session and its chronological command executions.
 */
public record SessionRecord(
    String sessionId,
    AgentTranscriptFormat agentFormat,
    Path transcriptPath,
    Instant startTime,
    Instant endTime,
    String workspaceRoot,
    List<SessionEvent> events
) {
    public SessionRecord {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(agentFormat, "agentFormat must not be null");
        events = events == null ? List.of() : Collections.unmodifiableList(events);
    }

    public int commandCount() {
        return events.size();
    }

    public long totalRawOutputBytes() {
        long total = 0;
        for (SessionEvent event : events) {
            total += event.rawOutputBytes();
        }
        return total;
    }

    /**
     * An individual command execution event within an agent session.
     */
    public record SessionEvent(
        String eventId,
        Instant timestamp,
        String command,
        int exitCode,
        long durationMillis,
        long rawOutputBytes,
        String outputSnippet,
        boolean filtered
    ) {
        public SessionEvent {
            Objects.requireNonNull(command, "command must not be null");
            outputSnippet = outputSnippet == null ? "" : outputSnippet;
        }

        public boolean failed() {
            return exitCode != 0;
        }

        public boolean succeeded() {
            return exitCode == 0;
        }
    }
}
