package com.wonderingwizard.kafka;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory dead letter queue that stores messages that failed to be
 * deserialized or processed by Kafka consumers.
 * <p>
 * Thread-safe — multiple consumer threads can add entries concurrently.
 * Entries are kept in arrival order with a configurable maximum size;
 * the oldest entries are evicted when the limit is reached.
 */
public class DeadLetterQueue {

    private static final int DEFAULT_MAX_SIZE = 1000;

    private final int maxSize;
    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();

    /**
     * A single DLQ entry representing a failed message.
     *
     * @param topic the Kafka topic the message was consumed from
     * @param partition the partition number
     * @param offset the offset within the partition
     * @param timestamp when the failure occurred
     * @param errorMessage the error description
     * @param exceptionClass the exception class name
     */
    public record Entry(
            String topic,
            int partition,
            long offset,
            Instant timestamp,
            String errorMessage,
            String exceptionClass
    ) {}

    public DeadLetterQueue() {
        this(DEFAULT_MAX_SIZE);
    }

    public DeadLetterQueue(int maxSize) {
        this.maxSize = maxSize;
    }

    /**
     * Adds a failed message entry to the DLQ.
     * If the queue is at capacity, the oldest entry is evicted.
     */
    public void add(String topic, int partition, long offset, String errorMessage, Throwable cause) {
        String exceptionClass = cause != null ? cause.getClass().getSimpleName() : "Unknown";
        Entry entry = new Entry(topic, partition, offset, Instant.now(), errorMessage, exceptionClass);
        entries.add(entry);
        // Evict oldest entries if over capacity
        while (entries.size() > maxSize) {
            entries.remove(0);
        }
    }

    /**
     * Returns an unmodifiable snapshot of all DLQ entries.
     */
    public List<Entry> getEntries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /**
     * Returns the number of entries in the DLQ.
     */
    public int size() {
        return entries.size();
    }

    /**
     * Clears all entries from the DLQ.
     */
    public void clear() {
        entries.clear();
    }
}
