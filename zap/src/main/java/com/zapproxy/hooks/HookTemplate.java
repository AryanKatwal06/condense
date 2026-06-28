package com.zapproxy.hooks;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Loads hook template content from classpath resources and applies substitutions.
 *
 * <p>Templates are stored at {@code /hooks/{tool-name}/{filename}} and bundled
 * in the native image via the {@code hooks/**} resource include pattern.
 *
 * <p>Substitution tokens:
 * <ul>
 *   <li>{@code {{ZAP_COMMANDS}}} — space-separated list of filtered commands</li>
 *   <li>{@code {{EXCLUDE_COMMANDS}}} — comma-separated user exclusion list</li>
 * </ul>
 */
public final class HookTemplate {

    /** Sentinel string that identifies Zap-managed hook files. */
    public static final String SENTINEL = "# Installed by: zap init";

    private HookTemplate() {}

    /**
     * Loads the template for {@code tool} and returns its content as a string.
     *
     * @throws IOException if the template resource is not found on the classpath
     */
    public static String load(HookTool tool) throws IOException {
        String resource = tool.templateResource;
        try (InputStream in = HookTemplate.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException(
                    "Hook template not found on classpath: " + resource +
                    " — ensure the hooks/** resource pattern is in resource-config.json");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Applies user-specific substitutions to a loaded template string.
     *
     * @param template         raw template content from {@link #load(HookTool)}
     * @param excludeCommands  commands the user has excluded from hook interception
     * @return final hook content ready to write to disk
     */
    public static String apply(String template, List<String> excludeCommands) {
        if (excludeCommands == null || excludeCommands.isEmpty()) {
            return template;
        }
        // Remove excluded commands from the ZAP_COMMANDS variable
        String excluded = String.join("|", excludeCommands);
        // Simple sed-like replacement: strip excluded commands from the ZAP_COMMANDS line
        String result = template;
        for (String cmd : excludeCommands) {
            result = result
                .replace(" " + cmd.trim() + " ", " ")  // middle of list
                .replace(" " + cmd.trim() + "\"", "\"") // end of list
                .replace("\"" + cmd.trim() + " ", "\""); // start of list
        }
        return result;
    }

    /**
     * Returns {@code true} if the given file content was written by Zap.
     * Checks for the {@link #SENTINEL} string.
     */
    public static boolean isManagedByZap(String fileContent) {
        return fileContent != null && fileContent.contains(SENTINEL);
    }
}
