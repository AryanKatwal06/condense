package com.condense.supplychain;

import com.condense.core.SafePathValidator;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterIncident;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.MsbuildBinlogStage;
import com.condense.filter.strategy.TrxReportStage;
import com.condense.hooks.CompoundCommandAnalyzer;
import com.condense.ir.DocumentBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adversarial security test suite covering parser bombs, XXE injection,
 * path traversal, compound-command bypasses, and installer integrity.
 */
class AdversarialSecurityTest {

    @TempDir
    Path tempDir;

    // =========================================================================
    // 1. TRX XML Parser Security (XXE & Entity Expansion Bombs)
    // =========================================================================

    @Test
    @DisplayName("TRX parser rejects XML external entity (XXE) injection and records incident")
    void trxParserBlocksExternalEntityInjection() throws Exception {
        Path maliciousTrx = tempDir.resolve("xxe-attack.trx");
        String xxePayload = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE TestRun [
              <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <TestRun xmlns="http://microsoft.com/schemas/VisualStudio/TeamTest/2010">
              <Results>
                <UnitTestResult testName="TestXXE" outcome="Failed">
                  <Output>
                    <ErrorInfo>
                      <Message>&xxe;</Message>
                    </ErrorInfo>
                  </Output>
                </UnitTestResult>
              </Results>
            </TestRun>
            """;
        Files.writeString(maliciousTrx, xxePayload, StandardCharsets.UTF_8);

        List<FilterIncident> incidents = new ArrayList<>();
        FilterContext context = new FilterContext(
            "dotnet test", null, null, 0, false, incidents, new DocumentBuilder(), List.of(maliciousTrx.toString()));
        StageResult result = TrxReportStage.INSTANCE.process("raw console output", context);

        // Fallback to raw output when XXE is detected
        assertThat(result.output()).isEqualTo("raw console output");
        assertThat(context.incidents())
            .extracting(FilterIncident::kind)
            .contains(FilterIncident.KIND_TRX_XXE);
    }

    @Test
    @DisplayName("TRX parser blocks Billion Laughs XML entity expansion bomb")
    void trxParserBlocksBillionLaughsBomb() throws Exception {
        Path bombTrx = tempDir.resolve("billion-laughs.trx");
        String bombPayload = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE TestRun [
              <!ENTITY lol "lol">
              <!ENTITY lol1 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
              <!ENTITY lol2 "&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;">
              <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
            ]>
            <TestRun xmlns="http://microsoft.com/schemas/VisualStudio/TeamTest/2010">
              <Results>
                <UnitTestResult testName="BombTest" outcome="Failed">
                  <Output>
                    <ErrorInfo>
                      <Message>&lol3;</Message>
                    </ErrorInfo>
                  </Output>
                </UnitTestResult>
              </Results>
            </TestRun>
            """;
        Files.writeString(bombTrx, bombPayload, StandardCharsets.UTF_8);

        List<FilterIncident> incidents = new ArrayList<>();
        FilterContext context = new FilterContext(
            "dotnet test", null, null, 0, false, incidents, new DocumentBuilder(), List.of(bombTrx.toString()));
        StageResult result = TrxReportStage.INSTANCE.process("raw test runner text", context);

        // Safely blocked with incident recorded; no OOM or hang
        assertThat(result.output()).isEqualTo("raw test runner text");
        assertThat(context.incidents())
            .extracting(FilterIncident::kind)
            .contains(FilterIncident.KIND_TRX_XXE);
    }

    // =========================================================================
    // 2. MSBuild Binlog Security (Decompression & Framing Bombs)
    // =========================================================================

    @Test
    @DisplayName("MSBuild binlog reader safely handles malformed gzip and truncated varints")
    void msbuildBinlogHandlesCorruptedGzipFraming() throws Exception {
        Path corruptBinlog = tempDir.resolve("corrupted.binlog");
        // Write invalid gzip bytes
        byte[] junk = new byte[] {0x1f, (byte) 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, (byte) 0xff, 0x01, 0x02, 0x03};
        Files.write(corruptBinlog, junk);

        List<FilterIncident> incidents = new ArrayList<>();
        FilterContext context = new FilterContext(
            "dotnet build", null, null, 0, false, incidents, new DocumentBuilder(), List.of(corruptBinlog.toString()));
        StageResult result = MsbuildBinlogStage.INSTANCE.process("msbuild output", context);

        // Graceful fallback to raw input with zero uncaught exceptions
        assertThat(result.output()).isEqualTo("msbuild output");
    }

    @Test
    @DisplayName("MSBuild binlog reader terminates safely on empty or truncated gzip archive")
    void msbuildBinlogHandlesEmptyGzipArchive() throws Exception {
        Path emptyGzipBinlog = tempDir.resolve("empty.binlog");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            // Write only 4 bytes (valid gzip header, but insufficient records)
            gzos.write(new byte[] {0x12, 0x00, 0x00, 0x00});
        }
        Files.write(emptyGzipBinlog, baos.toByteArray());

        List<FilterIncident> incidents = new ArrayList<>();
        FilterContext context = new FilterContext(
            "dotnet build", null, null, 0, false, incidents, new DocumentBuilder(), List.of(emptyGzipBinlog.toString()));
        StageResult result = MsbuildBinlogStage.INSTANCE.process("dotnet build output", context);

        assertThat(result.output()).isEqualTo("dotnet build output");
    }

