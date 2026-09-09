package com.condense.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactionTest {

    @Test
    @DisplayName("Redacts Anthropic API keys")
    void redactsAnthropicKey() {
        String input = "curl https://api.anthropic.com/v1/messages -H 'x-api-key: sk-ant-api03-abcdef1234567890abcdef1234567890-xyz'";
        String redacted = SecretRedactor.redact(input);
        assertThat(redacted).doesNotContain("sk-ant-api03");
        assertThat(redacted).contains("[REDACTED_ANTHROPIC_KEY]");
        assertThat(SecretRedactor.containsSecret(input)).isTrue();
    }

    @Test
    @DisplayName("Redacts OpenAI API keys and Project keys")
    void redactsOpenAiKey() {
        String input1 = "export OPENAI_API_KEY=sk-1234567890abcdef1234567890abcdef";
        String input2 = "export OPENAI_API_KEY=sk-proj-abcdef1234567890abcdef1234567890";
        assertThat(SecretRedactor.redact(input1)).contains("[REDACTED_OPENAI_KEY]");
        assertThat(SecretRedactor.redact(input2)).contains("[REDACTED_OPENAI_KEY]");
    }

    @Test
    @DisplayName("Redacts Google Cloud and Gemini API keys")
    void redactsGoogleApiKey() {
        String input = "gcloud compute instances list --api-key AIzaSyD1234567890abcdef1234567890abcde";
        String redacted = SecretRedactor.redact(input);
        assertThat(redacted).doesNotContain("AIzaSyD");
        assertThat(redacted).contains("[REDACTED_GOOGLE_KEY]");
    }

    @Test
    @DisplayName("Redacts GitHub personal access tokens")
    void redactsGithubTokens() {
        String classic = "git clone https://ghp_1234567890abcdef1234567890abcdef1234@github.com/org/repo.git";
        String fineGrained = "git clone https://github_pat_11AABCDEF01234567890abcdef1234567890@github.com/org/repo.git";
        assertThat(SecretRedactor.redact(classic)).contains("[REDACTED_GITHUB_TOKEN]");
        assertThat(SecretRedactor.redact(classic)).doesNotContain("ghp_1234567890");
        assertThat(SecretRedactor.redact(fineGrained)).contains("[REDACTED_GITHUB_TOKEN]");
        assertThat(SecretRedactor.redact(fineGrained)).doesNotContain("github_pat_11A");
    }

    @Test
    @DisplayName("Redacts AWS access keys")
    void redactsAwsAccessKeys() {
        String input = "aws s3 cp s3://bucket/dump.sql . --access-key AKIAIOSFODNN7EXAMPLE";
        String redacted = SecretRedactor.redact(input);
        assertThat(redacted).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(redacted).contains("[REDACTED_AWS_KEY]");
    }

    @Test
    @DisplayName("Redacts Slack tokens")
    void redactsSlackTokens() {
        String input = "curl -H 'Authorization: Bearer xoxb-1234567890-abcdef123456' https://slack.com/api";
        String redacted = SecretRedactor.redact(input);
        assertThat(redacted).doesNotContain("xoxb-1234567890");
        assertThat(redacted).contains("[REDACTED_SLACK_TOKEN]");
    }

    @Test
    @DisplayName("Redacts JSON Web Tokens (JWT)")
    void redactsJwt() {
        String jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String input = "curl -H 'Authorization: Bearer " + jwt + "' https://example.com";
        String redacted = SecretRedactor.redact(input);
        assertThat(redacted).doesNotContain(jwt);
        assertThat(redacted).contains("[REDACTED_JWT]");
    }

    @Test
    @DisplayName("Redacts command line password and token flags")
    void redactsCommandLineFlags() {
        String input1 = "mysql -u root --password SuperSecretPass123! -e 'SELECT 1'";
        String input2 = "npm publish --token=npm_998877665544332211";
        assertThat(SecretRedactor.redact(input1)).contains("--password [REDACTED_SECRET]");
        assertThat(SecretRedactor.redact(input1)).doesNotContain("SuperSecretPass123!");
        assertThat(SecretRedactor.redact(input2)).contains("--token=[REDACTED_SECRET]");
    }

    @Test
    @DisplayName("Redacts environment variable password assignments")
    void redactsEnvVarAssignments() {
        String input = "DATABASE_URL=postgres://root:p@ssw0rd123@db.prod:5432/main PASSWORD=SuperSecret psql";
        String redacted = SecretRedactor.redact(input);
        assertThat(redacted).doesNotContain("p@ssw0rd123");
        assertThat(redacted).doesNotContain("SuperSecret");
        assertThat(redacted).contains("[REDACTED_PASSWORD]");
        assertThat(redacted).contains("PASSWORD=[REDACTED_SECRET]");
    }

    @Test
    @DisplayName("Redacts PEM format multiline private keys")
    void redactsMultilinePrivateKeys() {
        String key = """
            -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEA0Y7...
            ...lots of key bytes...
            -----END RSA PRIVATE KEY-----
            """;
        String input = "echo '" + key + "' > /tmp/key.pem";
        String redacted = SecretRedactor.redact(input);
        assertThat(redacted).doesNotContain("MIIEowIBAAKCAQEA0Y7");
        assertThat(redacted).contains("[REDACTED_PRIVATE_KEY]");
    }

    @Test
    @DisplayName("Preserves benign commands without false-positive corruption")
    void preservesBenignCommands() {
        String cmd1 = "git commit -m 'feat: update user authentication and token handling'";
        String cmd2 = "pytest tests/test_payment_gateway.py -v --capture=no";
        String cmd3 = "mvn clean verify -Dtest=SecurityPolicyTest";
        assertThat(SecretRedactor.redact(cmd1)).isEqualTo(cmd1);
        assertThat(SecretRedactor.redact(cmd2)).isEqualTo(cmd2);
        assertThat(SecretRedactor.redact(cmd3)).isEqualTo(cmd3);
        assertThat(SecretRedactor.containsSecret(cmd1)).isFalse();
        assertThat(SecretRedactor.containsSecret(cmd2)).isFalse();
    }
}
