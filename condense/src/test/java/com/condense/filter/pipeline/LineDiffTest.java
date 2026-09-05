package com.condense.filter.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LineDiffTest {

    @Test
    void emptyStringIsOneEmptyLine() {
        LineDiff diff = LineDiff.of("", "");
        assertThat(diff.inputLines()).isEqualTo(1);
        assertThat(diff.outputLines()).isEqualTo(1);
        assertThat(diff.keptLines()).isEqualTo(1);
        assertThat(diff.droppedLines()).isZero();
        assertThat(diff.addedLines()).isZero();
    }

    @Test
    void trailingNewlineCountsTheEmptyLastLine() {
        LineDiff diff = LineDiff.of("hello\n", "hello\n");
        assertThat(LineDiff.split("hello\n")).containsExactly("hello", "");
        assertThat(diff.keptLines()).isEqualTo(2);
        assertThat(diff.droppedLines()).isZero();
    }

    @Test
    void crlfAndLfAreTheSameSplit() {
        assertThat(LineDiff.split("a\r\nb")).containsExactly("a", "b");
        assertThat(LineDiff.split("a\nb")).containsExactly("a", "b");
    }

    @Test
    void duplicateLinesUseMultisetCounts() {
        LineDiff diff = LineDiff.of("a\na\nb", "a\nb");
        assertThat(diff.keptLines()).isEqualTo(2);
        assertThat(diff.dropped()).containsExactly("a");
        assertThat(diff.added()).isEmpty();
        assertIdentity(diff);
    }

    @Test
    void changedLineIsOneDropAndOneAdd() {
        LineDiff diff = LineDiff.of("error: foo", "ERROR foo");
        assertThat(diff.dropped()).containsExactly("error: foo");
        assertThat(diff.added()).containsExactly("ERROR foo");
        assertThat(diff.keptLines()).isZero();
        assertIdentity(diff);
    }

    @Test
    void identitiesHoldForAMix() {
        LineDiff diff = LineDiff.of("keep\ndrop\nkeep", "keep\nadded\nkeep");
        assertThat(diff.dropped()).containsExactly("drop");
        assertThat(diff.added()).containsExactly("added");
        assertIdentity(diff);
    }

    private static void assertIdentity(LineDiff diff) {
        assertThat(diff.keptLines() + diff.droppedLines()).isEqualTo(diff.inputLines());
        assertThat(diff.keptLines() + diff.addedLines()).isEqualTo(diff.outputLines());
    }
}
