package com.wonderingwizard.kafka;

import com.wonderingwizard.engine.Event;

/**
 * Functional interface for mapping a Kafka JSON message (as a raw string) to an engine {@link Event}.
 * <p>
 * This is the JSON counterpart to {@link EventMapper}, which handles Avro records.
 * Each JSON Kafka topic has its own mapper implementation that parses the JSON
 * and constructs the appropriate engine event.
 *
 * @param <E> the target event type
 */
@FunctionalInterface
public interface JsonEventMapper<E extends Event> {

    /**
     * Map a JSON string consumed from Kafka to an engine event.
     *
     * @param json the raw JSON string from Kafka
     * @return the mapped engine event
     */
    E map(String json);
}
