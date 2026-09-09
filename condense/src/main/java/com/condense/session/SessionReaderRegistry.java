package com.condense.session;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Closed, static registry of local agent session transcript readers.
 * Uses zero reflection or runtime classloading to guarantee native image compatibility.
 */
public final class SessionReaderRegistry {

    private static final Map<AgentTranscriptFormat, SessionReader> READERS;
    private static final List<SessionReader> ALL_READERS;

    static {
        Map<AgentTranscriptFormat, SessionReader> map = new EnumMap<>(AgentTranscriptFormat.class);
        register(map, new ClaudeCodeSessionReader());
        register(map, new CursorSessionReader());
        register(map, new WindsurfSessionReader());
        READERS = Collections.unmodifiableMap(map);
        ALL_READERS = List.copyOf(READERS.values());
    }

    private SessionReaderRegistry() {}

    private static void register(Map<AgentTranscriptFormat, SessionReader> map, SessionReader reader) {
        map.put(reader.format(), reader);
    }

    /**
     * Finds the reader for the specified agent transcript format.
     */
    public static Optional<SessionReader> find(AgentTranscriptFormat format) {
        if (format == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(READERS.get(format));
    }

    /**
     * Resolves a reader by agent identifier (e.g. "claude", "cursor", "windsurf").
     */
    public static Optional<SessionReader> findById(String id) {
        return AgentTranscriptFormat.fromId(id).flatMap(SessionReaderRegistry::find);
    }

    /**
     * Returns an unmodifiable list of all registered session readers.
     */
    public static List<SessionReader> all() {
        return ALL_READERS;
    }
}
