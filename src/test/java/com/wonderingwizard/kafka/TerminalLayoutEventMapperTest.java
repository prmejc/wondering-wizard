package com.wonderingwizard.kafka;

import com.wonderingwizard.events.DigitalMapEvent;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerminalLayoutEventMapperTest {

    private static final String AVRO_SCHEMA_JSON = """
            {
                "namespace": "apmt.terminaloperations.digitalmap.confidential.dedicated.v1",
                "type": "record",
                "name": "TerminalLayout",
                "fields": [
                    {"name": "terminalCode", "type": "string"},
                    {"name": "terminalLayoutVersion", "type": "string"},
                    {"name": "terminalLayout", "type": "string"},
                    {"name": "timeStamp", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "eventSource", "type": "string"}
                ]
            }
            """;

    private Schema schema;
    private TerminalLayoutEventMapper mapper;

    @BeforeEach
    void setUp() {
        schema = new Schema.Parser().parse(AVRO_SCHEMA_JSON);
        mapper = new TerminalLayoutEventMapper();
    }

    @Test
    void shouldMapToDigitalMapEventWithJsonEnvelope() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("terminalCode", "MAPTM");
        record.put("terminalLayoutVersion", "47");
        record.put("terminalLayout", "H4sIAAAAAAAA==");
        record.put("timeStamp", 1700000000000L);
        record.put("eventSource", "test-source");

        DigitalMapEvent event = mapper.map(record);

        assertNotNull(event);
        String payload = event.mapPayload();
        assertTrue(payload.contains("\"terminalCode\":\"MAPTM\""));
        assertTrue(payload.contains("\"terminalLayoutVersion\":\"47\""));
        assertTrue(payload.contains("\"terminalLayout\":\"H4sIAAAAAAAA==\""));
        assertTrue(payload.contains("\"eventSource\":\"test-source\""));
    }

    @Test
    void shouldProduceValidJsonParsableByDigitalMapProcessor() {
        // Use a real gzipped base64 payload (from DigitalMapProcessorTest)
        String osmXml = """
                <?xml version='1.0' encoding='UTF-8'?>
                <osm version="0.6">
                  <node id="1" lat="35.887" lon="-5.494" version="1">
                    <tag k="amenity" v="apmt_poi_destination" />
                    <tag k="name" v="A" />
                  </node>
                </osm>
                """;
        String b64Layout = compressToBase64(osmXml);

        GenericRecord record = new GenericData.Record(schema);
        record.put("terminalCode", "TEST");
        record.put("terminalLayoutVersion", "1");
        record.put("terminalLayout", b64Layout);
        record.put("timeStamp", 0L);
        record.put("eventSource", "test");

        DigitalMapEvent event = mapper.map(record);

        // The processor should be able to parse this
        var processor = new com.wonderingwizard.processors.DigitalMapProcessor();
        processor.process(event);
        assertTrue(processor.isMapLoaded(), "DigitalMapProcessor should load the map from the mapped event");
    }

    @Test
    void shouldHandleEmptyTerminalLayout() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("terminalCode", "TEST");
        record.put("terminalLayoutVersion", "1");
        record.put("terminalLayout", "");
        record.put("timeStamp", 0L);
        record.put("eventSource", "test");

        DigitalMapEvent event = mapper.map(record);

        assertEquals("", event.mapPayload());
    }

    @Test
    void shouldEscapeSpecialCharactersInFields() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("terminalCode", "TEST\"CODE");
        record.put("terminalLayoutVersion", "1\\2");
        record.put("terminalLayout", "someBase64Data");
        record.put("timeStamp", 0L);
        record.put("eventSource", "test");

        DigitalMapEvent event = mapper.map(record);

        String payload = event.mapPayload();
        assertTrue(payload.contains("TEST\\\"CODE"), "Should escape quotes");
        assertTrue(payload.contains("1\\\\2"), "Should escape backslashes");
    }

    private static String compressToBase64(String data) {
        try {
            var baos = new java.io.ByteArrayOutputStream();
            try (var gzos = new java.util.zip.GZIPOutputStream(baos)) {
                gzos.write(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
