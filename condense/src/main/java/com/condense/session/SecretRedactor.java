package com.condense.session;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-assurance secret redaction engine for commands, parameters, and error snippets.
 * Ensures zero credentials or sensitive keys leak into session intelligence reports or proposals.
 */
public final class SecretRedactor {

    private static final Pattern PRIVATE_KEY_PATTERN = Pattern.compile(
        "-----BEGIN[A-Z0-9_ ]*PRIVATE KEY-----[\\s\\S]*?-----END[A-Z0-9_ ]*PRIVATE KEY-----"
    );

    private static final List<ReplacementRule> RULES = List.of(
        // Known Provider API Keys
        new ReplacementRule(Pattern.compile("sk-ant-[a-zA-Z0-9_\\-]{20,}"), "[REDACTED_ANTHROPIC_KEY]"),
        new ReplacementRule(Pattern.compile("sk-(?:proj-)?[a-zA-Z0-9_\\-]{20,}"), "[REDACTED_OPENAI_KEY]"),
        new ReplacementRule(Pattern.compile("AIza[0-9A-Za-z\\-_]{30,45}"), "[REDACTED_GOOGLE_KEY]"),
        new ReplacementRule(Pattern.compile("(?:ghp|gho|ghu|ghs|ghr)_[a-zA-Z0-9]{36}|github_pat_[a-zA-Z0-9_]{22,}"), "[REDACTED_GITHUB_TOKEN]"),
        new ReplacementRule(Pattern.compile("(?:AKIA|ASIA)[0-9A-Z]{16}"), "[REDACTED_AWS_KEY]"),
        new ReplacementRule(Pattern.compile("xox[baprs]-[0-9a-zA-Z]{10,48}"), "[REDACTED_SLACK_TOKEN]"),

        // JWT tokens (eyJ...)
        new ReplacementRule(Pattern.compile("eyJ[A-Za-z0-9-_=]{10,}\\.[A-Za-z0-9-_=]{10,}\\.[A-Za-z0-9-_.+/=]{10,}"), "[REDACTED_JWT]"),

        // Bearer Authentication headers
        new ReplacementRule(Pattern.compile("(?i)(bearer\\s+)[a-zA-Z0-9_\\-.]{20,}"), "$1[REDACTED_BEARER_TOKEN]"),

        // CLI flags: --password <value>, --token=<value>, -p <value>
        new ReplacementRule(
            Pattern.compile("(?i)(--(?:password|token|secret|api-key|auth-token|private-key)[=\\s]+)((?!\\[REDACTED_)\\S+)"),
            "$1[REDACTED_SECRET]"
        ),

        // Env var assignments: PASSWORD=..., API_KEY=..., SECRET=...
        new ReplacementRule(
            Pattern.compile("(?i)\\b((?:PASSWORD|PASSWD|SECRET|API_KEY|AUTH_TOKEN|ACCESS_TOKEN)=)((?!\\[REDACTED_)\\S+)"),
            "$1[REDACTED_SECRET]"
        ),

        // Connection URIs with credentials: protocol://user:password@host
        new ReplacementRule(
            Pattern.compile("([a-zA-Z0-9+\\-]+://[^:]+:)(?!\\[REDACTED_)([^@\\s]+)(@\\S+)"),
            "$1[REDACTED_PASSWORD]$3"
        )
    );

    private SecretRedactor() {}

    /**
     * Redacts all identifiable API keys, tokens, private keys, passwords, and URIs.
     *
     * @param input raw input string
     * @return sanitized string with all secrets masked
     */
    public static String redact(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        // First handle multiline private keys
        String result = PRIVATE_KEY_PATTERN.matcher(input).replaceAll("[REDACTED_PRIVATE_KEY]");

        // Apply specific pattern replacement rules
        for (ReplacementRule rule : RULES) {
            Matcher m = rule.pattern.matcher(result);
            if (m.find()) {
                result = m.replaceAll(rule.replacement);
            }
        }

        return result;
    }

    /**
     * Returns true if the input contains any recognizable secret.
     */
    public static boolean containsSecret(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        if (PRIVATE_KEY_PATTERN.matcher(input).find()) {
            return true;
        }
        for (ReplacementRule rule : RULES) {
            if (rule.pattern.matcher(input).find()) {
                return true;
            }
        }
        return false;
    }

    private record ReplacementRule(Pattern pattern, String replacement) {}
}
