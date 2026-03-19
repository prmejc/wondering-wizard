package com.wonderingwizard.e2e;

import com.wonderingwizard.server.Settings;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end performance test for CheLogicalPosition event processing.
 * <p>
 * Publishes 10,000 Avro CheLogicalPosition messages directly to Kafka using
 * the same connection settings as the application. The last message has node
 * name "FINAL", all others "TEST". Then polls the server API until TT01's
 * position shows "FINAL". Passes if total time is under 20 seconds.
 * <p>
 * Requires both Kafka and the server to be running:
 * <pre>
 * mvn exec:java                    # start server (connects to Kafka)
 * mvn test -Pe2e                   # run this test
 * </pre>
 */
@Tag("e2e")
@DisplayName("E2E: CheLogicalPosition Kafka → Server Processing")
class CheLogicalPositionE2ETest {

    private static final String BASE_URL = System.getProperty("e2e.baseUrl", "http://localhost:8080");
    private static final int MESSAGE_COUNT = 10_000;
    private static final int MAX_DURATION_SECONDS = 20;
    private static final String TRUCK_NAME = "TT01";
    private static final String FINAL_NODE_NAME = "FINAL";

    private static final String AVRO_SCHEMA_JSON = """
            {
                "type": "record",
                "name": "CheLogicalPosition",
                "namespace": "apmt.terminaloperations.chelogicalposition.confidential.dedicated.v1",
                "fields": [
                    {"name": "terminalCode", "type": "string"},
                    {"name": "cheShortName", "type": "string"},
                    {"name": "currentMapNodeID", "type": "long"},
                    {"name": "currentMapNodeName", "type": ["null", "string"]},
                    {"name": "timeStamp", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "coordinates", "type": {
                        "type": "record", "name": "GpsPosition",
                        "fields": [
                            {"name": "latitude", "type": "double"},
                            {"name": "longitude", "type": "double"},
                            {"name": "hdop", "type": "double"}
                        ]
                    }},
                    {"name": "eventSource", "type": "string"}
                ]
            }
            """;

    @Test
    @DisplayName("Publish 10k Avro messages to Kafka, verify server processes last within 20s")
    void shouldProcess10kKafkaMessagesWithin20Seconds() throws Exception {
        //assertServerIsReachable();

        // Ensure TT01 exists via the HTTP API (it must exist before positions are accepted)
        //ensureTruckExists(TRUCK_NAME);

        Settings settings = Settings.load();
        String topic = settings.cheLogicalPositionConsumerConfiguration().topic();

        Schema schema = new Schema.Parser().parse(AVRO_SCHEMA_JSON);
        Schema coordsSchema = schema.getField("coordinates").schema();

        System.out.println("Publishing " + MESSAGE_COUNT + " Avro CheLogicalPosition messages to topic: " + topic);

        long startTime = System.nanoTime();

        // Produce 10,000 messages directly to Kafka
        try (KafkaProducer<String, GenericRecord> producer = createProducer(settings)) {
            for (int i = 0; i < MESSAGE_COUNT; i++) {
                String nodeName = (i == MESSAGE_COUNT - 1) ? FINAL_NODE_NAME : "TEST";

                GenericRecord coords = new GenericData.Record(coordsSchema);
                coords.put("latitude", 35.889 + (i * 0.00001));
                coords.put("longitude", -5.496 + (i * 0.00001));
                coords.put("hdop", 1.0);

                GenericRecord record = new GenericData.Record(schema);
                record.put("terminalCode", "MAPTM");
                record.put("cheShortName", TRUCK_NAME);
                record.put("currentMapNodeID", (long) (i + 1));
                record.put("currentMapNodeName", nodeName);
                record.put("timeStamp", System.currentTimeMillis());
                record.put("coordinates", coords);
                record.put("eventSource", "e2e-test");

                producer.send(new ProducerRecord<>(topic, TRUCK_NAME, record));
            }
            producer.flush();
        }

        long postDurationMs = (System.nanoTime() - startTime) / 1_000_000;
        System.out.println("All messages published in " + postDurationMs + " ms");

        // Poll API until TT01's position shows FINAL
        System.out.println("Polling API for " + TRUCK_NAME + " position = " + FINAL_NODE_NAME + "...");

        String currentNodeName = null;
        int pollCount = 0;
        while (true) {
            pollCount++;
            long elapsed = (System.nanoTime() - startTime) / 1_000_000;
            if (elapsed > MAX_DURATION_SECONDS * 1000L) {
                fail("Timed out after " + elapsed + " ms. Last node name: " + currentNodeName
                        + " (polled " + pollCount + " times)");
            }

            currentNodeName = getTruckNodeName(TRUCK_NAME);
            if (FINAL_NODE_NAME.equals(currentNodeName)) {
                break;
            }

            Thread.sleep(100);
        }

        long totalDurationMs = (System.nanoTime() - startTime) / 1_000_000;
        System.out.println("\n=== Results ===");
        System.out.println("Total time: " + totalDurationMs + " ms");
        System.out.println("Kafka publish time: " + postDurationMs + " ms");
        System.out.println("Processing + poll lag: " + (totalDurationMs - postDurationMs) + " ms");
        System.out.println("Poll iterations: " + pollCount);
        System.out.println("Messages: " + MESSAGE_COUNT);
        System.out.println("Throughput: " + (MESSAGE_COUNT * 1000L / Math.max(1, totalDurationMs)) + " msg/s");

        assertTrue(totalDurationMs < MAX_DURATION_SECONDS * 1000L,
                "Total time " + totalDurationMs + " ms exceeds " + MAX_DURATION_SECONDS + "s limit");
    }

