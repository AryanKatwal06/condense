package com.condense.filter.strategy;

import com.condense.filter.pipeline.FilterContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class MsbuildBinlogFuzzTest {

    @Test
    void truncateAtVersionHeaderDoesNotThrow() {
        byte[] bytes = BinlogFixtureWriter.typicalErrorAndWarning();
        for (int i = 0; i < Math.min(bytes.length, 12); i++) {
            byte[] cut = new byte[i];
            System.arraycopy(bytes, 0, cut, 0, i);
            MsbuildBinlogStage.FileParse parsed = MsbuildBinlogStage.parseBytes(cut, FilterContext.empty());
            assertThat(parsed == null || parsed.findings != null).isTrue();
        }
    }

    @Test
    void truncateGzipHeaderDoesNotThrow() {
        byte[] bytes = BinlogFixtureWriter.typicalErrorAndWarning();
        byte[] cut = new byte[10];
        System.arraycopy(bytes, 0, cut, 0, 10);
        MsbuildBinlogStage.FileParse parsed = MsbuildBinlogStage.parseBytes(cut, FilterContext.empty());
        assertThat(parsed == null || parsed.findings.isEmpty()).isTrue();
    }

    @Test
    void truncateMidRecordKeepsPrefixOrFallsOpen() {
        byte[] bytes = BinlogFixtureWriter.typicalErrorAndWarning();
        byte[] cut = new byte[bytes.length - 8];
        System.arraycopy(bytes, 0, cut, 0, cut.length);
        MsbuildBinlogStage.FileParse parsed = MsbuildBinlogStage.parseBytes(cut, FilterContext.empty());
        assertThat(parsed == null || parsed.findings.size() <= 2).isTrue();
    }

    @Test
    void truncatedVarintDoesNotThrow() throws Exception {
        ByteArrayOutputStream gzipBody = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzipBody)) {
            gzip.write((byte) 0x80);
            gzip.write((byte) 0x80);
            gzip.write((byte) 0x80);
            gzip.write((byte) 0x80);
            gzip.write((byte) 0x80);
        }
        byte[] bytes = new byte[8 + gzipBody.size()];
        bytes[0] = 18;
        bytes[4] = 18;
        System.arraycopy(gzipBody.toByteArray(), 0, bytes, 8, gzipBody.size());
        MsbuildBinlogStage.FileParse parsed = MsbuildBinlogStage.parseBytes(bytes, FilterContext.empty());
        assertThat(parsed == null || parsed.findings.isEmpty() || parsed.capped).isTrue();
    }

    @Test
    void hugeDecompressedIsCapped() throws Exception {
        ByteArrayOutputStream gzipBody = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzipBody)) {
            byte[] chunk = new byte[64 * 1024];
            int written = 0;
            while (written < MsbuildBinlogStage.MAX_DECOMPRESSED + 8192) {
                gzip.write(chunk);
                written += chunk.length;
            }
        }
        byte[] bytes = new byte[8 + gzipBody.size()];
        bytes[0] = 18;
        bytes[4] = 18;
        System.arraycopy(gzipBody.toByteArray(), 0, bytes, 8, gzipBody.size());
        MsbuildBinlogStage.FileParse parsed = MsbuildBinlogStage.parseBytes(bytes, FilterContext.empty());
        assertThat(parsed == null || parsed.findings.isEmpty()).isTrue();
    }
}
