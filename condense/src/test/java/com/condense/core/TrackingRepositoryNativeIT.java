package com.condense.core;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

@QuarkusIntegrationTest
public class TrackingRepositoryNativeIT {

    @Test
    void nativeBinary_persistsAnalyticsToSqlite() throws Exception {
        String nativeImagePath = System.getProperty("native.image.path");
        if (nativeImagePath == null || nativeImagePath.isBlank()) {
            fail("native.image.path system property was not supplied — this test requires " +
                 "the native binary to be built first (e.g. `mvn verify -Pnative`). " +
                 "It cannot validate BUG-002 (SQLite persistence in the native image) without it, " +
                 "so it must fail rather than silently pass.");
        }

        File binary = new File(nativeImagePath);
        if (!binary.exists()) {
            File exeBinary = new File(nativeImagePath + ".exe");
            if (exeBinary.exists()) {
                binary = exeBinary;
            } else {
                fail("native.image.path was set to '" + nativeImagePath + "' but no binary exists " +
                     "at that path (checked both with and without a .exe suffix). The native build " +
                     "must have failed or not run before this test executed.");
            }
        }
        if (!binary.canExecute()) {
            fail("Native binary at '" + binary.getAbsolutePath() + "' exists but is not executable. " +
                 "Check file permissions from the native-image build step.");
        }

        PlatformDirs dirs = new PlatformDirs();
        Path dbPath = dirs.getDatabaseFile();

        long countBefore = 0;
        if (Files.exists(dbPath)) {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM commands")) {
                if (rs.next()) {
                    countBefore = rs.getLong(1);
                }
            } catch (Exception e) {
                fail("Pre-existing database at '" + dbPath + "' could not be read: " + e.getMessage() +
                     ". This baseline read must succeed for the before/after comparison to be meaningful.", e);
            }
        }

        Process process = new ProcessBuilder(binary.getAbsolutePath(), "echo", "hello_regression_test")
            .redirectErrorStream(false)
            .start();

        StringBuilder stderr = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stderr.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        assertThat(exitCode).isEqualTo(0);
        assertThat(stderr.toString()).doesNotContain("No suitable driver found");

        assertThat(Files.exists(dbPath)).isTrue();

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM commands")) {
            assertThat(rs.next()).isTrue();
            long countAfter = rs.getLong(1);
            assertThat(countAfter).isGreaterThan(countBefore);
        }
    }
}
