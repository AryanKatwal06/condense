package com.condense.read;

import com.condense.core.SafePathValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Path, size, and binary checks for {@code condense read}. Fail-closed.
 */
public final class ReadPathGate {

    public static final int DEFAULT_MAX_BYTES = 1_048_576;
    public static final int HARD_MAX_BYTES = 10_485_760;
    public static final int BINARY_PROBE_BYTES = 8192;

    public record GateResult(boolean ok, String error, Path canonicalFile, Path containedBy, byte[] bytes) {
        public static GateResult fail(String error) {
            return new GateResult(false, error, null, null, null);
        }

        public static GateResult ok(Path canonicalFile, Path containedBy, byte[] bytes) {
            return new GateResult(true, null, canonicalFile, containedBy, bytes);
        }
    }

    /**
     * Workspace root plus optional narrow-only override. Does not open files
     * or apply {@code condense read}'s byte-max / binary checks.
     */
    public record NarrowRoot(boolean ok, String error, Path root) {
        public static NarrowRoot fail(String error) {
            return new NarrowRoot(false, error, null);
        }

        public static NarrowRoot ok(Path root) {
            return new NarrowRoot(true, null, root);
        }
    }

    private ReadPathGate() {}

    public static int clampMaxBytes(Integer requested) {
        int value = requested == null || requested <= 0 ? DEFAULT_MAX_BYTES : requested;
        return Math.min(value, HARD_MAX_BYTES);
    }

    public static GateResult openStdin(byte[] bytes, int maxBytes) {
        byte[] body = bytes == null ? new byte[0] : bytes;
        int cap = clampMaxBytes(maxBytes);
        if (body.length > cap) {
            return GateResult.fail("input exceeds " + cap + " byte cap");
        }
        if (isBinary(body)) {
            return GateResult.fail("input looks binary (NUL in the first " + BINARY_PROBE_BYTES + " bytes)");
        }
        return GateResult.ok(null, null, body);
    }

    public static NarrowRoot resolveNarrowRoot(Path cwd, Path rootOverride) {
        Path work = cwd == null ? Path.of(System.getProperty("user.dir", ".")) : cwd;
        Path defaultRoot = SafePathValidator.resolveWorkspaceRoot(work);
        if (rootOverride == null) {
            return NarrowRoot.ok(defaultRoot);
        }
        Path requested = rootOverride.toAbsolutePath().normalize();
        if (!Files.isDirectory(requested)) {
            return NarrowRoot.fail("root is not a directory");
        }
        if (!SafePathValidator.isAtOrUnder(requested, defaultRoot)) {
            return NarrowRoot.fail("root may only narrow the workspace, not widen it");
        }
        return NarrowRoot.ok(requested);
    }

    public static GateResult openFile(Path file, Path cwd, Path rootOverride, int maxBytes) {
        if (file == null) {
            return GateResult.fail("missing file path");
        }
        NarrowRoot narrowed = resolveNarrowRoot(cwd, rootOverride);
        if (!narrowed.ok()) {
            return GateResult.fail(narrowed.error());
        }
        Path root = narrowed.root();
        SafePathValidator.ContainmentResult contained = SafePathValidator.containReadable(file, root);
        if (!contained.contained()) {
            return GateResult.fail(contained.reason());
        }
        Path real = contained.realFile();
        int cap = clampMaxBytes(maxBytes);
        try {
            long size = Files.size(real);
            if (size > cap) {
                return GateResult.fail("file exceeds " + cap + " byte cap");
            }
            byte[] body = Files.readAllBytes(real);
            if (isBinary(body)) {
                return GateResult.fail("file looks binary (NUL in the first " + BINARY_PROBE_BYTES + " bytes)");
            }
            return GateResult.ok(real, root.toAbsolutePath().normalize(), body);
        } catch (IOException e) {
            return GateResult.fail("cannot read file: " + e.getMessage());
        }
    }

    static boolean isBinary(byte[] bytes) {
        if (bytes == null) {
            return false;
        }
        int limit = Math.min(bytes.length, BINARY_PROBE_BYTES);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
