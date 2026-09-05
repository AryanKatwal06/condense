package com.condense.trust;

/**
 * Marks Condense-generated filtered output and quotes impersonating lines.
 */
public final class Provenance {

    public static final String STAMP = "condense[filtered]";
    public static final String READ_STAMP = "condense[read]";
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
            out.append(isStampLine(line) ? QUOTED : line);
        }
        return out.toString();
    }

    public static boolean isStampLine(String line) {
        return STAMP.equals(line) || READ_STAMP.equals(line);
    }

    public static String stamp(String body) {
        String clean = neutralize(body);
        if (clean.isEmpty()) {
            return STAMP;
        }
        return STAMP + "\n" + clean;
    }

    public static String stampRead(String body) {
        String clean = neutralize(body);
        if (clean.isEmpty()) {
            return READ_STAMP;
        }
        return READ_STAMP + "\n" + clean;
    }

    public static String passthrough(String body) {
        return neutralize(body);
    }
}
