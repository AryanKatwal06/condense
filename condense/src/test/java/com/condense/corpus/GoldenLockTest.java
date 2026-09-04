package com.condense.corpus;

import com.condense.core.FilterResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Byte-lock of filtered corpus output. Slice 0 of Phase 4 wrote these files from
 * the pre-migration filters. A later wave that changes output must update the
 * matching golden <em>and</em> record a reason in {@code docs/pipeline-migration-diffs.md}.
 */
class GoldenLockTest {

    static final String GOLDEN_DIR = "/corpus/golden";

    @Test
    void corpusOutputMatchesLockedBytes() throws Exception {
        boolean update = Boolean.parseBoolean(System.getenv("UPDATE_GOLDENS"));
        Path writeDir = resolveGoldenWriteDir();
        if (update) {
            Files.createDirectories(writeDir);
        }

        List<String> missing = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        CorpusCatalog.Catalog catalog = CorpusCatalog.load();
        for (CorpusCatalog.Entry entry : catalog.entries()) {
            FilterResult result = CorpusRunner.apply(entry);
            String actual = result.output() != null ? result.output() : "";
            String fileName = goldenFileName(entry.id());
            if (update) {
                Files.writeString(writeDir.resolve(fileName), actual, StandardCharsets.UTF_8);
                continue;
            }
            String resource = GOLDEN_DIR + "/" + fileName;
            var in = GoldenLockTest.class.getResourceAsStream(resource);
            if (in == null) {
                missing.add(entry.id() + " (" + resource + ")");
                continue;
            }
            String expected;
            try (in) {
                expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!expected.equals(actual)) {
                mismatches.add(entry.id()
                    + " expected " + expected.length() + " chars, actual " + actual.length()
                    + " chars");
            }
        }

        if (update) {
            assertThat(catalog.entries()).hasSize(51);
            return;
        }
        if (!missing.isEmpty()) {
            fail("Missing golden files. Re-run with UPDATE_GOLDENS=true, then commit:\n  "
                + String.join("\n  ", missing));
        }
        if (!mismatches.isEmpty()) {
            fail("Golden lock mismatch. Update the golden and record a reason in "
                + "docs/pipeline-migration-diffs.md:\n  "
                + String.join("\n  ", mismatches));
        }
        assertThat(catalog.entries()).hasSize(51);
    }

    static String goldenFileName(String id) {
        return id.replace('/', '-') + ".txt";
    }

    private static Path resolveGoldenWriteDir() {
        Path cwd = Path.of("").toAbsolutePath();
        Path nested = cwd.resolve("src/test/resources/corpus/golden");
        if (Files.isDirectory(cwd.resolve("src/test/resources/corpus"))) {
            return nested;
        }
        Path fromRoot = cwd.resolve("condense/src/test/resources/corpus/golden");
        if (Files.isDirectory(fromRoot.getParent())) {
            return fromRoot;
        }
        return nested;
    }
}