    private KafkaProducer<String, GenericRecord> createProducer(Settings settings) {
        var kafkaConfig = settings.kafkaConfiguration();
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.bootstrapServer());
        props.put(ProducerConfig.CLIENT_ID_CONFIG, kafkaConfig.clientId() + "-e2e-test");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class.getName());
        props.put(KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, kafkaConfig.schemaRegistryUrl());
        props.put(KafkaAvroSerializerConfig.AUTO_REGISTER_SCHEMAS, true);

        if (kafkaConfig.securityProtocol() != null && !kafkaConfig.securityProtocol().isBlank()) {
            props.put("security.protocol", kafkaConfig.securityProtocol());
            props.put("sasl.mechanism", kafkaConfig.saslMechanism());
            props.put("sasl.jaas.config",
                    "org.apache.kafka.common.security.plain.PlainLoginModule required "
                            + "username=\"" + kafkaConfig.saslUsername() + "\" "
                            + "password=\"" + kafkaConfig.saslPassword() + "\";");
        }

        return new KafkaProducer<>(props);
    }

    private void ensureTruckExists(String cheShortName) throws IOException {
        String json = "{\"cheShortName\":\"" + cheShortName
                + "\",\"cheStatus\":\"Working\",\"cheKind\":\"TT\""
                + ",\"chePoolId\":\"23\",\"cheJobStepState\":\"IDLE\""
                + ",\"cheId\":\"100\"}";
        HttpURLConnection conn = (HttpURLConnection) URI.create(
                BASE_URL + "/api/container-handling-equipment").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        try (var os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(200, conn.getResponseCode(), "Failed to create truck " + cheShortName);
        conn.getInputStream().readAllBytes();
    }

    private String getTruckNodeName(String cheShortName) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(
                BASE_URL + "/api/state").toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        if (conn.getResponseCode() != 200) return null;

        String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        int posIdx = body.indexOf("\"truckPositions\"");
        if (posIdx < 0) return null;
        int truckIdx = body.indexOf("\"" + cheShortName + "\"", posIdx);
        if (truckIdx < 0) return null;
        int nodeNameIdx = body.indexOf("\"currentMapNodeName\"", truckIdx);
        if (nodeNameIdx < 0) return null;
        int colonIdx = body.indexOf(':', nodeNameIdx + 20);
        if (colonIdx < 0) return null;
        int firstQuote = body.indexOf('"', colonIdx + 1);
        if (firstQuote < 0) return null;
        String between = body.substring(colonIdx + 1, firstQuote).trim();
        if (between.startsWith("null")) return null;
        int secondQuote = body.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) return null;
        return body.substring(firstQuote + 1, secondQuote);
    }

    private void assertServerIsReachable() {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(
                    BASE_URL + "/api/version").toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            assertEquals(200, conn.getResponseCode(),
                    "Server not reachable at " + BASE_URL + ". Start it with 'mvn exec:java' first.");
        } catch (Exception e) {
            fail("Server not reachable at " + BASE_URL + ": " + e.getMessage()
                    + ". Start it with 'mvn exec:java' first.");
        }
    }
}
