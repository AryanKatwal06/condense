package com.condense.persist;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Filesystem seam for durable writes. Production is {@link #SYSTEM}.
 * Tests inject faults. Not a {@code FileSystemProvider} and not a CDI bean.
 */
public interface DurableIo {

    DurableIo SYSTEM = new SystemDurableIo();

    void createDirectories(Path dir) throws IOException;

    Path createTempFile(Path dir, String prefix, String suffix) throws IOException;

    OutputStream newOutputStream(Path file, OpenOption... options) throws IOException;

    void move(Path source, Path target, CopyOption... options) throws IOException;

    boolean deleteIfExists(Path file) throws IOException;

    boolean exists(Path file, LinkOption... options);

    /**
     * Best-effort fsync. Unsupported filesystems are ignored by {@link AtomicFile}.
     */
    void force(Path file) throws IOException;
}

final class SystemDurableIo implements DurableIo {

    @Override
    public void createDirectories(Path dir) throws IOException {
        Files.createDirectories(dir);
    }

    @Override
    public Path createTempFile(Path dir, String prefix, String suffix) throws IOException {
        return Files.createTempFile(dir, prefix, suffix);
    }

    @Override
    public OutputStream newOutputStream(Path file, OpenOption... options) throws IOException {
        return Files.newOutputStream(file, options);
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        Files.move(source, target, options);
    }

    @Override
    public boolean deleteIfExists(Path file) throws IOException {
        return Files.deleteIfExists(file);
    }

    @Override
    public boolean exists(Path file, LinkOption... options) {
        return Files.exists(file, options);
    }

    @Override
    public void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }
}
