package com.condense.hooks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Conservative finite-state shell command line analyzer for agent hook decisions.
 *
 * <p>Splits commands across control operators and pipelines without executing or
 * rewriting the shell string. Strictly prevents command-interception bypasses from
 * chained or nested commands, and defaults ambiguous syntax to deny/ask rather than
 * allowing raw uncompressed execution.
 */
public final class CompoundCommandAnalyzer {

    public enum Decision {
        ALLOW,
        DENY,
        AMBIGUOUS
    }

    public record CommandSegment(
        String raw,
        String executable,
        boolean matchesCondense,
        boolean ambiguous,
        String ambiguityReason
    ) {}

    public record AnalysisResult(
        Decision decision,
        List<CommandSegment> segments,
        List<String> matchedCommands,
        String reason
    ) {
        public boolean shouldDeny() {
            return decision == Decision.DENY || decision == Decision.AMBIGUOUS;
        }

        public boolean shouldAllow() {
            return decision == Decision.ALLOW;
        }

        public boolean isAmbiguous() {
            return decision == Decision.AMBIGUOUS;
        }

        public String formattedReason(String originalCommand) {
            if (decision == Decision.DENY) {
                if (matchedCommands.size() == 1 && segments.size() == 1) {
                    return "Use \"condense " + originalCommand.trim() + "\" instead to get filtered, token-efficient output.";
                }
                return "Command contains intercepted command(s) [" + String.join(", ", matchedCommands) +
                       "]. Use condense for compressed output.";
            }
            if (decision == Decision.AMBIGUOUS) {
                return "Command contains complex or ambiguous shell syntax (" + reason +
                       "). Run through condense or execute discrete commands.";
            }
            return "";
        }
    }

    private static final Pattern ENV_ASSIGNMENT = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*=.*$");
    private static final Set<String> WRAPPER_COMMANDS = Set.of(
        "sudo", "env", "nohup", "time", "command", "builtin"
    );
    private static final Set<String> DYNAMIC_COMMANDS = Set.of(
        "eval", "exec", "source", "."
    );

    private CompoundCommandAnalyzer() {}

    /**
     * Analyzes {@code commandLine} against the set of {@code condenseCommands}.
     */
    public static AnalysisResult analyze(String commandLine, Set<String> condenseCommands) {
        if (commandLine == null || commandLine.isBlank()) {
            return new AnalysisResult(Decision.ALLOW, List.of(), List.of(), "Empty command");
        }

        Set<String> normalizedCondense = new LinkedHashSet<>();
        if (condenseCommands != null) {
            for (String cmd : condenseCommands) {
                if (cmd != null && !cmd.isBlank()) {
                    normalizedCondense.add(normalizeExecutableName(cmd));
                }
            }
        }

        String sanitized = normalizeUnicodeWhitespace(commandLine);
        List<String> rawSegments = new ArrayList<>();
        String splitError = splitSegments(sanitized, rawSegments);
        if (splitError != null) {
            return new AnalysisResult(
                Decision.AMBIGUOUS,
                List.of(),
                List.of(),
                splitError
            );
        }

        if (rawSegments.isEmpty()) {
            return new AnalysisResult(Decision.ALLOW, List.of(), List.of(), "No executable segments");
        }

        List<CommandSegment> segments = new ArrayList<>();
        List<String> matchedCommands = new ArrayList<>();
        boolean hasAmbiguity = false;
        String ambiguityReason = null;

        for (String rawSegment : rawSegments) {
            SegmentAnalysis sa = analyzeSingleSegment(rawSegment, normalizedCondense);
            CommandSegment cs = new CommandSegment(
                rawSegment,
                sa.executable,
                sa.matchesCondense,
                sa.ambiguous,
                sa.ambiguityReason
            );
            segments.add(cs);

            if (sa.matchesCondense && sa.executable != null) {
                matchedCommands.add(sa.executable);
            }
            if (sa.ambiguous) {
                hasAmbiguity = true;
                if (ambiguityReason == null) {
                    ambiguityReason = sa.ambiguityReason;
                }
            }
        }

        // Intercepted commands take priority for deny guidance
        if (!matchedCommands.isEmpty()) {
            return new AnalysisResult(
                Decision.DENY,
                Collections.unmodifiableList(segments),
                Collections.unmodifiableList(matchedCommands),
                "Matched condensing command(s): " + String.join(", ", matchedCommands)
            );
        }

        // If any segment has unparsed/ambiguous constructs, default to AMBIGUOUS (deny/ask)
        if (hasAmbiguity) {
            return new AnalysisResult(
                Decision.AMBIGUOUS,
                Collections.unmodifiableList(segments),
                List.of(),
                ambiguityReason != null ? ambiguityReason : "Ambiguous shell syntax"
            );
        }

        // All segments cleanly parsed and none match Condense
        return new AnalysisResult(
            Decision.ALLOW,
            Collections.unmodifiableList(segments),
            List.of(),
            "All segments safe to execute directly"
        );
    }

