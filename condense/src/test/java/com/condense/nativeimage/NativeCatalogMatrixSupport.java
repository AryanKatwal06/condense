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
