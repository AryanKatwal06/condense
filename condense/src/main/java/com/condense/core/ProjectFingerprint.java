package com.condense.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Generates a stable, short fingerprint for the current working directory.
 *
 * <p>The fingerprint is the first 12 hexadecimal characters of the SHA-256
 * hash of the absolute path string. This uniquely identifies a project
 * directory for the {@code condense gain --scope project} analytics filter
 * without storing the actual path in the database.
 */
public final class ProjectFingerprint {

    private ProjectFingerprint() {}

    /**
     * Returns a 12-character hex fingerprint of the given path string.
     *
     * @param absolutePath the absolute working directory path
     * @return 12 lowercase hex characters, e.g. {@code "a3f9d2c1b407"}
     */
    public static String of(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank()) return "000000000000";
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(absolutePath.getBytes(StandardCharsets.UTF_8));
            // Convert first 6 bytes to 12 hex chars
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present in all JVMs
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Returns a fingerprint for the current JVM working directory.
     */
    public static String ofCurrentDir() {
        return of(System.getProperty("user.dir"));
    }
}
