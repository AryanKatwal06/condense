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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Static session reader for Claude Code JSONL transcripts.
 */
public final class ClaudeCodeSessionReader extends AbstractSessionReader {

    private static final Logger log = Logger.getLogger(ClaudeCodeSessionReader.class);

    @Override
    public AgentTranscriptFormat format() {
        return AgentTranscriptFormat.CLAUDE_CODE;
    }

    @Override
    public SessionRecord parseSession(Path sessionFile, long maxBytes) throws IOException {
        String content = readBoundedContent(sessionFile, maxBytes);
        String sessionId = extractSessionId(sessionFile);
        String workspaceRoot = null;
        Instant startTime = null;
        Instant endTime = null;

        List<SessionRecord.SessionEvent> events = new ArrayList<>();
        Map<String, PendingToolCall> pendingCalls = new HashMap<>();

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
                    Instant lineTime = parseTimestamp(node);
                    if (lineTime != null) {
                        if (startTime == null || lineTime.isBefore(startTime)) {
                            startTime = lineTime;
                        }
                        if (endTime == null || lineTime.isAfter(endTime)) {
                            endTime = lineTime;
                        }
                    }

                    if (workspaceRoot == null && node.has("cwd")) {
                        workspaceRoot = node.get("cwd").asText(null);
                    }

                    // Mode 1: Direct flat command event
                    if (node.has("command")) {
                        String rawCmd = node.get("command").asText("");
                        if (!rawCmd.isBlank()) {
                            int exitCode = node.has("exitCode") ? node.get("exitCode").asInt(0)
                                : (node.has("exit_code") ? node.get("exit_code").asInt(0) : 0);
                            String output = node.has("output") ? node.get("output").asText("") : "";
                            long duration = node.has("durationMillis") ? node.get("durationMillis").asLong(0) : 0L;
                            long outputBytes = node.has("outputBytes") ? node.get("outputBytes").asLong(output.length()) : output.length();
                            boolean filtered = node.has("filtered") && node.get("filtered").asBoolean(false);

                            events.add(new SessionRecord.SessionEvent(
                                "evt-" + lineNum,
                                lineTime != null ? lineTime : Instant.now(),
                                SecretRedactor.redact(rawCmd),
                                exitCode,
                                duration,
                                outputBytes,
                                SecretRedactor.redact(output),
                                filtered
                            ));
                            continue;
                        }
                    }

                    // Mode 2: Claude Code tool_use and tool_result events
                    parseToolUse(node, lineTime, pendingCalls);
                    parseToolResult(node, lineTime, lineNum, pendingCalls, events);

                } catch (Exception parseEx) {
                    log.debugf("Skipping malformed line %d in %s: %s", lineNum, sessionFile, parseEx.getMessage());
                }
            }
        }

        return new SessionRecord(
            sessionId,
            AgentTranscriptFormat.CLAUDE_CODE,
            sessionFile,
            startTime != null ? startTime : Instant.now(),
            endTime != null ? endTime : Instant.now(),
            workspaceRoot,
            events
        );
    }

    private void parseToolUse(JsonNode node, Instant timestamp, Map<String, PendingToolCall> pendingCalls) {
        // Direct tool_use
        if ("tool_use".equals(node.path("type").asText())) {
            recordPending(node, timestamp, pendingCalls);
            return;
        }

        // Nested in message.content array
        JsonNode content = node.path("message").path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                if ("tool_use".equals(item.path("type").asText())) {
                    recordPending(item, timestamp, pendingCalls);
                }
            }
        }
    }

    private void recordPending(JsonNode toolNode, Instant timestamp, Map<String, PendingToolCall> pendingCalls) {
        String toolName = toolNode.path("name").asText();
        if (isShellTool(toolName)) {
            String callId = toolNode.path("id").asText();
            String command = toolNode.path("input").path("command").asText();
            if (!callId.isBlank() && !command.isBlank()) {
                pendingCalls.put(callId, new PendingToolCall(callId, command, timestamp));
            }
        }
    }

    private void parseToolResult(
        JsonNode node,
        Instant timestamp,
        int lineNum,
        Map<String, PendingToolCall> pendingCalls,
        List<SessionRecord.SessionEvent> events
    ) {
        if ("tool_result".equals(node.path("type").asText())) {
            handleResult(node, timestamp, lineNum, pendingCalls, events);
            return;
        }

        JsonNode content = node.path("message").path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                if ("tool_result".equals(item.path("type").asText())) {
                    handleResult(item, timestamp, lineNum, pendingCalls, events);
                }
            }
        }
    }

    private void handleResult(
        JsonNode resultNode,
        Instant timestamp,
        int lineNum,
        Map<String, PendingToolCall> pendingCalls,
        List<SessionRecord.SessionEvent> events
    ) {
        String callId = resultNode.path("tool_use_id").asText();
        PendingToolCall pending = pendingCalls.remove(callId);
        if (pending == null) {
            return;
        }

        boolean isError = resultNode.path("is_error").asBoolean(false);
        int exitCode = isError ? 1 : 0;
        if (resultNode.has("exit_code")) {
            exitCode = resultNode.get("exit_code").asInt(exitCode);
        }

        String rawOutput = resultNode.path("content").asText("");
        long duration = 0L;
        if (pending.timestamp != null && timestamp != null && !timestamp.isBefore(pending.timestamp)) {
            duration = java.time.Duration.between(pending.timestamp, timestamp).toMillis();
        }

        String redactedCmd = SecretRedactor.redact(pending.command);
        String redactedOutput = SecretRedactor.redact(rawOutput);
        boolean isFiltered = redactedCmd.startsWith("condense ") || rawOutput.contains("[condensed]");

        events.add(new SessionRecord.SessionEvent(
            pending.callId.isBlank() ? "evt-" + lineNum : pending.callId,
            pending.timestamp != null ? pending.timestamp : (timestamp != null ? timestamp : Instant.now()),
            redactedCmd,
            exitCode,
            duration,
            rawOutput.length(),
            redactedOutput,
            isFiltered
        ));
    }

    private boolean isShellTool(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.equals("bash") || lower.equals("sh") || lower.equals("terminal") || lower.equals("exec");
    }

    private String extractSessionId(Path sessionFile) {
        String filename = sessionFile.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private Instant parseTimestamp(JsonNode node) {
        if (node.has("timestamp")) {
            try {
                return Instant.parse(node.get("timestamp").asText());
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private record PendingToolCall(String callId, String command, Instant timestamp) {}
}
