package com.wonderingwizard.kafka;

import com.wonderingwizard.events.CheLogicalPositionEvent;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CheLogicalPositionEventMapperTest {

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

    private Schema schema;
    private CheLogicalPositionEventMapper mapper;

    @BeforeEach
    void setUp() {
        schema = new Schema.Parser().parse(AVRO_SCHEMA_JSON);
        mapper = new CheLogicalPositionEventMapper();
    }

    @Test
    void shouldMapAllFields() {
        GenericRecord coords = new GenericData.Record(schema.getField("coordinates").schema());
        coords.put("latitude", 35.889);
        coords.put("longitude", -5.496);
        coords.put("hdop", 1.2);

        GenericRecord record = new GenericData.Record(schema);
        record.put("terminalCode", "MAPTM");
        record.put("cheShortName", "TT01");
        record.put("currentMapNodeID", 42L);
        record.put("currentMapNodeName", "3A13");
        record.put("timeStamp", 1700000000000L);
        record.put("coordinates", coords);
        record.put("eventSource", "test");

        CheLogicalPositionEvent event = mapper.map(record);

        assertEquals("TT01", event.cheShortName());
        assertEquals(42L, event.currentMapNodeId());
        assertEquals("3A13", event.currentMapNodeName());
        assertEquals(35.889, event.latitude(), 0.0001);
        assertEquals(-5.496, event.longitude(), 0.0001);
        assertEquals(1.2, event.hdop(), 0.0001);
        assertEquals(1700000000000L, event.timestampMs());
    }

    @Test
    void shouldHandleNullNodeName() {
        GenericRecord coords = new GenericData.Record(schema.getField("coordinates").schema());
        coords.put("latitude", 0.0);
        coords.put("longitude", 0.0);
        coords.put("hdop", 0.0);

        GenericRecord record = new GenericData.Record(schema);
        record.put("terminalCode", "MAPTM");
        record.put("cheShortName", "TT02");
        record.put("currentMapNodeID", 99L);
        record.put("currentMapNodeName", null);
        record.put("timeStamp", 0L);
        record.put("coordinates", coords);
        record.put("eventSource", "test");

        CheLogicalPositionEvent event = mapper.map(record);

        assertEquals("TT02", event.cheShortName());
        assertEquals(99L, event.currentMapNodeId());
        assertNull(event.currentMapNodeName());
    }
}
