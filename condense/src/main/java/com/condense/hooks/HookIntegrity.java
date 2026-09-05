package com.condense.hooks;

import com.condense.core.TrackingRepository;
import com.condense.trust.TrustStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * SHA-256 baselines for Condense-owned hook scripts. Not {@code trust.json}.
 */
public final class HookIntegrity {

    public static final String OK = "ok";
    public static final String MISSING = "missing";
    public static final String TAMPERED = "tampered";
    public static final String UNMANAGED = "unmanaged";

    private HookIntegrity() {}

    public static String hashFile(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return TrustStore.sha256Hex(bytes);
    }

    public static String verify(TrackingRepository tracking, HookTool tool, Path script) {
        if (script == null || !Files.exists(script, LinkOption.NOFOLLOW_LINKS)) {
            return MISSING;
        }
        try {
            String content = Files.readString(script);
            if (!HookTemplate.isManagedByCondense(content)) {
                return UNMANAGED;
            }
            String actual = TrustStore.sha256Hex(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (tracking == null) {
                return OK;
            }
            TrackingRepository.HookBaseline baseline = tracking.findHookBaseline(tool.name());
            if (baseline == null) {
                return OK;
            }
            return baseline.sha256().equals(actual) ? OK : TAMPERED;
        } catch (IOException e) {
            return MISSING;
        }
    }

    public static boolean worldWritable(Path script) {
        if (script == null || !Files.exists(script)) {
            return false;
        }
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(script);
            return perms.contains(PosixFilePermission.OTHERS_WRITE);
        } catch (UnsupportedOperationException | IOException ignored) {
            return false;
        }
    }
}
