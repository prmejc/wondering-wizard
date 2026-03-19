package com.wonderingwizard.kafka;

import com.wonderingwizard.events.QuayCraneMappingEvent;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuayCraneMappingEventMapperTest {

    private static final String AVRO_SCHEMA_JSON = """
            {
                "name": "QuayCraneMapping",
                "type": "record",
                "namespace": "apmt.quaysideoperations.quaycraneflowposition.topic.internal.any.v2",
                "fields": [
                    {"name": "quayCraneShortName", "type": "string"},
                    {"name": "vesselName", "type": ["null", "string"], "default": null},
                    {"name": "craneMode", "type": ["null", "string"], "default": null},
                    {"name": "radioChannel", "type": ["null", "string"], "default": null},
                    {"name": "lane", "type": ["null", "string"], "default": null},
                    {"name": "standby", "type": ["null", {
                        "type": "record", "name": "StandbyPosition",
                        "fields": [
                            {"name": "positionName", "type": "string"},
                            {"name": "nodeId", "type": "long"},
                            {"name": "nodeName", "type": "string"},
                            {"name": "trafficDirection", "type": "string"}
                        ]
                    }]},
                    {"name": "loadPinning", "type": ["null", {
                        "type": "record", "name": "PinningPosition",
                        "fields": [
                            {"name": "positionName", "type": "string"},
                            {"name": "nodeId", "type": "long"},
                            {"name": "nodeName", "type": "string"}
                        ]
                    }], "default": null},
                    {"name": "dischargePinning", "type": ["null", "PinningPosition"], "default": null},
                    {"name": "eventSource", "type": "string"},
                    {"name": "terminalCode", "type": "string"},
                    {"name": "timeStamp", "type": {"type": "long", "logicalType": "timestamp-millis"}}
                ]
            }
            """;

    private Schema schema;
    private QuayCraneMappingEventMapper mapper;

    @BeforeEach
    void setUp() {
        schema = new Schema.Parser().parse(AVRO_SCHEMA_JSON);
        mapper = new QuayCraneMappingEventMapper();
    }

    @Test
    void shouldMapAllFieldsIncludingNestedRecords() {
        Schema standbySchema = schema.getField("standby").schema().getTypes().get(1);
        GenericRecord standby = new GenericData.Record(standbySchema);
        standby.put("positionName", "B80");
        standby.put("nodeId", 9022L);
        standby.put("nodeName", "B80");
        standby.put("trafficDirection", "N");

        Schema pinSchema = schema.getField("loadPinning").schema().getTypes().get(1);
        GenericRecord loadPin = new GenericData.Record(pinSchema);
        loadPin.put("positionName", "B81");
        loadPin.put("nodeId", 9040L);
        loadPin.put("nodeName", "B81");

        GenericRecord dischPin = new GenericData.Record(pinSchema);
        dischPin.put("positionName", "B82");
        dischPin.put("nodeId", 9041L);
        dischPin.put("nodeName", "B82");

        GenericRecord record = new GenericData.Record(schema);
        record.put("quayCraneShortName", "QCZ9");
        record.put("vesselName", "MAERSK EMERALD");
        record.put("craneMode", "DSCH");
        record.put("lane", "L1");
        record.put("standby", standby);
        record.put("loadPinning", loadPin);
        record.put("dischargePinning", dischPin);
        record.put("eventSource", "test");
        record.put("terminalCode", "MAPTM");
        record.put("timeStamp", 1700000000000L);

        QuayCraneMappingEvent event = mapper.map(record);

        assertEquals("QCZ9", event.quayCraneShortName());
        assertEquals("MAERSK EMERALD", event.vesselName());
        assertEquals("DSCH", event.craneMode());
        assertEquals("L1", event.lane());
        assertEquals("B80", event.standbyPositionName());
        assertEquals("B80", event.standbyNodeName());
        assertEquals("N", event.standbyTrafficDirection());
        assertEquals("B81", event.loadPinningPositionName());
        assertEquals("B82", event.dischargePinningPositionName());
        assertEquals("MAPTM", event.terminalCode());
        assertEquals(1700000000000L, event.timestampMs());
    }

    @Test
    void shouldHandleNullNestedRecords() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("quayCraneShortName", "QC01");
        record.put("vesselName", null);
        record.put("craneMode", null);
        record.put("lane", null);
        record.put("standby", null);
        record.put("loadPinning", null);
        record.put("dischargePinning", null);
        record.put("eventSource", "test");
        record.put("terminalCode", "TEST");
        record.put("timeStamp", 0L);

        QuayCraneMappingEvent event = mapper.map(record);

        assertEquals("QC01", event.quayCraneShortName());
        assertNull(event.vesselName());
        assertNull(event.standbyPositionName());
        assertNull(event.standbyNodeName());
        assertNull(event.loadPinningPositionName());
        assertNull(event.dischargePinningPositionName());
    }
}
