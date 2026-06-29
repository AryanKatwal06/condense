package com.condense.filter.strategy;

import java.util.*;

/**
 * Converts a flat list of file paths into a compact directory tree.
 *
 * <p>Directories with more than {@link #MAX_FILES_PER_DIR} files show a summary
 * line instead of every file, keeping the output compact for large repos.
 *
 * <p>Example (50 paths → 8 lines):
 * <pre>
 * src/
 *   main/
 *     java/com/example/    (12 files)
 *   test/
 *     java/com/example/
 *       FooTest.java
 *       BarTest.java
 * </pre>
 */
public final class TreeCompressionStrategy {

    private TreeCompressionStrategy() {}

    /** Directories with more files than this show a summary count. */
    public static final int MAX_FILES_PER_DIR = 8;

    /**
     * Builds a compressed tree string from a list of file paths.
     *
     * @param paths flat list of paths (relative or absolute)
     * @return multi-line indented tree; empty string if paths is empty
     */
    public static String compress(List<String> paths) {
        if (paths == null || paths.isEmpty()) return "";

        // Build a tree: dir → list of children (files or sub-dirs)
        Map<String, List<String>> tree = new TreeMap<>();
        for (String path : paths) {
            String normalised = path.trim().replace('\\', '/');
            if (normalised.isBlank()) continue;
            int lastSlash = normalised.lastIndexOf('/');
            String dir  = lastSlash >= 0 ? normalised.substring(0, lastSlash) : ".";
            String file = lastSlash >= 0 ? normalised.substring(lastSlash + 1) : normalised;
            tree.computeIfAbsent(dir, k -> new ArrayList<>()).add(file);
        }

        StringBuilder sb = new StringBuilder();
        // Collect unique top-level dirs
        Set<String> topDirs = new TreeSet<>();
        for (String dir : tree.keySet()) {
            String top = dir.contains("/") ? dir.substring(0, dir.indexOf('/')) : dir;
            topDirs.add(top);
        }

        for (String top : topDirs) {
            sb.append(top).append("/\n");
            renderDir(sb, tree, top, "  ");
        }

        return sb.toString().stripTrailing();
    }

    private static void renderDir(StringBuilder sb, Map<String, List<String>> tree,
                                   String dir, String indent) {
        List<String> files = tree.getOrDefault(dir, List.of());
        if (files.size() > MAX_FILES_PER_DIR) {
            sb.append(indent).append("(").append(files.size()).append(" files)\n");
        } else {
            for (String file : files) {
                sb.append(indent).append(file).append("\n");
            }
        }

        // Render sub-directories
        for (String subDir : new TreeSet<>(tree.keySet())) {
            if (subDir.startsWith(dir + "/") &&
                !subDir.substring(dir.length() + 1).contains("/")) {
                String subName = subDir.substring(dir.length() + 1);
                sb.append(indent).append(subName).append("/\n");
                renderDir(sb, tree, subDir, indent + "  ");
            }
        }
    }
}