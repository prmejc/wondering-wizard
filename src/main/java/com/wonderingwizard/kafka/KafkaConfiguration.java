package com.wonderingwizard.kafka;

/**
 * Top-level Kafka connection configuration.
 *
 * @param bootstrapServer Kafka broker address (e.g., "kafka-broker-service:9094")
 * @param clientId client identifier for this application
 * @param schemaRegistryUrl URL of the Confluent Schema Registry
 * @param saslMechanism SASL authentication mechanism (e.g., "Plain")
 * @param saslUsername SASL username
 * @param saslPassword SASL password
 * @param securityProtocol security protocol (e.g., "SaslPlaintext")
 */
public record KafkaConfiguration(
        String bootstrapServer,
        String clientId,
        String schemaRegistryUrl,
        String saslMechanism,
        String saslUsername,
        String saslPassword,
        String securityProtocol
) {
}
