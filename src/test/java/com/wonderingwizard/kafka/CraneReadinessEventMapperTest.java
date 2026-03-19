package com.wonderingwizard.kafka;

import com.wonderingwizard.events.CraneReadinessEvent;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class CraneReadinessEventMapperTest {

    private static final String AVRO_SCHEMA_JSON = """
            {
                "type": "record",
                "name": "CraneReadiness",
                "namespace": "com.apmt.terminaloperations.cranereadiness.internal.dedicated.v1",
                "fields": [
                    {"name": "eventId", "type": {"type": "string", "logicalType": "uuid"}},
                    {"name": "qcShortName", "type": "string"},
                    {"name": "workQueueId", "type": "long"},
                    {"name": "updatedBy", "type": ["null", "string"], "default": null},
                    {"name": "qcResumeTimestamp", "type": "long", "logicalType": "timestamp-millis"}
                ]
            }
            """;

    private Schema schema;
    private CraneReadinessEventMapper mapper;

    @BeforeEach
    void setUp() {
        schema = new Schema.Parser().parse(AVRO_SCHEMA_JSON);
        mapper = new CraneReadinessEventMapper();
    }

    @Test
    void shouldMapAllFields() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("eventId", "550e8400-e29b-41d4-a716-446655440000");
        record.put("qcShortName", "QCZ9");
        record.put("workQueueId", 42L);
        record.put("updatedBy", "operator1");
        record.put("qcResumeTimestamp", 1700000000000L);

        CraneReadinessEvent event = mapper.map(record);

        assertEquals("QCZ9", event.qcShortName());
        assertEquals(42L, event.workQueueId());
        assertEquals(Instant.ofEpochMilli(1700000000000L), event.qcResumeTimestamp());
        assertEquals("operator1", event.updatedBy());
        assertEquals("550e8400-e29b-41d4-a716-446655440000", event.eventId());
    }

    @Test
    void shouldHandleNullUpdatedBy() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("eventId", "abc-123");
        record.put("qcShortName", "QC01");
        record.put("workQueueId", 99L);
        record.put("updatedBy", null);
        record.put("qcResumeTimestamp", 0L);

        CraneReadinessEvent event = mapper.map(record);

        assertEquals("QC01", event.qcShortName());
        assertEquals(99L, event.workQueueId());
        assertNull(event.updatedBy());
    }
}
