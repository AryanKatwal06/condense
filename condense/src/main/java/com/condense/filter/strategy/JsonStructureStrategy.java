package com.condense.filter.strategy;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

public final class JsonStructureStrategy implements FilterStage {

    public static final JsonStructureStrategy INSTANCE = new JsonStructureStrategy();

    public JsonStructureStrategy() {}

    private static final ObjectMapper MAPPER = com.condense.core.Mappers.JSON;
    private static final int MAX_DEPTH = 6;

    @Override
    public StageResult process(String input, FilterContext context) {
        return StageResult.continueWith(skeleton(input));
    }

    /**
     * Parses {@code json} and returns a schema-skeleton string.
     * Returns the original text unchanged if parsing fails.
     *
     * @param json  raw JSON string
     * @return schema skeleton, or original text on parse failure
     */
    public static String skeleton(String json) {
        if (json == null || json.isBlank()) return json;
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode skeleton = skeletonNode(root, 0);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(skeleton);
        } catch (Exception e) {
            return json; // Not valid JSON — return unchanged
        }
    }

    private static JsonNode skeletonNode(JsonNode node, int depth) {
        if (depth >= MAX_DEPTH) return MAPPER.getNodeFactory().textNode("...");

        if (node.isObject()) {
            ObjectNode obj = MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                obj.set(field.getKey(), skeletonNode(field.getValue(), depth + 1));
            }
            return obj;
        }

        if (node.isArray()) {
            ArrayNode arr = MAPPER.createArrayNode();
            if (node.size() > 0) {
                // Show type of first element only, with count
                arr.add(skeletonNode(node.get(0), depth + 1));
                if (node.size() > 1) {
                    arr.add(MAPPER.getNodeFactory()
                        .textNode("... +" + (node.size() - 1) + " more"));
                }
            }
            return arr;
        }

        if (node.isTextual())  return MAPPER.getNodeFactory().textNode("<string>");
        if (node.isNumber())   return MAPPER.getNodeFactory().numberNode(0);
        if (node.isBoolean())  return MAPPER.getNodeFactory().booleanNode(false);
        if (node.isNull())     return MAPPER.getNodeFactory().nullNode();
        return node; // Unknown node type — keep as-is
    }
}