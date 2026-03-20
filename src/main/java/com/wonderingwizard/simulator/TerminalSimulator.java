package com.wonderingwizard.simulator;

import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Terminal Equipment Simulator.
 * <p>
 * Consumes equipment instruction messages from Kafka and produces simulated
 * equipment responses (asset events, work instructions, job operations, etc.)
 * <p>
 * Also consumes the WI topic to maintain latest work instruction state,
 * which is used to construct "QC Discharged Container" events.
 */
public class TerminalSimulator {

    private static final Logger logger = Logger.getLogger(TerminalSimulator.class.getName());

    private final Properties config;
    private final Map<String, InstructionHandler> handlers = new HashMap<>();
    private final WorkInstructionStateTracker wiTracker = new WorkInstructionStateTracker();
    private volatile boolean running = true;

    public TerminalSimulator(Properties config) {
        this.config = config;
    }

    public void registerHandler(String topic, InstructionHandler handler) {
        handlers.put(topic, handler);
    }

    public WorkInstructionStateTracker getWiTracker() {
        return wiTracker;
    }

    public void run() {
        KafkaInfra kafka = new KafkaInfra(config);

        try {
            // Start WI state consumer on background thread
            String wiTopic = config.getProperty("topic.work-instruction");
            if (wiTopic != null && !wiTopic.isBlank()) {
                startWiConsumer(kafka, wiTopic);
            }

            // Subscribe equipment instruction consumer
            List<String> topics = List.copyOf(handlers.keySet());
            kafka.consumer().subscribe(topics, new org.apache.kafka.clients.consumer.ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(java.util.Collection<org.apache.kafka.common.TopicPartition> partitions) {}

                @Override
                public void onPartitionsAssigned(java.util.Collection<org.apache.kafka.common.TopicPartition> partitions) {
                    logger.info("Equipment instruction consumer: seeking to end on " + partitions.size() + " partitions");
                    kafka.consumer().seekToEnd(partitions);
                }
            });
            logger.info("Terminal Simulator started — consuming instructions from " + topics);

            while (running) {
                ConsumerRecords<String, GenericRecord> records = kafka.consumer().poll(Duration.ofSeconds(1));
                records.forEach(record -> {
                    InstructionHandler handler = handlers.get(record.topic());
                    if (handler != null) {
                        try {
                            handler.handle(record.value(), kafka);
                        } catch (Exception e) {
                            logger.warning("Error handling message from " + record.topic() + ": " + e.getMessage());
                        }
                    }
                });
            }
        } finally {
            kafka.close();
        }
    }

    private void startWiConsumer(KafkaInfra kafka, String wiTopic) {
        Thread.ofVirtual().name("wi-state-consumer").start(() -> {
            kafka.wiConsumer().subscribe(List.of(wiTopic));
            logger.info("WI state consumer started — consuming from " + wiTopic);

            while (running) {
                try {
                    ConsumerRecords<String, GenericRecord> records = kafka.wiConsumer().poll(Duration.ofSeconds(1));
                    records.forEach(record -> wiTracker.update(record.value()));
                } catch (Exception e) {
                    if (running) {
                        logger.warning("WI state consumer error: " + e.getMessage());
                    }
                }
            }
        });
    }

    public static void main(String[] args) {
        Properties config = loadConfig();

        // Allow overrides from external file (same pattern as main service)
        String externalFile = System.getenv("SIMULATOR_SETTINGS_FILE");
        if (externalFile != null && !externalFile.isBlank()) {
            Path path = Path.of(externalFile.trim());
            if (Files.exists(path)) {
                try (InputStream is = Files.newInputStream(path)) {
                    config.load(is);
                    logger.info("Loaded simulator settings override from: " + externalFile);
                } catch (IOException e) {
                    logger.warning("Failed to load external simulator settings: " + e.getMessage());
                }
            } else {
                logger.warning("Simulator settings file not found: " + externalFile);
            }
        }

        // Allow overrides from system properties
        System.getProperties().forEach((key, value) -> {
            String k = key.toString();
            if (k.startsWith("kafka.") || k.startsWith("terminal.") || k.startsWith("topic.")) {
                config.setProperty(k, value.toString());
            }
        });

        String terminalCode = config.getProperty("terminal.code", "");

        TerminalSimulator simulator = new TerminalSimulator(config);

        // Register QC handler
        simulator.registerHandler(
                config.getProperty("topic.equipment-instruction.qc"),
                new QCHandler(
                        config.getProperty("topic.asset-event.qc"),
                        config.getProperty("topic.work-instruction"),
                        terminalCode,
                        simulator.getWiTracker()));

        // Future: register RTG and TT handlers here

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down Terminal Simulator...");
            simulator.running = false;
        }));

        simulator.run();
    }

    private static Properties loadConfig() {
        Properties props = new Properties();
        try (InputStream is = TerminalSimulator.class.getResourceAsStream("/simulator.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            logger.warning("Could not load simulator.properties: " + e.getMessage());
        }
        return props;
    }
}
