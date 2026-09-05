package com.condense.propose;

import com.condense.core.SafePathValidator;
import com.condense.filter.pipeline.config.FilterOverrideConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * Writes {@code .condense/filters.toml.proposed} only. Refuses the live override filename.
 */
public final class ProposeWriter {

    public static final String REL_PATH = ".condense/filters.toml.proposed";
    public static final String LIVE_FILE_NAME = "filters.toml";
    public static final String PROPOSED_FILE_NAME = "filters.toml.proposed";

    private final ProposeService service;

    public ProposeWriter() {
        this(new ProposeService());
    }

    public ProposeWriter(ProposeService service) {
        this.service = service;
    }

    public Path write(Path root, ProposeReport report) throws IOException {
        if (root == null) {
            throw new IllegalArgumentException("root is required");
        }
        Path dir = root.resolve(".condense");
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(dir)) {
            throw new IOException("refusing to write through a .condense symlink");
        }
        Files.createDirectories(dir);
        Path destination = dir.resolve(PROPOSED_FILE_NAME);
        return writeTo(destination, root, report);
    }

    Path writeTo(Path destination, Path containedBy, ProposeReport report) throws IOException {
        if (destination == null || destination.getFileName() == null) {
            throw new IllegalArgumentException("destination is required");
        }
        if (LIVE_FILE_NAME.equals(destination.getFileName().toString())) {
            throw new IllegalArgumentException("refusing to write live filters.toml");
        }
        SafePathValidator.ContainmentResult contained = SafePathValidator.contain(destination, containedBy);
        if (!contained.contained()) {
            throw new IOException("proposed file is outside the workspace: " + contained.reason());
        }
        Map<String, List<FilterOverrideConfig.StageDef>> filters = service.readyFilters(report);
        byte[] bytes = ProposeToml.document(filters).getBytes(StandardCharsets.UTF_8);
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = Files.createTempFile(
            parent == null ? Path.of(".") : parent,
            ".condense-proposed-",
            ".toml.tmp");
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, destination,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        return destination;
    }
}
