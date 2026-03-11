package com.wonderingwizard.kafka;

import com.wonderingwizard.engine.Engine;
import com.wonderingwizard.engine.EventProcessingEngine;
import com.wonderingwizard.events.WorkQueueMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KafkaConsumerManagerTest {

    @Test
    void shouldRegisterConsumers() {
        var kafkaConfig = new KafkaConfiguration(
                "kafka-broker:9094", "client", "http://sr:8081",
                "Plain", "admin", "secret", "SaslPlaintext"
        );
        Engine engine = new EventProcessingEngine();

        var manager = new KafkaConsumerManager(kafkaConfig, engine);

        var workQueueConfig = new ConsumerConfiguration(
                "APMT.terminalOperations.workQueue.topic.confidential.dedicated.v1",
                "apmt.tc1.sit.terminal-operations.flow-tone-work-queue.consumergroup.v1",
                "APMT.terminalOperations.workQueue.topic.confidential.dedicated.v1.WorkQueue",
                null,
                false
        );

        manager.register(workQueueConfig, new WorkQueueEventMapper());

        assertEquals(1, manager.getConsumers().size());
    }

    @Test
    void shouldSupportFluentRegistration() {
        var kafkaConfig = new KafkaConfiguration(
                "kafka-broker:9094", "client", "http://sr:8081",
                null, null, null, null
        );
        Engine engine = new EventProcessingEngine();

        var manager = new KafkaConsumerManager(kafkaConfig, engine);

        var config1 = new ConsumerConfiguration("topic1", "group1", "Avro1", null, false);
        var config2 = new ConsumerConfiguration("topic2", "group2", "Avro2", null, true);

        manager
                .register(config1, r -> null)
                .register(config2, r -> null);

        assertEquals(2, manager.getConsumers().size());
    }

    @Test
    void shouldReturnUnmodifiableConsumerList() {
        var kafkaConfig = new KafkaConfiguration(
                "kafka-broker:9094", "client", "http://sr:8081",
                null, null, null, null
        );
        Engine engine = new EventProcessingEngine();
        var manager = new KafkaConsumerManager(kafkaConfig, engine);

        assertThrows(UnsupportedOperationException.class, () ->
                manager.getConsumers().add(null)
        );
    }

    @Test
    void shouldRegisterJsonConsumers() {
        var kafkaConfig = new KafkaConfiguration(
                "kafka-broker:9094", "client", "http://sr:8081",
                null, null, null, null
        );
        Engine engine = new EventProcessingEngine();
        var manager = new KafkaConsumerManager(kafkaConfig, engine);

        var jsonConfig = new ConsumerConfiguration(
                "test.json.topic.v1", "group-v1", null, "AssetEvent", false
        );

        manager.registerJson(jsonConfig, json -> null);

        assertEquals(0, manager.getConsumers().size());
        assertEquals(1, manager.getJsonConsumers().size());
    }

    @Test
    void shouldSupportMixedAvroAndJsonRegistration() {
        var kafkaConfig = new KafkaConfiguration(
                "kafka-broker:9094", "client", "http://sr:8081",
                null, null, null, null
        );
        Engine engine = new EventProcessingEngine();
        var manager = new KafkaConsumerManager(kafkaConfig, engine);

        var avroConfig = new ConsumerConfiguration("avro.topic", "group1", "Avro1", null, false);
        var jsonConfig = new ConsumerConfiguration("json.topic", "group2", null, "JsonType", false);

        manager.register(avroConfig, r -> null)
                .registerJson(jsonConfig, json -> null);

        assertEquals(1, manager.getConsumers().size());
        assertEquals(1, manager.getJsonConsumers().size());
    }

    @Test
    void shouldReturnUnmodifiableJsonConsumerList() {
        var kafkaConfig = new KafkaConfiguration(
                "kafka-broker:9094", "client", "http://sr:8081",
                null, null, null, null
        );
        Engine engine = new EventProcessingEngine();
        var manager = new KafkaConsumerManager(kafkaConfig, engine);

        assertThrows(UnsupportedOperationException.class, () ->
                manager.getJsonConsumers().add(null)
        );
    }
}
