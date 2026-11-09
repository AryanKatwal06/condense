package com.condense.filter.pipeline;

import com.condense.filter.strategy.BoundedRegex;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic preservation of opaque debugging handles across aggressive
 * compression.
 *
 * <p>A summary that drops thousands of middle lines can also drop the one UUID,
 * CVE, ticket ID, or commit SHA a caller needs to continue debugging. The
 * factsheet extracts those identifiers from the raw output, keeps only the ones
 * the filtered output no longer contains, and renders a single bounded line.
 *
 * <p>Every match runs through {@link BoundedRegex} and inspection is capped at
 * {@link FidelityGuard#MAX_INSPECTION_CHARS} by sharing
 * {@link FidelityGuard#sampleHeadAndTail(String)}.
 */
public final class IdentifierFactsheet {

    /** Maximum identifiers rendered on one factsheet line. */
    public static final int MAX_IDENTIFIERS = 12;

    /** Maximum formatted factsheet line length in characters. */
    public static final int MAX_FACTSHEET_CHARS = 240;

    /**
     * Raw outputs below this size are not large enough for identifier loss to
     * matter, and appending a factsheet to them would add noise. Kept high so
     * locked corpus goldens stay byte-identical.
     */
    public static final int APPEND_MIN_RAW_CHARS = 16_384;

    /** Append only when the filtered output is at most this percentage of raw. */
    public static final int APPEND_MAX_FILTERED_PERCENT = 15;

    private static final String PREFIX = "[preserved ids: ";
    private static final String SUFFIX = "]";

    private static final Pattern UUID = Pattern.compile(
        "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");

    private static final Pattern CVE = Pattern.compile(
        "\\bCVE-\\d{4}-\\d{4,7}\\b");

    private static final Pattern TICKET = Pattern.compile(
        "\\b[A-Z]{2,10}-\\d{2,6}\\b");

    private static final Pattern SHA = Pattern.compile(
        "\\b[0-9a-f]{7,40}\\b");

    /** Uppercase prefixes that look like ticket IDs but are standard notation. */
    private static final Set<String> NOISE_PREFIXES = Set.of(
        "UTF", "ISO", "SHA", "HTTP", "HTTPS", "RFC", "TLS", "SSL", "AES",
        "MD", "SHA1", "SHA2", "SHA3", "CVE", "IPV", "UUID");

    private IdentifierFactsheet() {}

    /**
     * Extract identifiers present in {@code raw} but absent from
     * {@code filtered}.
     *
     * @return up to {@link #MAX_IDENTIFIERS} unique identifiers in discovery
     *         order; never {@code null}
     */
    public static List<String> extractDroppedIdentifiers(String raw, String filtered) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String sample = FidelityGuard.sampleHeadAndTail(raw);
        String kept = filtered == null ? "" : filtered;
        Set<String> identifiers = new LinkedHashSet<>();

        collect(UUID, sample, kept, identifiers);
        collect(CVE, sample, kept, identifiers);
        collectTickets(sample, kept, identifiers);
        collectShas(sample, kept, identifiers);

        List<String> result = new ArrayList<>(identifiers);
        return result.size() <= MAX_IDENTIFIERS
            ? List.copyOf(result)
            : List.copyOf(result.subList(0, MAX_IDENTIFIERS));
    }

    /**
     * Render a bounded factsheet line. Returns the empty string when no
     * identifiers survive, so callers can append nothing.
     */
    public static String formatFactsheetLine(List<String> droppedIds) {
        if (droppedIds == null || droppedIds.isEmpty()) {
            return "";
        }
        List<String> ids = new ArrayList<>();
        for (String id : droppedIds) {
            if (id == null || id.isBlank() || ids.contains(id)) {
                continue;
            }
            ids.add(id);
            if (ids.size() >= MAX_IDENTIFIERS) {
                break;
            }
        }
        while (!ids.isEmpty()) {
            String line = PREFIX + String.join(", ", ids) + SUFFIX;
            if (line.length() <= MAX_FACTSHEET_CHARS) {
                return line;
            }
            ids.remove(ids.size() - 1);
        }
        return "";
    }

    private static void collect(Pattern pattern, String sample, String kept, Set<String> out) {
        Matcher matcher = BoundedRegex.matcher(pattern, sample);
        while (matcher.find() && out.size() < MAX_IDENTIFIERS) {
            addIfDropped(matcher.group(), kept, out);
        }
    }

    private static void collectTickets(String sample, String kept, Set<String> out) {
        Matcher matcher = BoundedRegex.matcher(TICKET, sample);
        while (matcher.find() && out.size() < MAX_IDENTIFIERS) {
            String id = matcher.group();
            int dash = id.indexOf('-');
            String prefix = dash < 0 ? id : id.substring(0, dash);
            if (NOISE_PREFIXES.contains(prefix)) {
                continue;
            }
            addIfDropped(id, kept, out);
        }
    }

    private static void collectShas(String sample, String kept, Set<String> out) {
        List<int[]> uuidSpans = new ArrayList<>();
        Matcher uuid = BoundedRegex.matcher(UUID, sample);
        while (uuid.find()) {
            uuidSpans.add(new int[] {uuid.start(), uuid.end()});
        }
        Matcher matcher = BoundedRegex.matcher(SHA, sample);
        while (matcher.find() && out.size() < MAX_IDENTIFIERS) {
            // A hex block inside a UUID is not a standalone Git object name.
            int start = matcher.start();
            int end = matcher.end();
            if (withinAnySpan(start, end, uuidSpans)) {
                continue;
            }
            String id = matcher.group();
            if (!hasTwoDigitsAndTwoLetters(id)) {
                continue;
            }
            addIfDropped(id, kept, out);
        }
    }

    private static boolean withinAnySpan(int start, int end, List<int[]> spans) {
        for (int[] span : spans) {
            if (start >= span[0] && end <= span[1]) {
                return true;
            }
        }
        return false;
    }

    private static void addIfDropped(String id, String kept, Set<String> out) {
        if (id != null && !id.isBlank() && !kept.contains(id)) {
            out.add(id);
        }
    }

    private static boolean hasTwoDigitsAndTwoLetters(String id) {
        int digits = 0;
        int letters = 0;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
            } else if (c >= 'a' && c <= 'f') {
                letters++;
            }
        }
        return digits >= 2 && letters >= 2;
    }
}
