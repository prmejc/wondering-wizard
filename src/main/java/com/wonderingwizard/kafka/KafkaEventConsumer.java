package com.wonderingwizard.kafka;

import com.wonderingwizard.engine.Engine;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.metrics.Metrics;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RecordDeserializationException;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generic Kafka consumer that polls messages from a topic, maps them to engine events,
 * and feeds them into the event processing engine.
 * <p>
 * Each instance consumes from a single topic using the provided {@link EventMapper}
 * to transform Avro records into engine events. Runs on a platform thread
 * because the Kafka client uses {@code synchronized} blocks that pin virtual threads.
 * <p>
 * Messages that fail deserialization (e.g. schema mismatch) are sent to the
 * {@link DeadLetterQueue} and skipped so consumption can continue.
 *
 * @param <E> the type of engine event produced by this consumer
 */
public class KafkaEventConsumer<E extends Event> {

    private static final Logger logger = Logger.getLogger(KafkaEventConsumer.class.getName());
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);

    private final KafkaConfiguration kafkaConfig;
    private final ConsumerConfiguration consumerConfig;
    private final EventMapper<E> mapper;
    private final Engine engine;
    private final Metrics metrics;
    private final DeadLetterQueue deadLetterQueue;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean ready;
    private Thread consumerThread;

    public KafkaEventConsumer(
            KafkaConfiguration kafkaConfig,
            ConsumerConfiguration consumerConfig,
            EventMapper<E> mapper,
            Engine engine
    ) {
        this(kafkaConfig, consumerConfig, mapper, engine, null, null);
    }

    public KafkaEventConsumer(
            KafkaConfiguration kafkaConfig,
            ConsumerConfiguration consumerConfig,
            EventMapper<E> mapper,
            Engine engine,
            Metrics metrics
    ) {
        this(kafkaConfig, consumerConfig, mapper, engine, metrics, null);
    }

    public KafkaEventConsumer(
            KafkaConfiguration kafkaConfig,
            ConsumerConfiguration consumerConfig,
            EventMapper<E> mapper,
            Engine engine,
            Metrics metrics,
            DeadLetterQueue deadLetterQueue
    ) {
        this.kafkaConfig = kafkaConfig;
        this.consumerConfig = consumerConfig;
        this.mapper = mapper;
        this.engine = engine;
        this.metrics = metrics;
        this.deadLetterQueue = deadLetterQueue;
    }

    /**
     * Start consuming messages on a daemon platform thread.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            consumerThread = Thread.ofPlatform()
                    .daemon()
                    .name("kafka-consumer-" + consumerConfig.topic())
                    .start(this::consumeLoop);
            logger.info("Started Kafka consumer for topic: " + consumerConfig.topic());
        }
    }

    /**
     * Stop the consumer gracefully.
     */
    public void stop() {
        running.set(false);
        if (consumerThread != null) {
            consumerThread.interrupt();
            logger.info("Stopped Kafka consumer for topic: " + consumerConfig.topic());
        }
    }

    /**
     * Returns whether this consumer is currently running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Returns whether this consumer has completed its first successful poll
     * (i.e., partition assignment is done and it is actively consuming).
     */
    public boolean isReady() {
        return ready;
    }

    private void consumeLoop() {
        Properties props = buildConsumerProperties();

        try (var consumer = new KafkaConsumer<String, GenericRecord>(props)) {
            consumer.subscribe(List.of(consumerConfig.topic()));
            logger.info("Subscribed to topic: " + consumerConfig.topic()
                    + " with group: " + consumerConfig.groupId());

            while (running.get()) {
                try {
                    ConsumerRecords<String, GenericRecord> records = consumer.poll(POLL_TIMEOUT);
                    if (!ready) {
                        ready = true;
                        logger.info("Consumer ready for topic: " + consumerConfig.topic());
                    }
                    for (var record : records) {
                        processRecord(record.value(), record.offset(), record.partition());
                    }
                } catch (RecordDeserializationException e) {
                    // Send to dead letter queue and seek past the bad record
                    TopicPartition tp = e.topicPartition();
                    long offset = e.offset();
                    logger.log(Level.WARNING,
                            "Deserialization error on " + tp + " at offset " + offset
                                    + ", sending to DLQ and skipping", e);
                    if (deadLetterQueue != null) {
                        deadLetterQueue.add(tp.topic(), tp.partition(), offset,
                                e.getCause() != null ? e.getCause().getMessage() : e.getMessage(), e);
                    }
                    consumer.seek(tp, offset + 1);
                } catch (Exception e) {
                    if (running.get()) {
                        logger.log(Level.SEVERE,
                                "Error polling topic " + consumerConfig.topic(), e);
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE,
                    "Fatal error in consumer for topic " + consumerConfig.topic(), e);
        }
    }

    private void processRecord(GenericRecord record, long offset, int partition) {
        try {
            long startNs = System.nanoTime();
            E event = mapper.map(record);
            logger.fine("Mapped Kafka message from topic " + consumerConfig.topic()
                    + " [partition=" + partition + ", offset=" + offset + "] to event: " + event);
            engine.processEvent(event);
            if (metrics != null) {
                metrics.recordKafkaMessage(consumerConfig.topic(), System.nanoTime() - startNs);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING,
                    "Failed to process record from topic " + consumerConfig.topic()
                            + " [partition=" + partition + ", offset=" + offset + "]", e);
            if (deadLetterQueue != null) {
                deadLetterQueue.add(consumerConfig.topic(), partition, offset, e.getMessage(), e);
            }
        }
    }

    Properties buildConsumerProperties() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.bootstrapServer());
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, kafkaConfig.clientId());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerConfig.groupId());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class.getName());
        props.put(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, kafkaConfig.schemaRegistryUrl());
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, false);

        if (consumerConfig.readAllMessagesAtStartup()) {
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        } else {
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        }

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
}