    private static String normalizeUnicodeWhitespace(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\n' || c == '\r') {
                sb.append(c);
            } else if (Character.isWhitespace(c) || c == '\u00A0' || c == '\u2007' || c == '\u202F' || c == '\uFEFF') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Splits a compound command string into individual segment strings.
     * Returns null on success, or an error description if syntax is unparseable.
     */
    static String splitSegments(String input, List<String> outSegments) {
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;
        int len = input.length();

        for (int i = 0; i < len; i++) {
            char c = input.charAt(i);

            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }

            if (c == '\\' && !inSingle) {
                escaped = true;
                current.append(c);
                continue;
            }

            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                current.append(c);
                continue;
            }

            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                current.append(c);
                continue;
            }

            if (!inSingle && !inDouble) {
                // Check for here-doc redirection '<<'
                if (c == '<' && i + 1 < len && input.charAt(i + 1) == '<') {
                    return "Here-document redirection '<<' is not permitted in automated hook decisions";
                }

                // Check for control operators
                if (c == ';') {
                    addSegment(current, outSegments);
                    continue;
                }
                if (c == '\n') {
                    addSegment(current, outSegments);
                    continue;
                }
                if (c == '\r') {
                    addSegment(current, outSegments);
                    if (i + 1 < len && input.charAt(i + 1) == '\n') {
                        i++; // skip \n in \r\n
                    }
                    continue;
                }
                if (c == '&') {
                    if (i + 1 < len && input.charAt(i + 1) == '&') {
                        addSegment(current, outSegments);
                        i++; // skip second &
                        continue;
                    }
                    // single background operator &
                    addSegment(current, outSegments);
                    continue;
                }
                if (c == '|') {
                    if (i + 1 < len && (input.charAt(i + 1) == '|' || input.charAt(i + 1) == '&')) {
                        addSegment(current, outSegments);
                        i++; // skip second | or &
                        continue;
                    }
                    addSegment(current, outSegments);
                    continue;
                }
            }

            current.append(c);
        }

        if (escaped) {
            return "Dangling escape character at end of command";
        }
        if (inSingle) {
            return "Unclosed single quote";
        }
        if (inDouble) {
            return "Unclosed double quote";
        }

        addSegment(current, outSegments);
        return null;
    }

    private static void addSegment(StringBuilder current, List<String> outSegments) {
        String trimmed = current.toString().trim();
        if (!trimmed.isEmpty()) {
            outSegments.add(trimmed);
        }
        current.setLength(0);
    }

    private record SegmentAnalysis(
        String executable,
        boolean matchesCondense,
        boolean ambiguous,
        String ambiguityReason
    ) {}

    private static SegmentAnalysis analyzeSingleSegment(String segment, Set<String> condenseCommands) {
        // Detect command substitution $() or backticks
        if (containsSubshellOrBacktick(segment)) {
            String innerCmd = findCondenseCommandInside(segment, condenseCommands);
            if (innerCmd != null) {
                return new SegmentAnalysis(innerCmd, true, true, "Command substitution contains condensing command: " + innerCmd);
            }
            return new SegmentAnalysis(null, false, true, "Command substitution ($() or backticks) detected");
        }

        List<String> tokens = tokenizeWords(segment);
        if (tokens.isEmpty()) {
            return new SegmentAnalysis(null, false, false, null);
        }

        int idx = 0;

        // Skip leading redirections and environment variable assignments
        while (idx < tokens.size()) {
            String token = tokens.get(idx);
            if (isRedirection(token)) {
                idx++;
                if (isStandaloneRedirect(token) && idx < tokens.size()) {
                    idx++; // skip target file
                }
                continue;
            }
            if (isEnvAssignment(token)) {
                idx++;
                continue;
            }
            break;
        }

        if (idx >= tokens.size()) {
            return new SegmentAnalysis(null, false, false, null);
        }

        // Check wrapper commands (sudo, env, etc.)
        while (idx < tokens.size()) {
            String rawToken = tokens.get(idx);
            String base = normalizeExecutableName(rawToken);

            if (DYNAMIC_COMMANDS.contains(base)) {
                return new SegmentAnalysis(base, false, true, "Dynamic execution command detected: " + base);
            }

            if (WRAPPER_COMMANDS.contains(base)) {
                idx++;
                // Skip options to the wrapper (e.g. -u user, -i)
                while (idx < tokens.size()) {
                    String opt = tokens.get(idx);
                    if (isRedirection(opt)) {
                        idx++;
                        if (isStandaloneRedirect(opt) && idx < tokens.size()) {
                            idx++;
                        }
                    } else if (opt.startsWith("-")) {
                        idx++;
                        if (opt.equals("-u") || opt.equals("-C") || opt.equals("-g")) {
                            if (idx < tokens.size() && !tokens.get(idx).startsWith("-")) {
                                idx++;
                            }
                        }
                    } else if (isEnvAssignment(opt)) {
                        idx++;
                    } else {
                        break;
                    }
                }
            } else {
                break;
            }
        }

        // Skip any remaining redirections before command word
        while (idx < tokens.size()) {
            String token = tokens.get(idx);
            if (isRedirection(token)) {
                idx++;
                if (isStandaloneRedirect(token) && idx < tokens.size()) {
                    idx++;
                }
            } else {
                break;
            }
        }

        if (idx >= tokens.size()) {
            return new SegmentAnalysis(null, false, false, null);
        }

        String rawCmd = tokens.get(idx);
        String baseCmd = normalizeExecutableName(rawCmd);

        boolean matches = condenseCommands.contains(baseCmd);
        return new SegmentAnalysis(baseCmd, matches, false, null);
    }

    private static boolean containsSubshellOrBacktick(String input) {
        boolean inSingle = false;
        boolean escaped = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '\'' && !escaped) {
                inSingle = !inSingle;
                continue;
            }
            if (!inSingle) {
                if (c == '`') {
                    return true;
                }
                if (c == '$' && i + 1 < input.length() && input.charAt(i + 1) == '(') {
                    return true;
                }
            }
        }
        return false;
    }

    private static String findCondenseCommandInside(String text, Set<String> condenseCommands) {
        String[] words = text.split("[\\s$`\"'();|&]+");
        for (String word : words) {
            String base = normalizeExecutableName(word);
            if (condenseCommands.contains(base)) {
                return base;
            }
        }
        return null;
    }

    private static boolean isEnvAssignment(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String unquoted = unquoteAndUnescape(token);
        return ENV_ASSIGNMENT.matcher(unquoted).matches();
    }

    private static boolean isRedirection(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return token.startsWith("<") || token.startsWith(">") ||
               token.startsWith("1>") || token.startsWith("2>") || token.startsWith("&>");
    }

    private static boolean isStandaloneRedirect(String token) {
        if (token == null) {
            return false;
        }
        return token.equals("<") || token.equals(">") || token.equals(">>") ||
               token.equals("2>") || token.equals("1>") || token.equals("2>&1") || token.equals("&>");
    }

    static List<String> tokenizeWords(String segment) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;

        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);

            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }

            if (c == '\\' && !inSingle) {
                escaped = true;
                current.append(c);
                continue;
            }

            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                current.append(c);
                continue;
            }

            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                current.append(c);
                continue;
            }

            if (!inSingle && !inDouble) {
                if (Character.isWhitespace(c)) {
                    if (!current.isEmpty()) {
                        words.add(current.toString());
                        current.setLength(0);
                    }
                    continue;
                }
                if (c == '<' || c == '>') {
                    if (!current.isEmpty()) {
                        words.add(current.toString());
                        current.setLength(0);
                    }
                    current.append(c);
                    while (i + 1 < segment.length()) {
                        char next = segment.charAt(i + 1);
                        if (next == '>' || next == '<' || next == '&') {
                            current.append(next);
                            i++;
                        } else {
                            break;
                        }
                    }
                    words.add(current.toString());
                    current.setLength(0);
                    continue;
                }
            }

            current.append(c);
        }

        if (!current.isEmpty()) {
            words.add(current.toString());
        }

        return words;
    }

    static String unquoteAndUnescape(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        String s = token.trim();
        if ((s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) ||
            (s.startsWith("'") && s.endsWith("'") && s.length() >= 2)) {
            s = s.substring(1, s.length() - 1);
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                sb.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String normalizeExecutableName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String clean = raw.trim();

        // Strip quotes if present
        if ((clean.startsWith("\"") && clean.endsWith("\"") && clean.length() >= 2) ||
            (clean.startsWith("'") && clean.endsWith("'") && clean.length() >= 2)) {
            clean = clean.substring(1, clean.length() - 1);
        }

        // Normalize path separators
        clean = clean.replace('\\', '/');
        int lastSlash = clean.lastIndexOf('/');
        if (lastSlash >= 0) {
            clean = clean.substring(lastSlash + 1);
        }

        // If leading backslash remains (e.g. \git)
        if (clean.startsWith("\\")) {
            clean = clean.substring(1);
        }

        clean = clean.trim().toLowerCase(Locale.ROOT);
        if (clean.endsWith(".exe") || clean.endsWith(".cmd") || clean.endsWith(".bat")) {
            clean = clean.substring(0, clean.lastIndexOf('.'));
        }
        return clean;
    }
}
