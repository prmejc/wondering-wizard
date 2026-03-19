package com.wonderingwizard.kafka;

import com.wonderingwizard.events.CraneDelayActivityEvent;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class CraneDelayActivityEventMapperTest {

    private static final String AVRO_SCHEMA_JSON = """
            {
                "type": "record",
                "name": "craneDelayActivities",
                "namespace": "APMT.terminalOperations.craneDelayActivities.topic.confidential.dedicated.v1",
                "fields": [
                    {"name": "eventType", "type": ["null", "string"], "default": null},
                    {"name": "opType", "type": ["null", "string"], "default": null},
                    {"name": "cdhTerminalCode", "type": ["null", "string"], "default": null},
                    {"name": "messageSequenceNumber", "type": ["null", "long"], "default": null},
                    {"name": "vesselVisitCraneDelayId", "type": ["null", "long"], "default": null},
                    {"name": "vesselVisitId", "type": ["null", "string"], "default": null},
                    {"name": "delayStartTime", "type": ["null", "long"], "default": null},
                    {"name": "delayStopTime", "type": ["null", "long"], "default": null},
                    {"name": "cheShortName", "type": ["null", "string"], "default": null},
                    {"name": "delayRemarks", "type": ["null", "string"], "default": null},
                    {"name": "delayType", "type": ["null", "string"], "default": null},
                    {"name": "delayTypeDescription", "type": ["null", "string"], "default": null},
                    {"name": "positionEnum", "type": ["null", "string"], "default": null},
                    {"name": "delayStatus", "type": ["null", "string"], "default": null},
                    {"name": "delayTypeAction", "type": ["null", "string"], "default": null},
                    {"name": "delayTypeCategory", "type": ["null", "string"], "default": null},
                    {"name": "SOURCE_TS_MS", "type": ["null", "long"], "default": null}
                ]
            }
            """;

    private Schema schema;
    private CraneDelayActivityEventMapper mapper;

    @BeforeEach
    void setUp() {
        schema = new Schema.Parser().parse(AVRO_SCHEMA_JSON);
        mapper = new CraneDelayActivityEventMapper();
    }

    @Test
    void shouldMapAllFields() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("eventType", "Crane Delay Occured");
        record.put("opType", "I");
        record.put("cdhTerminalCode", "MAPTM");
        record.put("messageSequenceNumber", 47185161403L);
        record.put("vesselVisitCraneDelayId", 1962142L);
        record.put("vesselVisitId", "411608N");
        record.put("delayStartTime", 1773900725437L);
        record.put("delayStopTime", 1773900845437L);
        record.put("cheShortName", "QCZ8");
        record.put("delayRemarks", "3 HC");
        record.put("delayType", "1.1");
        record.put("delayTypeDescription", "HATCHCOVER GENERIC");
        record.put("positionEnum", "FIXED_START");
        record.put("delayStatus", "CLERK_STARTED");
        record.put("delayTypeAction", "CONTAINER_MOVE_STOPPED");
        record.put("delayTypeCategory", "ABNORMAL");
        record.put("SOURCE_TS_MS", 1773900731312L);

        CraneDelayActivityEvent event = mapper.map(record);

        assertEquals("Crane Delay Occured", event.eventType());
        assertEquals("I", event.opType());
        assertEquals("MAPTM", event.cdhTerminalCode());
        assertEquals(47185161403L, event.messageSequenceNumber());
        assertEquals(1962142L, event.vesselVisitCraneDelayId());
        assertEquals("411608N", event.vesselVisitId());
        assertEquals(Instant.ofEpochMilli(1773900725437L), event.delayStartTime());
        assertEquals(Instant.ofEpochMilli(1773900845437L), event.delayStopTime());
        assertEquals("QCZ8", event.cheShortName());
        assertEquals("3 HC", event.delayRemarks());
        assertEquals("1.1", event.delayType());
        assertEquals("HATCHCOVER GENERIC", event.delayTypeDescription());
        assertEquals("FIXED_START", event.positionEnum());
        assertEquals("CLERK_STARTED", event.delayStatus());
        assertEquals("CONTAINER_MOVE_STOPPED", event.delayTypeAction());
        assertEquals("ABNORMAL", event.delayTypeCategory());
        assertEquals(1773900731312L, event.sourceTsMs());
    }

    @Test
    void shouldHandleNullFields() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("cheShortName", "QC01");

        CraneDelayActivityEvent event = mapper.map(record);

        assertEquals("QC01", event.cheShortName());
        assertNull(event.eventType());
        assertNull(event.delayStartTime());
        assertNull(event.delayStopTime());
        assertNull(event.messageSequenceNumber());
        assertNull(event.sourceTsMs());
    }
}
