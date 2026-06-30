package com.condense;

import picocli.CommandLine.IVersionProvider;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Reads the application version from {@code /com/condense/version.properties}
 * bundled in the JAR/native image. Never hardcodes the version string.
 */
public class VersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/com/condense/version.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // Fall through to default
        }
        String version = props.getProperty("version", "unknown");
        String imageCode = System.getProperty("org.graalvm.nativeimage.imagecode");
        if (imageCode != null) {
            return new String[]{
                "condense " + version,
                "Built with GraalVM Native Image"
            };
        } else {
            return new String[]{
                "condense " + version,
                "Running on JVM (Java " + System.getProperty("java.version") + ")"
            };
        }
    }
}
