package com.wonderingwizard.kafka;

import com.wonderingwizard.engine.SideEffect;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generic Kafka publisher that maps engine side effects to Avro records and sends them
 * to configured Kafka topics.
 * <p>
 * This is the outbound counterpart to {@link KafkaEventConsumer}. Side effect mappers
 * are registered per side effect type, each associated with a target topic. When
 * {@link #publish(List)} is called, each side effect is matched against registered
 * mappers and published to the appropriate topic.
 */
public class KafkaSideEffectPublisher {

    private static final Logger logger = Logger.getLogger(KafkaSideEffectPublisher.class.getName());

    private final KafkaConfiguration kafkaConfig;
    private final List<RegisteredMapper<?>> registeredMappers = new ArrayList<>();
    private KafkaProducer<String, GenericRecord> producer;

    public KafkaSideEffectPublisher(KafkaConfiguration kafkaConfig) {
        this.kafkaConfig = kafkaConfig;
    }

    /**
     * Register a mapper for a specific side effect type and target topic.
     *
     * @param sideEffectType the class of side effects this mapper handles
     * @param topic the Kafka topic to publish to
     * @param mapper the mapper that converts side effects to Avro records
     * @param <S> the side effect type
     */
    public <S extends SideEffect> void registerMapper(
            Class<S> sideEffectType,
            String topic,
            SideEffectMapper<S> mapper
    ) {
        registeredMappers.add(new RegisteredMapper<>(sideEffectType, topic, mapper));
        logger.info("Registered side effect mapper for " + sideEffectType.getSimpleName()
                + " → topic: " + topic);
    }

    /**
     * Start the publisher by creating the Kafka producer.
     */
    public void start() {
        Properties props = buildProducerProperties();
        this.producer = new KafkaProducer<>(props);
        logger.info("Started Kafka side effect publisher");
    }

    /**
     * Stop the publisher and close the Kafka producer.
     */
    public void stop() {
        if (producer != null) {
            producer.close();
            logger.info("Stopped Kafka side effect publisher");
        }
    }

    /**
     * Publish a list of side effects to Kafka. Each side effect is matched against
     * registered mappers. Side effects without a matching mapper are ignored.
     *
     * @param sideEffects the side effects to publish
     */
    public void publish(List<SideEffect> sideEffects) {
        for (SideEffect sideEffect : sideEffects) {
            for (RegisteredMapper<?> registered : registeredMappers) {
                if (registered.matches(sideEffect)) {
                    publishSideEffect(registered, sideEffect);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <S extends SideEffect> void publishSideEffect(RegisteredMapper<S> registered, SideEffect sideEffect) {
        try {
            GenericRecord record = registered.mapper().map((S) sideEffect);
            if (record == null) {
                return;
            }
            ProducerRecord<String, GenericRecord> producerRecord =
                    new ProducerRecord<>(registered.topic(), record);
            producer.send(producerRecord, (metadata, exception) -> {
                if (exception != null) {
                    logger.log(Level.SEVERE, "Failed to publish " + sideEffect.getClass().getSimpleName()
                            + " to topic " + registered.topic(), exception);
                } else {
                    logger.fine("Published " + sideEffect.getClass().getSimpleName()
                            + " to topic " + registered.topic()
                            + " [partition=" + metadata.partition()
                            + ", offset=" + metadata.offset() + "]");
                }
            });
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error mapping side effect " + sideEffect.getClass().getSimpleName()
                    + " for topic " + registered.topic(), e);
        }
    }

    Properties buildProducerProperties() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.bootstrapServer());
        props.put(ProducerConfig.CLIENT_ID_CONFIG, kafkaConfig.clientId() + "-producer");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class.getName());
        props.put(KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, kafkaConfig.schemaRegistryUrl());

        // SASL configuration
        if (kafkaConfig.securityProtocol() != null && !kafkaConfig.securityProtocol().isBlank()) {
            props.put("security.protocol", kafkaConfig.securityProtocol());
            props.put("sasl.mechanism", kafkaConfig.saslMechanism());
            props.put("sasl.jaas.config",
                    "org.apache.kafka.common.security.plain.PlainLoginModule required "
                            + "username=\"" + kafkaConfig.saslUsername() + "\" "
                            + "password=\"" + kafkaConfig.saslPassword() + "\";");
        }

        return props;
    }

    private record RegisteredMapper<S extends SideEffect>(
            Class<S> sideEffectType,
            String topic,
            SideEffectMapper<S> mapper
    ) {
        boolean matches(SideEffect sideEffect) {
            return sideEffectType.isInstance(sideEffect);
        }
    }
}
