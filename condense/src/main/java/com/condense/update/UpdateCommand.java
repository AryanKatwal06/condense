package com.condense.update;

import picocli.CommandLine.Command;
import com.condense.VersionProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "update", description = "Update Condense to the latest version")
public class UpdateCommand implements Callable<Integer> {

    private static final String REPO_OWNER = "aryanKatwal06";
    private static final String REPO_NAME = "code-condenser";
    private static final String LATEST_RELEASE_URL = "https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME + "/releases/latest";

    @Override
    public Integer call() throws Exception {
        System.out.println("Checking for updates...");

        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(LATEST_RELEASE_URL)).header("Accept", "application/vnd.github.v3+json").build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() != 200) {
            System.err.println("Failed to check for updates. HTTP " + res.statusCode());
            return 1;
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode releaseNode = mapper.readTree(res.body());
        String latestVersionStr = releaseNode.get("tag_name").asText();
        String latestVersion = latestVersionStr.startsWith("v") ? latestVersionStr.substring(1) : latestVersionStr;

        VersionProvider vp = new VersionProvider();
        String currentVersionStr = vp.getVersion()[0];
        String currentVersion = currentVersionStr.startsWith("v") ? currentVersionStr.substring(1) : currentVersionStr;

        if (latestVersion.equals(currentVersion)) {
            System.out.println("Condense is up to date (version " + currentVersion + ").");
            return 0;
        }

        System.out.println("New version found: " + latestVersion + " (current: " + currentVersion + ")");

        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        String targetOs = os.contains("win") ? "windows" : (os.contains("mac") ? "macos" : "linux");
        String targetArch = arch.contains("aarch64") || arch.contains("arm64") ? "aarch64" : "x64";
        String binaryName = "condense-" + targetOs + "-" + targetArch;
        if (targetOs.equals("windows")) {
            binaryName += ".exe";
        }

        String downloadUrl = null;
        String checksumUrl = null;
        for (JsonNode asset : releaseNode.get("assets")) {
            String name = asset.get("name").asText();
            if (name.equals(binaryName)) {
                downloadUrl = asset.get("browser_download_url").asText();
            } else if (name.equals("checksums.txt")) {
                checksumUrl = asset.get("browser_download_url").asText();
            }
        }

        if (downloadUrl == null) {
            System.err.println("Could not find binary for your platform (" + binaryName + ") in release " + latestVersionStr);
            return 1;
        }

        System.out.println("Downloading " + binaryName + "...");
        Path tempBinary = Files.createTempFile("condense-", ".tmp");
        HttpRequest downloadReq = HttpRequest.newBuilder().uri(URI.create(downloadUrl)).build();
        client.send(downloadReq, HttpResponse.BodyHandlers.ofFile(tempBinary));

        if (checksumUrl != null) {
            System.out.println("Verifying checksum...");
            HttpRequest checksumReq = HttpRequest.newBuilder().uri(URI.create(checksumUrl)).build();
            HttpResponse<String> checksumRes = client.send(checksumReq, HttpResponse.BodyHandlers.ofString());
            String expectedHash = null;
            for (String line : checksumRes.body().split("\n")) {
                if (line.contains(binaryName)) {
                    expectedHash = line.split("\\s+")[0].trim().toLowerCase();
                    break;
                }
            }

            if (expectedHash != null) {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (InputStream is = Files.newInputStream(tempBinary)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        md.update(buffer, 0, read);
                    }
                }
                byte[] digest = md.digest();
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) {
                    sb.append(String.format("%02x", b));
                }
                String actualHash = sb.toString();

                if (!expectedHash.equals(actualHash)) {
                    System.err.println("Checksum verification failed!");
                    System.err.println("Expected: " + expectedHash);
                    System.err.println("Actual:   " + actualHash);
                    Files.deleteIfExists(tempBinary);
                    return 1;
                }
                System.out.println("Checksum verified.");
            } else {
                System.err.println("Warning: Could not find checksum for " + binaryName + " in checksums.txt");
            }
        }

        // Determine current executable
        String execPathStr = ProcessHandle.current().info().command().orElse(null);
        if (execPathStr == null || execPathStr.contains("java")) {
            // Running via JVM (mvn), not a native image.
            System.err.println("Cannot self-update when running via JVM or unknown executable path.");
            Files.deleteIfExists(tempBinary);
            return 1;
        }
        
        Path currentExe = Path.of(execPathStr);
        System.out.println("Replacing " + currentExe + "...");
        
        try {
            if (targetOs.equals("windows")) {
                Path oldExe = currentExe.resolveSibling(currentExe.getFileName() + ".old");
                Files.deleteIfExists(oldExe);
                Files.move(currentExe, oldExe, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tempBinary, currentExe, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(tempBinary, currentExe, StandardCopyOption.REPLACE_EXISTING);
                // Make executable
                Set<PosixFilePermission> perms = new HashSet<>(Files.getPosixFilePermissions(currentExe));
                perms.add(PosixFilePermission.OWNER_EXECUTE);
                perms.add(PosixFilePermission.GROUP_EXECUTE);
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(currentExe, perms);
            }
            System.out.println("Successfully updated to " + latestVersionStr + "!");
        } catch (Exception e) {
            System.err.println("Failed to replace binary: " + e.getMessage());
            return 1;
        }

        return 0;
    }
}
