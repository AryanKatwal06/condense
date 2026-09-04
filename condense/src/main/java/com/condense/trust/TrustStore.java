package com.condense.trust;

import com.condense.core.PlatformDirs;
import com.condense.core.SafePathValidator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Persists trusted project override hashes under {@code {configDir}/trust.json}.
 */
public final class TrustStore {

    public static final String FILE_NAME = "trust.json";
    public static final int SCHEMA_VERSION = 1;

    private static final Logger log = Logger.getLogger(TrustStore.class);

    static final ObjectMapper JSON;
    static {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        JSON = mapper;
    }

    private final PlatformDirs platformDirs;

    public TrustStore(PlatformDirs platformDirs) {
        this.platformDirs = platformDirs;
    }

    public Path storePath() {
        Path configDir = configDir();
        return configDir == null ? null : configDir.resolve(FILE_NAME);
    }

    public Optional<TrustRecord> find(Path canonicalFile) {
        if (canonicalFile == null) {
            return Optional.empty();
        }
        Path wanted = canonicalize(canonicalFile);
        for (TrustRecord record : all()) {
            if (record.path() == null) {
                continue;
            }
            if (pathsMatch(wanted, Path.of(record.path()))) {
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }

    public List<TrustRecord> all() {
        TrustFile file = readFile();
        return file == null ? List.of() : List.copyOf(file.entries());
    }

    public void put(TrustRecord record) {
        if (record == null || record.path() == null || record.sha256() == null) {
            throw new IllegalArgumentException("trust record requires path and sha256");
        }
        List<TrustRecord> next = new ArrayList<>();
        Path wanted = canonicalize(Path.of(record.path()));
        for (TrustRecord existing : all()) {
            if (existing.path() == null || !pathsMatch(wanted, Path.of(existing.path()))) {
                next.add(existing);
            }
        }
        next.add(new TrustRecord(
            wanted.toString(),
            record.sha256().toLowerCase(),
            record.capabilities(),
            record.trustedAt()
        ));
        writeFile(new TrustFile(SCHEMA_VERSION, next));
    }

    public void remove(Path canonicalFile) {
        if (canonicalFile == null) {
            return;
        }
        Path wanted = canonicalize(canonicalFile);
        List<TrustRecord> next = new ArrayList<>();
        boolean removed = false;
        for (TrustRecord existing : all()) {
            if (existing.path() != null && pathsMatch(wanted, Path.of(existing.path()))) {
                removed = true;
                continue;
            }
            next.add(existing);
        }
        if (removed) {
            writeFile(new TrustFile(SCHEMA_VERSION, next));
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes == null ? new byte[0] : bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    public static Path canonicalize(Path file) {
        if (file == null) {
            return null;
        }
        try {
            if (Files.exists(file)) {
                return file.toRealPath();
            }
        } catch (IOException ignored) {
        }
        return file.toAbsolutePath().normalize();
    }

    private static boolean pathsMatch(Path left, Path right) {
        if (left == null || right == null) {
            return false;
        }
        return canonicalize(left).equals(canonicalize(right));
    }

    private TrustFile readFile() {
        Path path = storePath();
        if (path == null || !Files.exists(path)) {
            return new TrustFile(SCHEMA_VERSION, List.of());
        }
        try {
            TrustFile file = JSON.readValue(Files.readAllBytes(path), TrustFile.class);
            if (file.schemaVersion() != null && file.schemaVersion() != SCHEMA_VERSION) {
                log.warnf("trust.json schema_version %s is not %s — ignoring store",
                    file.schemaVersion(), SCHEMA_VERSION);
                return new TrustFile(SCHEMA_VERSION, List.of());
            }
            return file;
        } catch (Exception e) {
            log.warnf("Cannot read trust.json: %s", e.getMessage());
            return new TrustFile(SCHEMA_VERSION, List.of());
        }
    }

    private void writeFile(TrustFile file) {
        Path configDir = configDir();
        if (configDir == null) {
            throw new IllegalStateException("config directory is not available");
        }
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create config directory: " + e.getMessage(), e);
        }
        Path target = configDir.resolve(FILE_NAME);
        SafePathValidator.ContainmentResult containment = SafePathValidator.contain(target, configDir);
        if (!containment.contained()) {
            throw new IllegalStateException("Refusing to write trust.json: " + containment.reason());
        }
        try {
            byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(file);
            Path tmp = configDir.resolve(FILE_NAME + ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write trust.json: " + e.getMessage(), e);
        }
    }

    private Path configDir() {
        return platformDirs == null ? null : platformDirs.resolveConfigDir();
    }
}
