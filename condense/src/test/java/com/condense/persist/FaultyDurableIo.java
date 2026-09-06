package com.condense.persist;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.CopyOption;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;

/**
 * Test double for {@link DurableIo}. Injects disk-full, readonly, rename, and
 * kill-between-write-and-move faults.
 */
public final class FaultyDurableIo implements DurableIo {

    private final DurableIo delegate;
    private final Integer failAfterBytes;
    private final boolean failRename;
    private final boolean skipRename;
    private final boolean readOnly;

    private FaultyDurableIo(
            DurableIo delegate,
            Integer failAfterBytes,
            boolean failRename,
            boolean skipRename,
            boolean readOnly
    ) {
        this.delegate = delegate == null ? DurableIo.SYSTEM : delegate;
        this.failAfterBytes = failAfterBytes;
        this.failRename = failRename;
        this.skipRename = skipRename;
        this.readOnly = readOnly;
    }

    public static FaultyDurableIo diskFullAfter(int bytes) {
        return new FaultyDurableIo(DurableIo.SYSTEM, bytes, false, false, false);
    }

    public static FaultyDurableIo renameFails() {
        return new FaultyDurableIo(DurableIo.SYSTEM, null, true, false, false);
    }

    public static FaultyDurableIo skipRename() {
        return new FaultyDurableIo(DurableIo.SYSTEM, null, false, true, false);
    }

    public static FaultyDurableIo readOnly() {
        return new FaultyDurableIo(DurableIo.SYSTEM, null, false, false, true);
    }

    @Override
    public void createDirectories(Path dir) throws IOException {
        delegate.createDirectories(dir);
    }

    @Override
    public Path createTempFile(Path dir, String prefix, String suffix) throws IOException {
        return delegate.createTempFile(dir, prefix, suffix);
    }

    @Override
    public OutputStream newOutputStream(Path file, OpenOption... options) throws IOException {
        if (readOnly) {
            throw new IOException("readonly");
        }
        OutputStream out = delegate.newOutputStream(file, options);
        if (failAfterBytes == null) {
            return out;
        }
        return new CappedOutputStream(out, failAfterBytes);
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        if (failRename) {
            throw new IOException("rename_fail");
        }
        if (skipRename) {
            return;
        }
        delegate.move(source, target, options);
    }

    @Override
    public boolean deleteIfExists(Path file) throws IOException {
        return delegate.deleteIfExists(file);
    }

    @Override
    public boolean exists(Path file, LinkOption... options) {
        return delegate.exists(file, options);
    }

    @Override
    public void force(Path file) throws IOException {
        delegate.force(file);
    }

    private static final class CappedOutputStream extends FilterOutputStream {
        private final int limit;
        private int written;

        private CappedOutputStream(OutputStream out, int limit) {
            super(out);
            this.limit = Math.max(0, limit);
        }

        @Override
        public void write(int b) throws IOException {
            if (written >= limit) {
                throw new IOException("disk_full");
            }
            out.write(b);
            written++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (b == null) {
                throw new NullPointerException();
            }
            int remaining = limit - written;
            if (remaining <= 0) {
                throw new IOException("disk_full");
            }
            int allowed = Math.min(len, remaining);
            out.write(b, off, allowed);
            written += allowed;
            if (allowed < len) {
                throw new IOException("disk_full");
            }
        }
    }
}
