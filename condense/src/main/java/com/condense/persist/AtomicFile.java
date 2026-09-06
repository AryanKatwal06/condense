package com.condense.persist;

import com.condense.core.SafePathValidator;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Write-to-temp then rename. Never truncates the destination first.
 * Temp files live in the same directory as the target.
 */
public final class AtomicFile {

    public static final AtomicFile SYSTEM = new AtomicFile(DurableIo.SYSTEM);

    public static final String DEFAULT_PREFIX = ".condense-atomic-";
    public static final String DEFAULT_SUFFIX = ".tmp";

    private final DurableIo io;

    public AtomicFile() {
        this(DurableIo.SYSTEM);
    }

    public AtomicFile(DurableIo io) {
        this.io = io == null ? DurableIo.SYSTEM : io;
    }

    public void write(Path target, Path containedBy, byte[] bytes) throws IOException {
        write(target, containedBy, bytes, DEFAULT_PREFIX, DEFAULT_SUFFIX);
    }

    public void write(Path target, Path containedBy, byte[] bytes, String prefix, String suffix)
            throws IOException {
        byte[] payload = bytes == null ? new byte[0] : bytes;
        write(target, containedBy, out -> out.write(payload), prefix, suffix);
    }

    public void write(
            Path target,
            Path containedBy,
            OutputHandler handler,
            String prefix,
            String suffix
    ) throws IOException {
        if (target == null) {
            throw new IOException("atomic write target is null");
        }
        SafePathValidator.ContainmentResult containment = SafePathValidator.contain(target, containedBy);
        if (!containment.contained()) {
            throw new IOException("Refusing atomic write: " + containment.reason());
        }
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("atomic write target has no parent: " + target);
        }
        io.createDirectories(parent);
        String tmpPrefix = prefix == null || prefix.length() < 3 ? DEFAULT_PREFIX : prefix;
        String tmpSuffix = suffix == null || suffix.isBlank() ? DEFAULT_SUFFIX : suffix;
        Path tmp = io.createTempFile(parent, tmpPrefix, tmpSuffix);
        try {
            try (OutputStream out = io.newOutputStream(
                    tmp,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                if (handler != null) {
                    handler.writeTo(out);
                }
                out.flush();
            }
            try {
                io.force(tmp);
            } catch (IOException ignored) {
                // force is best-effort
            }
            try {
                io.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                io.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                io.deleteIfExists(tmp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    @FunctionalInterface
    public interface OutputHandler {
        void writeTo(OutputStream out) throws IOException;
    }
}
