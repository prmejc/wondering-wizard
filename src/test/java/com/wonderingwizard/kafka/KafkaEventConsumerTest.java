package com.wonderingwizard.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class KafkaEventConsumerTest {

    @Test
    void shouldBuildConsumerPropertiesWithSasl() {
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
                "test.topic.v1",
                "test-group-v1",
                "com.test.AvroMessage",
                null,
                false
        );

        var consumer = new KafkaEventConsumer<>(kafkaConfig, consumerConfig, r -> null, null);
        Properties props = consumer.buildConsumerProperties();

        assertEquals("kafka-broker:9094", props.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertEquals("test-client", props.get(ConsumerConfig.CLIENT_ID_CONFIG));
        assertEquals("test-group-v1", props.get(ConsumerConfig.GROUP_ID_CONFIG));
        assertEquals("http://schema-registry:8081", props.get(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG));
        assertEquals("latest", props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
        assertEquals("SaslPlaintext", props.get("security.protocol"));
        assertEquals("Plain", props.get("sasl.mechanism"));
        assertTrue(props.get("sasl.jaas.config").toString().contains("username=\"admin\""));
        assertTrue(props.get("sasl.jaas.config").toString().contains("password=\"admin-secret\""));
    }

    @Test
    void shouldSetAutoOffsetResetToEarliestWhenReadAllAtStartup() {
        var kafkaConfig = new KafkaConfiguration(
                "kafka-broker:9094", "client", "http://sr:8081",
                null, null, null, null
        );

        var consumerConfig = new ConsumerConfiguration(
                "test.topic.v1", "group-v1", "com.test.Avro", null, true
        );

        var consumer = new KafkaEventConsumer<>(kafkaConfig, consumerConfig, r -> null, null);
        Properties props = consumer.buildConsumerProperties();

        assertEquals("earliest", props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
    }

    @Test
    void shouldNotSetSaslWhenSecurityProtocolIsNull() {
        var kafkaConfig = new KafkaConfiguration(
                "kafka-broker:9094", "client", "http://sr:8081",
                null, null, null, null
        );

        var consumerConfig = new ConsumerConfiguration(
                "test.topic.v1", "group-v1", "com.test.Avro", null, false
        );

        var consumer = new KafkaEventConsumer<>(kafkaConfig, consumerConfig, r -> null, null);
        Properties props = consumer.buildConsumerProperties();

        assertNull(props.get("security.protocol"));
        assertNull(props.get("sasl.mechanism"));
        assertNull(props.get("sasl.jaas.config"));
    }
}
