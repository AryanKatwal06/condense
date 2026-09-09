package com.condense.session;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Pluggable, static session reader for local AI coding assistant transcripts.
 */
public interface SessionReader {

    /**
     * The agent transcript format supported by this reader.
     */
    AgentTranscriptFormat format();

    /**
     * Discovers session files under the specified base directory, filtered by max age and capped by max count.
     *
     * @param baseDir root directory to search for transcripts
     * @param maxAgeDays maximum age in days of transcript files to consider
     * @param maxFiles maximum number of session files to return
     * @return list of session file paths ordered by last modified time (newest first)
     * @throws IOException if directory scanning fails
     */
    List<Path> discoverSessionFiles(Path baseDir, int maxAgeDays, int maxFiles) throws IOException;

    /**
     * Parses an individual session file into a structured {@link SessionRecord}.
     *
     * @param sessionFile path to the session transcript file
     * @param maxBytes maximum bytes to read from the session file to avoid unbounded memory allocation
     * @return structured session record, or an empty session record if corrupt/unreadable
     * @throws IOException if file reading fails
     */
    SessionRecord parseSession(Path sessionFile, long maxBytes) throws IOException;
}
