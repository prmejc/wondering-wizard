package com.wonderingwizard.simulator;

import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;
import java.util.logging.Logger;

/**
 * Shared Kafka infrastructure: consumers and producers for the simulator.
 */
public class KafkaInfra {

    private static final Logger logger = Logger.getLogger(KafkaInfra.class.getName());

    private final KafkaConsumer<String, GenericRecord> consumer;
    private final KafkaConsumer<String, GenericRecord> wiConsumer;
    private final KafkaProducer<String, String> jsonProducer;
    private final KafkaProducer<String, GenericRecord> avroProducer;

    public KafkaInfra(Properties config) {
        this.consumer = createAvroConsumer(config, config.getProperty("kafka.consumer.group-id", "terminal-simulator"));
        this.wiConsumer = createAvroConsumer(config, config.getProperty("kafka.consumer.wi-group-id", "terminal-simulator-wi"));
        this.jsonProducer = createJsonProducer(config);
        this.avroProducer = createAvroProducer(config);
    }

    public KafkaConsumer<String, GenericRecord> consumer() {
        return consumer;
    }

    public KafkaConsumer<String, GenericRecord> wiConsumer() {
        return wiConsumer;
    }

    public void send(String topic, String key, String json) {
        jsonProducer.send(new ProducerRecord<>(topic, key, json), (metadata, exception) -> {
            if (exception != null) {
                logger.warning("Failed to send JSON to " + topic + ": " + exception.getMessage());
            } else {
                logger.info("Sent JSON to " + topic + " key=" + key
                        + " (partition=" + metadata.partition() + ", offset=" + metadata.offset() + ")");
            }
        });
    }

    public void sendAvro(String topic, String key, GenericRecord record) {
        avroProducer.send(new ProducerRecord<>(topic, key, record), (metadata, exception) -> {
            if (exception != null) {
                logger.warning("Failed to send Avro to " + topic + ": " + exception.getMessage());
            } else {
                logger.info("Sent Avro to " + topic + " key=" + key
                        + " (partition=" + metadata.partition() + ", offset=" + metadata.offset() + ")");
            }
        });
    }

    public void close() {
        consumer.close();
        wiConsumer.close();
        jsonProducer.close();
        avroProducer.close();
    }

    private static KafkaConsumer<String, GenericRecord> createAvroConsumer(Properties config, String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getProperty("kafka.bootstrap-server"));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class.getName());
        props.put(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, config.getProperty("kafka.schema-registry-url"));
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        configureSecurity(props, config);
        return new KafkaConsumer<>(props);
    }

    private static KafkaProducer<String, String> createJsonProducer(Properties config) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getProperty("kafka.bootstrap-server"));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        configureSecurity(props, config);
        return new KafkaProducer<>(props);
    }

    private static KafkaProducer<String, GenericRecord> createAvroProducer(Properties config) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getProperty("kafka.bootstrap-server"));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class.getName());
        props.put(KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, config.getProperty("kafka.schema-registry-url"));
        configureSecurity(props, config);
        return new KafkaProducer<>(props);
    }

    private static void configureSecurity(Properties props, Properties config) {
        String securityProtocol = config.getProperty("kafka.security-protocol");
        if (securityProtocol != null && !securityProtocol.isEmpty()) {
            props.put("security.protocol", securityProtocol);
        }
        String saslMechanism = config.getProperty("kafka.sasl-mechanism");
        if (saslMechanism != null && !saslMechanism.isEmpty()) {
            props.put("sasl.mechanism", saslMechanism);
            String username = config.getProperty("kafka.sasl-username", "");
            String password = config.getProperty("kafka.sasl-password", "");
            props.put("sasl.jaas.config",
                    "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
                            + username + "\" password=\"" + password + "\";");
        }
    }
}
