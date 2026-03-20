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
     * Creates Settings from the given properties (for testing).
     */
    static Settings of(Properties properties) {
        return new Settings(properties);
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
            Path path = Path.of(externalFile.trim());
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

    public boolean clockAutoStart() {
        return getBoolean("clock.autostart", true);
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

    public ConsumerConfiguration cheTargetPositionConsumerConfiguration() {
        return new ConsumerConfiguration(
                get("kafka.consumer.che-target-position.topic",
                        "apmt.terminaloperations.chetargetposition.topic.confidential.dedicated.v1"),
                get("kafka.consumer.che-target-position.group-id", "wondering-wizard-che-target-position"),
                null,
                "CheTargetPositionConfirmation",
                false
        );
    }

    public ConsumerConfiguration jobOperationConsumerConfiguration() {
        return new ConsumerConfiguration(
                get("kafka.consumer.job-operation.topic",
                        "apmt.terminaloperations.joboperation.topic.confidential.dedicated.v1"),
                get("kafka.consumer.job-operation.group-id", "wondering-wizard-job-operation"),
                null,
                "JobOperation",
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

    public String containerMoveStateTopic() {
        return get("kafka.producer.container-move-state.topic",
                "apmt.terminaloperations.containermovestate.topic.confidential.dedicated.v1");
    }

    public ConsumerConfiguration containerMoveStateConsumerConfiguration() {
        return new ConsumerConfiguration(
                get("kafka.consumer.container-move-state.topic",
                        "apmt.terminaloperations.containermovestate.topic.confidential.status.v1"),
                get("kafka.consumer.container-move-state.group-id", "wondering-wizard-cms"),
                get("kafka.consumer.container-move-state.avro-message-type", null),
                null,
                getBoolean("kafka.consumer.container-move-state.read-all-at-startup", false)
        );
    }

    public ConsumerConfiguration craneDelayActivitiesConsumerConfiguration() {
        return new ConsumerConfiguration(
                get("kafka.consumer.crane-delay-activities.topic",
                        "APMT.terminalOperations.craneDelayActivities.topic.confidential.dedicated.v1"),
                get("kafka.consumer.crane-delay-activities.group-id", "wondering-wizard-crane-delay"),
                get("kafka.consumer.crane-delay-activities.avro-message-type", null),
                null,
                getBoolean("kafka.consumer.crane-delay-activities.read-all-at-startup", false)
        );
    }

    public ConsumerConfiguration craneAvailabilityStatusConsumerConfiguration() {
        return new ConsumerConfiguration(
                get("kafka.consumer.crane-availability-status.topic",
                        "apmt.terminaloperations.craneavailabilitystatus.topic.confidential.dedicated.v1"),
                get("kafka.consumer.crane-availability-status.group-id", "wondering-wizard-crane-availability"),
                get("kafka.consumer.crane-availability-status.avro-message-type", null),
                null,
                getBoolean("kafka.consumer.crane-availability-status.read-all-at-startup", false)
        );
    }

    public ConsumerConfiguration craneReadinessConsumerConfiguration() {
        return new ConsumerConfiguration(
                get("kafka.consumer.crane-readiness.topic",
                        "apmt.terminal-operations.cranereadiness.topic.internal.any.v1"),
                get("kafka.consumer.crane-readiness.group-id", "wondering-wizard-crane-readiness"),
                get("kafka.consumer.crane-readiness.avro-message-type", null),
                null,
                getBoolean("kafka.consumer.crane-readiness.read-all-at-startup", false)
        );
    }

    public ConsumerConfiguration quayCraneMappingConsumerConfiguration() {
        return new ConsumerConfiguration(
                get("kafka.consumer.quay-crane-mapping.topic",
                        "apmt.quaysideoperations.quaycraneflowposition.topic.internal.any.v2"),
                get("kafka.consumer.quay-crane-mapping.group-id", "wondering-wizard-qc-mapping"),
                get("kafka.consumer.quay-crane-mapping.avro-message-type", null),
                null,
                getBoolean("kafka.consumer.quay-crane-mapping.read-all-at-startup", true)
        );
    }

    public ConsumerConfiguration terminalLayoutConsumerConfiguration() {
        return new ConsumerConfiguration(
                get("kafka.consumer.terminal-layout.topic",
                        "apmt.terminaloperations.digitalmap.topic.confidential.dedicated.v1"),
                get("kafka.consumer.terminal-layout.group-id", "wondering-wizard-digitalmap"),
                get("kafka.consumer.terminal-layout.avro-message-type", null),
                null,
                getBoolean("kafka.consumer.terminal-layout.read-all-at-startup", true)
        );
    }

    public ConsumerConfiguration cheLogicalPositionConsumerConfiguration() {
        return new ConsumerConfiguration(
                get("kafka.consumer.che-logical-position.topic",
                        "apmt.terminaloperations.chelogicalposition.topic.confidential.dedicated.v1"),
                get("kafka.consumer.che-logical-position.group-id", "wondering-wizard-che-pos"),
                get("kafka.consumer.che-logical-position.avro-message-type", null),
                null,
                false
        );
    }

    public ConsumerConfiguration containerHandlingEquipmentConsumerConfiguration() {
        return new ConsumerConfiguration(
                get("kafka.consumer.container-handling-equipment.topic",
                        "APMT.terminalOperations.containerHandlingEquipment.topic.confidential.dedicated.v1"),
                get("kafka.consumer.container-handling-equipment.group-id", "wondering-wizard-che"),
                get("kafka.consumer.container-handling-equipment.avro-message-type", null),
                null,
                getBoolean("kafka.consumer.container-handling-equipment.read-all-at-startup", false)
        );
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
