package com.condense.trust;

/**
 * Marks Condense-generated filtered output and quotes impersonating lines.
 */
public final class Provenance {

    public static final String STAMP = "condense[filtered]";
    public static final String QUOTED = "condense[quoted]";

    private Provenance() {}

    public static String neutralize(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean first = true;
        for (String line : text.split("\\R", -1)) {
            if (!first) {
                out.append('\n');
            }
            first = false;
            out.append(STAMP.equals(line) ? QUOTED : line);
        }
        return out.toString();
    }

    public static String stamp(String body) {
        String clean = neutralize(body);
        if (clean.isEmpty()) {
            return STAMP;
        }
        return STAMP + "\n" + clean;
    }

    public static String passthrough(String body) {
        return neutralize(body);
    }
}
