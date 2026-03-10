package com.wonderingwizard.kafka;

import com.wonderingwizard.engine.Event;
import org.apache.avro.generic.GenericRecord;

/**
 * Functional interface for mapping a Kafka Avro {@link GenericRecord} to an engine {@link Event}.
 * <p>
 * Each Kafka topic has its own mapper implementation that extracts the relevant
 * fields from the Avro record and constructs the appropriate engine event.
 *
 * @param <E> the target event type
 */
@FunctionalInterface
public interface EventMapper<E extends Event> {

    /**
     * Map an Avro GenericRecord consumed from Kafka to an engine event.
     *
     * @param record the Avro record from Kafka
     * @return the mapped engine event
     */
    E map(GenericRecord record);
}
