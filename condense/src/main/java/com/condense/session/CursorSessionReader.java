package com.condense.session;

import com.condense.core.Mappers;
import com.fasterxml.jackson.databind.JsonNode;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Static session reader for Cursor transcripts and terminal execution logs.
 */
public final class CursorSessionReader extends AbstractSessionReader {

    private static final Logger log = Logger.getLogger(CursorSessionReader.class);

    @Override
    public AgentTranscriptFormat format() {
        return AgentTranscriptFormat.CURSOR;
    }

    @Override
    public SessionRecord parseSession(Path sessionFile, long maxBytes) throws IOException {
        String content = readBoundedContent(sessionFile, maxBytes);
        String sessionId = extractSessionId(sessionFile);
        String workspaceRoot = null;
        Instant startTime = null;
        Instant endTime = null;

        List<SessionRecord.SessionEvent> events = new ArrayList<>();

        try {
            JsonNode root = Mappers.JSON.readTree(content);
            if (root.isObject()) {
                if (root.has("sessionId")) {
                    sessionId = root.get("sessionId").asText(sessionId);
                }
                if (root.has("workspace")) {
                    workspaceRoot = root.get("workspace").asText(null);
                }

                JsonNode cmdList = root.path("commands");
                if (cmdList.isArray()) {
                    for (int i = 0; i < cmdList.size(); i++) {
                        JsonNode item = cmdList.get(i);
                        SessionRecord.SessionEvent evt = parseCommandNode(item, "evt-" + (i + 1));
                        if (evt != null) {
                            events.add(evt);
                            if (startTime == null || evt.timestamp().isBefore(startTime)) {
                                startTime = evt.timestamp();
                            }
                            if (endTime == null || evt.timestamp().isAfter(endTime)) {
                                endTime = evt.timestamp();
                            }
                        }
                    }
                }
            } else if (root.isArray()) {
                for (int i = 0; i < root.size(); i++) {
                    JsonNode item = root.get(i);
                    SessionRecord.SessionEvent evt = parseCommandNode(item, "evt-" + (i + 1));
                    if (evt != null) {
                        events.add(evt);
                        if (startTime == null || evt.timestamp().isBefore(startTime)) {
                            startTime = evt.timestamp();
                        }
                        if (endTime == null || evt.timestamp().isAfter(endTime)) {
                            endTime = evt.timestamp();
                        }
                    }
                }
            }
        } catch (Exception parseEx) {
            // Fallback: try parsing as line-delimited JSONL
            try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
                String line;
                int lineNum = 0;
                while ((line = reader.readLine()) != null) {
                    lineNum++;
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    try {
                        JsonNode node = Mappers.JSON.readTree(line);
                        SessionRecord.SessionEvent evt = parseCommandNode(node, "evt-" + lineNum);
                        if (evt != null) {
                            events.add(evt);
                            if (startTime == null || evt.timestamp().isBefore(startTime)) {
                                startTime = evt.timestamp();
                            }
                            if (endTime == null || evt.timestamp().isAfter(endTime)) {
                                endTime = evt.timestamp();
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        return new SessionRecord(
            sessionId,
            AgentTranscriptFormat.CURSOR,
            sessionFile,
            startTime != null ? startTime : Instant.now(),
            endTime != null ? endTime : Instant.now(),
            workspaceRoot,
            events
        );
    }

    private SessionRecord.SessionEvent parseCommandNode(JsonNode node, String defaultId) {
        String cmd = null;
        if (node.has("command")) {
            cmd = node.get("command").asText("");
        } else if (node.has("cmd")) {
            cmd = node.get("cmd").asText("");
        } else if (node.has("terminalCommand")) {
            cmd = node.get("terminalCommand").asText("");
        }

        if (cmd == null || cmd.isBlank()) {
            return null;
        }

        int exitCode = node.has("exitCode") ? node.get("exitCode").asInt(0)
            : (node.has("exit_code") ? node.get("exit_code").asInt(0) : 0);
        String output = node.has("output") ? node.get("output").asText("")
            : (node.has("result") ? node.get("result").asText("") : "");
        long duration = node.has("durationMillis") ? node.get("durationMillis").asLong(0) : 0L;
        long outputBytes = node.has("outputBytes") ? node.get("outputBytes").asLong(output.length()) : output.length();
        boolean filtered = node.has("filtered") && node.get("filtered").asBoolean(false);

        Instant time = Instant.now();
        if (node.has("timestamp")) {
            try {
                time = Instant.parse(node.get("timestamp").asText());
            } catch (Exception ignored) {}
        }

        String redactedCmd = SecretRedactor.redact(cmd);
        String redactedOutput = SecretRedactor.redact(output);
        if (redactedCmd.startsWith("condense ")) {
            filtered = true;
        }

        return new SessionRecord.SessionEvent(
            node.has("id") ? node.get("id").asText(defaultId) : defaultId,
            time,
            redactedCmd,
            exitCode,
            duration,
            outputBytes,
            redactedOutput,
            filtered
        );
    }

    private String extractSessionId(Path sessionFile) {
        String filename = sessionFile.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
