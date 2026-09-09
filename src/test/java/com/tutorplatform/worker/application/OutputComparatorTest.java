package com.tutorplatform.worker.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutputComparatorTest {
    private final OutputComparator comparator = new OutputComparator();

    @Test
    void exactEqualPasses() {
        assertThat(comparator.matches("hello\n", "hello\n", ComparisonMode.EXACT)).isTrue();
    }

    @Test
    void exactWhitespaceDifferenceFails() {
        assertThat(comparator.matches("hello", "hello\n", ComparisonMode.EXACT)).isFalse();
    }

    @Test
    void normalizedLineEndingsPass() {
        assertThat(comparator.matches("one\r\ntwo\r", "one\ntwo\n", ComparisonMode.NORMALIZED)).isTrue();
    }

    @Test
    void normalizedTrailingWhitespacePasses() {
        assertThat(comparator.matches("one  \n two\t", "one\n two", ComparisonMode.NORMALIZED)).isTrue();
    }

    @Test
    void normalizedMeaningfulDifferenceFails() {
        assertThat(comparator.matches("answer", "Answer", ComparisonMode.NORMALIZED)).isFalse();
    }
}
