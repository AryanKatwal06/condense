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
 * Static session reader for Windsurf Cascade session transcripts.
 */
public final class WindsurfSessionReader extends AbstractSessionReader {

    private static final Logger log = Logger.getLogger(WindsurfSessionReader.class);

    @Override
    public AgentTranscriptFormat format() {
        return AgentTranscriptFormat.WINDSURF;
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
                if (root.has("cascadeId")) {
                    sessionId = root.get("cascadeId").asText(sessionId);
                } else if (root.has("sessionId")) {
                    sessionId = root.get("sessionId").asText(sessionId);
                }
                if (root.has("directory")) {
                    workspaceRoot = root.get("directory").asText(null);
                } else if (root.has("workspace")) {
                    workspaceRoot = root.get("workspace").asText(null);
                }

                JsonNode steps = root.path("steps");
                if (!steps.isArray() && root.has("commands")) {
                    steps = root.path("commands");
                }

                if (steps.isArray()) {
                    for (int i = 0; i < steps.size(); i++) {
                        JsonNode item = steps.get(i);
                        SessionRecord.SessionEvent evt = parseStepNode(item, "step-" + (i + 1));
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
                    SessionRecord.SessionEvent evt = parseStepNode(item, "step-" + (i + 1));
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
            // Fallback line-delimited JSONL parsing
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
                        SessionRecord.SessionEvent evt = parseStepNode(node, "step-" + lineNum);
                        if (evt != null) {
                            events.add(evt);
                            if (startTime == null || evt.timestamp().isBefore(startTime)) {
                                startTime = evt.timestamp();
                            }
                            if (endTime == null || evt.timestamp().isAfter(endTime)) {
                                endTime = evt.timestamp();
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        return new SessionRecord(
            sessionId,
            AgentTranscriptFormat.WINDSURF,
            sessionFile,
            startTime != null ? startTime : Instant.now(),
            endTime != null ? endTime : Instant.now(),
            workspaceRoot,
            events
        );
    }

    private SessionRecord.SessionEvent parseStepNode(JsonNode node, String defaultId) {
        String cmd = null;
        if (node.has("command")) {
            cmd = node.get("command").asText("");
        } else if (node.has("cmd")) {
            cmd = node.get("cmd").asText("");
        }

        if (cmd == null || cmd.isBlank()) {
            return null;
        }

        int exitCode = node.has("exit_code") ? node.get("exit_code").asInt(0)
            : (node.has("exitCode") ? node.get("exitCode").asInt(0) : 0);
        String output = node.has("output") ? node.get("output").asText("") : "";
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
            node.has("stepId") ? node.get("stepId").asText(defaultId) : defaultId,
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
