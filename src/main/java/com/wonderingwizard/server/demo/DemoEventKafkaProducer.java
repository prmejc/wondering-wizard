package com.wonderingwizard.server.demo;

import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.server.Settings;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;
import java.util.logging.Logger;

/**
 * Sends work instruction events to Kafka (Avro).
 * The existing Kafka consumer picks them up and processes through the engine.
 * Used when Kafka is enabled.
 */
public class DemoEventKafkaProducer implements DemoEventProducer {

    private static final Logger logger = Logger.getLogger(DemoEventKafkaProducer.class.getName());

    private final KafkaProducer<String, GenericRecord> producer;
    private final WorkInstructionAvroMapper mapper;
    private final String wiTopic;

    public DemoEventKafkaProducer(Settings settings) {
        this.mapper = new WorkInstructionAvroMapper();
        this.wiTopic = settings.workInstructionConsumerConfiguration().topic();
        this.producer = createProducer(settings);
        logger.info("DemoEventKafkaProducer initialized — publishing WI events to " + wiTopic);
    }

    @Override
    public void sendWorkInstruction(WorkInstructionEvent event) {
        GenericRecord record = mapper.toAvro(event);
        String key = String.valueOf(event.workInstructionId());
        producer.send(new ProducerRecord<>(wiTopic, key, record), (metadata, exception) -> {
            if (exception != null) {
                logger.warning("Failed to send WI to Kafka: " + exception.getMessage());
            } else {
                logger.info("Sent WI " + event.workInstructionId() + " to " + wiTopic
                        + " (partition=" + metadata.partition() + ", offset=" + metadata.offset() + ")");
            }
        });
    }

    public void close() {
        producer.close();
    }

    private static KafkaProducer<String, GenericRecord> createProducer(Settings settings) {
        var kafkaConfig = settings.kafkaConfiguration();
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.bootstrapServer());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class.getName());
        props.put(KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, kafkaConfig.schemaRegistryUrl());

        if (kafkaConfig.securityProtocol() != null && !kafkaConfig.securityProtocol().isEmpty()) {
            props.put("security.protocol", kafkaConfig.securityProtocol());
        }
        if (kafkaConfig.saslMechanism() != null && !kafkaConfig.saslMechanism().isEmpty()) {
            props.put("sasl.mechanism", kafkaConfig.saslMechanism());
            props.put("sasl.jaas.config",
                    "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
                            + kafkaConfig.saslUsername() + "\" password=\"" + kafkaConfig.saslPassword() + "\";");
        }

        return new KafkaProducer<>(props);
    }
}
