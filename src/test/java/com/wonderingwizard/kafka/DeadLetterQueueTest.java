package com.wonderingwizard.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Dead Letter Queue")
class DeadLetterQueueTest {

    @Test
    @DisplayName("Should store and retrieve entries")
    void storeAndRetrieve() {
        var dlq = new DeadLetterQueue();
        dlq.add("test-topic", 0, 42L, "bad magic byte", new RuntimeException("test"));

        assertEquals(1, dlq.size());
        var entry = dlq.getEntries().getFirst();
        assertEquals("test-topic", entry.topic());
        assertEquals(0, entry.partition());
        assertEquals(42L, entry.offset());
        assertEquals("bad magic byte", entry.errorMessage());
        assertEquals("RuntimeException", entry.exceptionClass());
        assertNotNull(entry.timestamp());
    }

    @Test
    @DisplayName("Should evict oldest entries when at capacity")
    void eviction() {
        var dlq = new DeadLetterQueue(3);
        dlq.add("t", 0, 1L, "err1", new Exception("1"));
        dlq.add("t", 0, 2L, "err2", new Exception("2"));
        dlq.add("t", 0, 3L, "err3", new Exception("3"));
        dlq.add("t", 0, 4L, "err4", new Exception("4"));

        assertEquals(3, dlq.size());
        assertEquals(2L, dlq.getEntries().getFirst().offset());
        assertEquals(4L, dlq.getEntries().getLast().offset());
    }

    @Test
    @DisplayName("Should clear all entries")
    void clear() {
        var dlq = new DeadLetterQueue();
        dlq.add("t", 0, 1L, "err", new Exception());
        dlq.add("t", 0, 2L, "err", new Exception());
        dlq.clear();

        assertEquals(0, dlq.size());
        assertTrue(dlq.getEntries().isEmpty());
    }

    @Test
    @DisplayName("Should handle null cause")
    void nullCause() {
        var dlq = new DeadLetterQueue();
        dlq.add("t", 0, 1L, "err", null);

        assertEquals("Unknown", dlq.getEntries().getFirst().exceptionClass());
    }
}
