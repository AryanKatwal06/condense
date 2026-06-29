package com.condense.core;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TrackingRepositoryTest {

    @Inject
    TrackingRepository repo;

    @Test
    @Order(1)
    void schemaIsCreatedOnFirstAccess() {
        // countAll() triggers schema creation
        long count = repo.countAll();
        assertThat(count).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @Order(2)
    void insertIncreasesCount() {
        long before = repo.countAll();
        repo.insert("git status", "abc123def456", "/tmp/project", 500, 100, 42L);
        long after = repo.countAll();
        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    @Order(3)
    void insertWithNullProjectDoesNotThrow() {
        long before = repo.countAll();
        repo.insert("ls -la", null, "/tmp", 200, 50, 5L);
        long after = repo.countAll();
        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    @Order(4)
    void insertNeverThrowsEvenWithInvalidData() {
        // Should log WARN, not throw
        org.assertj.core.api.Assertions.assertThatCode(
            () -> repo.insert("", null, null, -1, -1, -1L)
        ).doesNotThrowAnyException();
    }
}
