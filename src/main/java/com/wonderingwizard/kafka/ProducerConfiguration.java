package com.wonderingwizard.kafka;

/**
 * Configuration for a single Kafka producer (one per topic).
 *
 * @param topic the Kafka topic to publish to
 */
public record ProducerConfiguration(
        String topic
) {
}
