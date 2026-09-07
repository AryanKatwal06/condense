package com.condense.nativeimage;

import com.condense.corpus.CorpusCatalog;
import com.condense.filter.pipeline.config.BuiltinDefinition;
import com.condense.filter.pipeline.config.BuiltinDefinitionCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class NativeCatalogMatrixSupport {

    static final String RESOURCE = "/inventory/native-catalog-matrix.json";

    private NativeCatalogMatrixSupport() {}

    record Row(String definition, String command, String fixture, int exitCode, List<String> mustContain) {}

    static List<Row> load() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try (var in = NativeCatalogMatrixSupport.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing");
            }
            root = mapper.readTree(in);
        }
        List<Row> rows = new ArrayList<>();
        for (JsonNode node : root.path("entries")) {
            List<String> signals = new ArrayList<>();
            node.path("must_contain").forEach(signal -> signals.add(signal.asText()));
            rows.add(new Row(
                node.path("definition").asText(),
                node.path("command").asText(),
                node.path("fixture").asText(),
                node.path("exit_code").asInt(),
                List.copyOf(signals)
            ));
        }
        return rows;
    }

    static List<Row> shard(List<Row> rows, String shardSpec) {
        if (shardSpec == null || shardSpec.isBlank()) {
            return rows;
        }
        String[] parts = shardSpec.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("condense.native.catalog.shard must be i/n");
        }
        int index = Integer.parseInt(parts[0].trim());
        int total = Integer.parseInt(parts[1].trim());
        List<Row> selected = new ArrayList<>();
        for (Row row : rows) {
            if (Math.floorMod(row.definition().hashCode(), total) == index) {
                selected.add(row);
            }
        }
        return selected;
    }

    static Set<String> indexNames() {
        return new LinkedHashSet<>(BuiltinDefinitionCatalog.standalone().names());
    }

    static BuiltinDefinition definitionForCommand(String command) {
        return BuiltinDefinitionCatalog.standalone().findByCommand(command);
    }

    /**
     * Drop the proxy tee footer so passthrough rows can be compared to the
     * fixture. {@code ProxyService} always appends
     * {@code [raw output saved to: ...]} after a trailing newline.
     */
    static String stripTeeFooter(String stdout) {
        if (stdout == null || stdout.isEmpty()) {
            return stdout == null ? "" : stdout;
        }
        String normalized = stdout.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
        int lastNl = normalized.lastIndexOf('\n');
        String lastLine = lastNl < 0 ? normalized : normalized.substring(lastNl + 1);
        if (lastLine.startsWith("[raw output saved to:")) {
            return lastNl < 0 ? "" : normalized.substring(0, lastNl).stripTrailing();
        }
        return normalized;
    }

    static boolean compressedRequiresStamp(String fixtureText, String stdout) {
        String fixture = fixtureText == null
            ? ""
            : fixtureText.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
        return !stripTeeFooter(stdout).equals(fixture);
    }

    static List<Row> firstCorpusRowPerDefinition() throws Exception {
        List<Row> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (CorpusCatalog.Entry entry : CorpusCatalog.load().entries()) {
            BuiltinDefinition definition = definitionForCommand(entry.command());
            if (definition == null || !seen.add(definition.name())) {
                continue;
            }
            rows.add(new Row(
                definition.name(),
                entry.command(),
                entry.fixture(),
                entry.exitCode(),
                entry.criticalSignals().subList(0, Math.min(1, entry.criticalSignals().size()))
            ));
        }
        return rows;
    }
}
