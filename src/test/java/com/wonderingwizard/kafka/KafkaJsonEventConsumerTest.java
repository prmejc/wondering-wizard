package com.wonderingwizard.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class KafkaJsonEventConsumerTest {

    @Test
    void shouldBuildConsumerPropertiesWithStringDeserializer() {
        var kafkaConfig = new KafkaConfiguration(
                "kafka-broker:9094",
                "test-client",
                "http://schema-registry:8081",
                "Plain",
                "admin",
                "admin-secret",
                "SaslPlaintext"
        );

        var consumerConfig = new ConsumerConfiguration(
                "test.json.topic.v1",
                "test-json-group-v1",
                null,
                "AssetEvent",
                false
        );

        var consumer = new KafkaJsonEventConsumer<>(kafkaConfig, consumerConfig, json -> null, null);
        Properties props = consumer.buildConsumerProperties();

        assertEquals("kafka-broker:9094", props.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertEquals("test-client", props.get(ConsumerConfig.CLIENT_ID_CONFIG));
        assertEquals("test-json-group-v1", props.get(ConsumerConfig.GROUP_ID_CONFIG));
        assertEquals(StringDeserializer.class.getName(), props.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG));
        assertEquals(StringDeserializer.class.getName(), props.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG));
        assertEquals("latest", props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
    }

    @Test
    void shouldNotContainSchemaRegistryConfig() {
        var kafkaConfig = new KafkaConfiguration(
                "kafka-broker:9094", "client", "http://sr:8081",
                null, null, null, null
        );

        var consumerConfig = new ConsumerConfiguration(
                "test.json.topic.v1", "group-v1", null, "AssetEvent", false
        );

        var consumer = new KafkaJsonEventConsumer<>(kafkaConfig, consumerConfig, json -> null, null);
        Properties props = consumer.buildConsumerProperties();

        assertNull(props.get("schema.registry.url"));
    }

    @Test
    void shouldSetAutoOffsetResetToEarliestWhenReadAllAtStartup() {
        var kafkaConfig = new KafkaConfiguration(
                "kafka-broker:9094", "client", "http://sr:8081",
                null, null, null, null
        );

        var consumerConfig = new ConsumerConfiguration(
                "test.json.topic.v1", "group-v1", null, "AssetEvent", true
        );

        var consumer = new KafkaJsonEventConsumer<>(kafkaConfig, consumerConfig, json -> null, null);
        Properties props = consumer.buildConsumerProperties();

        assertEquals("earliest", props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
    }

    @Test
    void shouldSetSaslWhenSecurityProtocolIsConfigured() {
        var kafkaConfig = new KafkaConfiguration(
                "kafka-broker:9094", "client", "http://sr:8081",
                "Plain", "admin", "secret", "SaslPlaintext"
        );

        var consumerConfig = new ConsumerConfiguration(
                "test.json.topic.v1", "group-v1", null, "AssetEvent", false
        );

        var consumer = new KafkaJsonEventConsumer<>(kafkaConfig, consumerConfig, json -> null, null);
        Properties props = consumer.buildConsumerProperties();

        assertEquals("SaslPlaintext", props.get("security.protocol"));
        assertEquals("Plain", props.get("sasl.mechanism"));
        assertTrue(props.get("sasl.jaas.config").toString().contains("username=\"admin\""));
    }
}
