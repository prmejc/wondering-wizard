package com.wonderingwizard.server;

import com.wonderingwizard.kafka.ConsumerConfiguration;
import com.wonderingwizard.kafka.KafkaConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Application settings loaded from properties file.
 * <p>
 * Loads from {@code settings.properties} on the classpath, with optional
 * override from a file path specified via the {@code SETTINGS_FILE} environment variable.
 */
public class Settings {

    private static final Logger logger = Logger.getLogger(Settings.class.getName());
    private static final String CLASSPATH_RESOURCE = "/settings.properties";
    private static final String ENV_SETTINGS_FILE = "SETTINGS_FILE";

    private final Properties properties;

    private Settings(Properties properties) {
        this.properties = properties;
    }

    /**
     * Loads settings from classpath, then overrides with external file if
     * the {@code SETTINGS_FILE} environment variable is set.
     */
    public static Settings load() {
        Properties props = new Properties();

        // Load defaults from classpath
        try (InputStream is = Settings.class.getResourceAsStream(CLASSPATH_RESOURCE)) {
            if (is != null) {
                props.load(is);
                logger.info("Loaded settings from classpath: " + CLASSPATH_RESOURCE);
            }
        } catch (IOException e) {
            logger.warning("Failed to load classpath settings: " + e.getMessage());
        }

        // Override from external file if specified
        String externalFile = System.getenv(ENV_SETTINGS_FILE);
        if (externalFile != null && !externalFile.isBlank()) {
            Path path = Path.of(externalFile);
            if (Files.exists(path)) {
                try (InputStream is = Files.newInputStream(path)) {
                    props.load(is);
                    logger.info("Loaded settings override from: " + externalFile);
                } catch (IOException e) {
                    logger.warning("Failed to load external settings: " + e.getMessage());
                }
            } else {
                logger.warning("Settings file not found: " + externalFile);
            }
        }

        return new Settings(props);
    }

    // --- Server ---

    public int serverPort() {
        return getInt("server.port", 8080);
    }

    // --- Kafka ---

    public boolean kafkaEnabled() {
        return getBoolean("kafka.enabled", false);
    }

    public KafkaConfiguration kafkaConfiguration() {
        return new KafkaConfiguration(
                get("kafka.bootstrap-server", "localhost:9092"),
                get("kafka.client-id", "wondering-wizard"),
                get("kafka.schema-registry-url", "http://localhost:8081"),
                get("kafka.sasl-mechanism", ""),
                get("kafka.sasl-username", ""),
                get("kafka.sasl-password", ""),
                get("kafka.security-protocol", "")
        );
    }

    public ConsumerConfiguration workQueueConsumerConfiguration() {
        return new ConsumerConfiguration(
                get("kafka.consumer.work-queue.topic", "work-queue"),
                get("kafka.consumer.work-queue.group-id", "wondering-wizard-wq"),
                get("kafka.consumer.work-queue.avro-message-type", null),
                null,
                getBoolean("kafka.consumer.work-queue.read-all-at-startup", false)
        );
    }

    public ConsumerConfiguration assetEventRtgConsumerConfiguration() {
        return new ConsumerConfiguration(
                get("kafka.consumer.asset-event-rtg.topic",
                        "apmt.terminaloperations.assetevent.rubbertyredgantry.topic.confidential.dedicated.v1"),
                get("kafka.consumer.asset-event-rtg.group-id", "wondering-wizard-asset-event-rtg"),
                null,
                "AssetEvent",
                false
        );
    }

    public ConsumerConfiguration assetEventQcConsumerConfiguration() {
        return new ConsumerConfiguration(
                get("kafka.consumer.asset-event-qc.topic",
                        "apmt.terminaloperations.assetevent.quaycrane.topic.confidential.dedicated.v1"),
                get("kafka.consumer.asset-event-qc.group-id", "wondering-wizard-asset-event-qc"),
                null,
                "AssetEvent",
                false
        );
    }

    public ConsumerConfiguration assetEventEhConsumerConfiguration() {
        return new ConsumerConfiguration(
                get("kafka.consumer.asset-event-eh.topic",
                        "apmt.terminaloperations.assetevent.emptyhandler.topic.confidential.dedicated.v1"),
                get("kafka.consumer.asset-event-eh.group-id", "wondering-wizard-asset-event-eh"),
                null,
                "AssetEvent",
                false
        );
    }

    public String terminalCode() {
        return get("kafka.terminal-code", "UNKNOWN");
    }

    public String equipmentInstructionRtgTopic() {
        return get("kafka.producer.equipment-instruction-rtg.topic",
                "apmt.terminaloperations.equipmentinstruction.rubbertyredgantry.topic.confidential.dedicated.v1");
    }

    public String equipmentInstructionTtTopic() {
        return get("kafka.producer.equipment-instruction-tt.topic",
                "apmt.terminaloperations.equipmentinstruction.terminaltruck.topic.confidential.dedicated.v1");
    }

    public String equipmentInstructionQcTopic() {
        return get("kafka.producer.equipment-instruction-qc.topic",
                "apmt.terminaloperations.equipmentinstruction.quaycrane.topic.confidential.dedicated.v1");
    }

    public ConsumerConfiguration workInstructionConsumerConfiguration() {
        return new ConsumerConfiguration(
                get("kafka.consumer.work-instruction.topic", "work-instruction"),
                get("kafka.consumer.work-instruction.group-id", "wondering-wizard-wi"),
                get("kafka.consumer.work-instruction.avro-message-type", null),
                null,
                getBoolean("kafka.consumer.work-instruction.read-all-at-startup", false)
        );
    }

    // --- Helpers ---

    private String get(String key, String defaultValue) {
        String value = System.getenv(envKey(key));
        if (value != null && !value.isBlank()) {
            return value;
        }
        return properties.getProperty(key, defaultValue);
    }

    private int getInt(String key, int defaultValue) {
        String value = get(key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            logger.warning("Invalid integer for " + key + ": " + value + ", using default " + defaultValue);
            return defaultValue;
        }
    }

    private boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /**
     * Converts a property key to an environment variable name.
     * E.g., "kafka.bootstrap-server" becomes "KAFKA_BOOTSTRAP_SERVER".
     */
    private static String envKey(String key) {
        return key.replace('.', '_').replace('-', '_').toUpperCase();
    }
}
