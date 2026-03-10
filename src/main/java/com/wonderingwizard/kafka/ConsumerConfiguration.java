package com.wonderingwizard.kafka;

/**
 * Configuration for a single Kafka consumer (one per topic).
 *
 * @param topic the Kafka topic to consume from
 * @param groupId the consumer group ID
 * @param avroMessageType the fully-qualified Avro record type name (null for JSON messages)
 * @param jsonMessageType the fully-qualified JSON message type name (null for Avro messages)
 * @param readAllMessagesAtStartup if true, read from beginning of topic on first connect
 */
public record ConsumerConfiguration(
        String topic,
        String groupId,
        String avroMessageType,
        String jsonMessageType,
        boolean readAllMessagesAtStartup
) {

    /**
     * Returns whether this consumer uses Avro deserialization.
     */
    public boolean isAvro() {
        return avroMessageType != null && !avroMessageType.isBlank();
    }

    /**
     * Returns whether this consumer uses JSON deserialization.
     */
    public boolean isJson() {
        return jsonMessageType != null && !jsonMessageType.isBlank();
    }
}
