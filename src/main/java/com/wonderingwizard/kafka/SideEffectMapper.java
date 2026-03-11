package com.wonderingwizard.kafka;

import com.wonderingwizard.engine.SideEffect;
import org.apache.avro.generic.GenericRecord;

/**
 * Functional interface for mapping an engine {@link SideEffect} to a Kafka Avro {@link GenericRecord}.
 * <p>
 * This is the outbound counterpart to {@link EventMapper}. Each Kafka topic has its own
 * mapper implementation that constructs an Avro record from the side effect's fields.
 *
 * @param <S> the source side effect type
 */
@FunctionalInterface
public interface SideEffectMapper<S extends SideEffect> {

    /**
     * Map an engine side effect to an Avro GenericRecord for publishing to Kafka.
     *
     * @param sideEffect the side effect to map
     * @return the Avro record to publish, or null if this side effect should be skipped
     */
    GenericRecord map(S sideEffect);
}
