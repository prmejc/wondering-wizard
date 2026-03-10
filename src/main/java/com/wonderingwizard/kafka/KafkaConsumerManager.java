package com.wonderingwizard.kafka;

import com.wonderingwizard.engine.Engine;
import com.wonderingwizard.engine.Event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages the lifecycle of all Kafka event consumers.
 * <p>
 * Provides a central place to register, start, and stop all Kafka consumers.
 * New topic consumers are added by calling {@link #register} with the appropriate
 * configuration and mapper before calling {@link #startAll()}.
 */
public class KafkaConsumerManager {

    private static final Logger logger = Logger.getLogger(KafkaConsumerManager.class.getName());

    private final KafkaConfiguration kafkaConfig;
    private final Engine engine;
    private final List<KafkaEventConsumer<?>> consumers = new ArrayList<>();

    public KafkaConsumerManager(KafkaConfiguration kafkaConfig, Engine engine) {
        this.kafkaConfig = kafkaConfig;
        this.engine = engine;
    }

    /**
     * Register a new Kafka consumer for a specific topic.
     *
     * @param consumerConfig the consumer-specific configuration (topic, group, etc.)
     * @param mapper the mapper that transforms Avro records into engine events
     * @param <E> the type of engine event
     * @return this manager for fluent chaining
     */
    public <E extends Event> KafkaConsumerManager register(
            ConsumerConfiguration consumerConfig,
            EventMapper<E> mapper
    ) {
        var consumer = new KafkaEventConsumer<>(kafkaConfig, consumerConfig, mapper, engine);
        consumers.add(consumer);
        logger.info("Registered Kafka consumer for topic: " + consumerConfig.topic());
        return this;
    }

    /**
     * Start all registered consumers.
     */
    public void startAll() {
        logger.info("Starting " + consumers.size() + " Kafka consumer(s)...");
        for (var consumer : consumers) {
            consumer.start();
        }
    }

    /**
     * Stop all running consumers.
     */
    public void stopAll() {
        logger.info("Stopping " + consumers.size() + " Kafka consumer(s)...");
        for (var consumer : consumers) {
            consumer.stop();
        }
    }

    /**
     * Returns an unmodifiable view of the registered consumers.
     */
    public List<KafkaEventConsumer<?>> getConsumers() {
        return Collections.unmodifiableList(consumers);
    }
}