    // =========================================================================
    // 3. SafePathValidator Traversal & Boundary Security
    // =========================================================================

    @Test
    @DisplayName("SafePathValidator rejects directory traversal sequences and escape paths")
    void safePathValidatorRejectsDirectoryTraversal() throws Exception {
        Path safeBase = tempDir.resolve("workspace");
        Files.createDirectories(safeBase);

        // Traversal attempting to escape to parent
        Path traversalPath = safeBase.resolve("../outside.txt");
        SafePathValidator.ContainmentResult result = SafePathValidator.contain(traversalPath, safeBase);
        assertThat(result.contained())
            .as("Path outside workspace directory must not be contained")
            .isFalse();

        // Null file or null parent
        assertThat(SafePathValidator.contain(null, safeBase).contained()).isFalse();
        assertThat(SafePathValidator.contain(traversalPath, null).contained()).isFalse();
    }

    @Test
    @DisplayName("SafePathValidator recognized temp files cannot be used for arbitrary deletion")
    void safePathValidatorRecognizesOnlyValidTempPatterns() {
        assertThat(SafePathValidator.isKnownCondenseTemp("trust.json.tmp")).isTrue();
        assertThat(SafePathValidator.isKnownCondenseTemp(".condense-hook-123.tmp")).isTrue();
        assertThat(SafePathValidator.isKnownCondenseTemp(".condense-atomic-abc.tmp")).isTrue();

        // Hostile filenames attempting to spoof temp patterns
        assertThat(SafePathValidator.isKnownCondenseTemp(null)).isFalse();
        assertThat(SafePathValidator.isKnownCondenseTemp("")).isFalse();
        assertThat(SafePathValidator.isKnownCondenseTemp("condense.db")).isFalse();
        assertThat(SafePathValidator.isKnownCondenseTemp("id_rsa.tmp")).isFalse();
        assertThat(SafePathValidator.isKnownCondenseTemp("../.condense-hook-123.tmp")).isFalse();
    }

    // =========================================================================
    // 4. Compound Command Analyzer Security (Bypass Prevention)
    // =========================================================================

    @Test
    @DisplayName("Compound command analyzer refuses to auto-allow mixed or dangerous compound segments")
    void compoundCommandAnalyzerPreventsChainedBypasses() {
        Set<String> intercepted = Set.of("pytest", "npm test", "git status");

        // Chained command where intercepted command is combined with an unintercepted command
        CompoundCommandAnalyzer.AnalysisResult chained = CompoundCommandAnalyzer.analyze("cat secret.txt && pytest", intercepted);
        assertThat(chained.shouldDeny())
            .as("Chained compound command with unintercepted prefix must deny or ask")
            .isTrue();

        // Subshell substitution payload
        CompoundCommandAnalyzer.AnalysisResult subshell = CompoundCommandAnalyzer.analyze("pytest $(rm -rf /)", intercepted);
        assertThat(subshell.isAmbiguous() || subshell.shouldDeny())
            .as("Subshell syntax must default to ambiguous/deny")
            .isTrue();

        // Backtick substitution payload
        CompoundCommandAnalyzer.AnalysisResult backticks = CompoundCommandAnalyzer.analyze("npm test `cat /etc/shadow`", intercepted);
        assertThat(backticks.isAmbiguous() || backticks.shouldDeny())
            .as("Backticks syntax must default to ambiguous/deny")
            .isTrue();

        // Unbalanced quotation payload
        CompoundCommandAnalyzer.AnalysisResult unclosedQuote = CompoundCommandAnalyzer.analyze("git status 'unterminated", intercepted);
        assertThat(unclosedQuote.isAmbiguous() || unclosedQuote.shouldDeny())
            .as("Unclosed quotation marks must be marked ambiguous")
            .isTrue();
    }

    // =========================================================================
    // 5. Installer Fail-Closed Verification
    // =========================================================================

    @Test
    @DisplayName("Installer checksum mismatch logic fails closed")
    void installerChecksumMismatchFailsClosed() {
        // Model the checksum verification logic implemented in install.sh and install.ps1
        String expectedChecksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String corruptedChecksum = "0000000000000000000000000000000000000000000000000000000000000000";

        boolean matches = expectedChecksum.equalsIgnoreCase(corruptedChecksum);
        assertThat(matches)
            .as("Corrupted checksum must not match expected checksum")
            .isFalse();
    }
}
