package com.condense.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Package-visible process and capture seam. Production uses {@link #SYSTEM}.
 * Tests wrap child streams. Not a CDI bean and not loaded by name.
 */
interface ProcessIo {

    ProcessIo SYSTEM = new SystemProcessIo();

    Process start(ProcessBuilder builder) throws IOException;

    Path createCaptureFile() throws IOException;

    default InputStream stdoutOf(Process process) {
        return process.getInputStream();
    }

    default InputStream stderrOf(Process process) {
        return process.getErrorStream();
    }
}

final class SystemProcessIo implements ProcessIo {

    @Override
    public Process start(ProcessBuilder builder) throws IOException {
        return builder.start();
    }

    @Override
    public Path createCaptureFile() throws IOException {
        Path tempFile = Files.createTempFile("condense-stream-", ".log");
        tempFile.toFile().deleteOnExit();
        return tempFile;
    }
}
