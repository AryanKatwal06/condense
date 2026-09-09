package com.condense.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SessionReaderRegistryTest {

    @Test
    @DisplayName("Registry contains readers for all defined formats")
    void containsAllFormats() {
        for (AgentTranscriptFormat format : AgentTranscriptFormat.values()) {
            Optional<SessionReader> reader = SessionReaderRegistry.find(format);
            assertThat(reader).isPresent();
            assertThat(reader.get().format()).isEqualTo(format);
        }
    }

    @Test
    @DisplayName("Resolves readers by case-insensitive identifier")
    void resolvesById() {
        assertThat(SessionReaderRegistry.findById("claude")).isPresent();
        assertThat(SessionReaderRegistry.findById("CLAUDE")).isPresent();
        assertThat(SessionReaderRegistry.findById("cursor")).isPresent();
        assertThat(SessionReaderRegistry.findById("CURSOR")).isPresent();
        assertThat(SessionReaderRegistry.findById("windsurf")).isPresent();
        assertThat(SessionReaderRegistry.findById("WINDSURF")).isPresent();
        assertThat(SessionReaderRegistry.findById("unknown_agent")).isEmpty();
    }

    @Test
    @DisplayName("all() returns exact registered readers list")
    void allReturnsReaders() {
        List<SessionReader> all = SessionReaderRegistry.all();
        assertThat(all).hasSize(3);
        assertThat(all).extracting(SessionReader::format)
            .containsExactlyInAnyOrder(
                AgentTranscriptFormat.CLAUDE_CODE,
                AgentTranscriptFormat.CURSOR,
                AgentTranscriptFormat.WINDSURF
            );
    }
}
