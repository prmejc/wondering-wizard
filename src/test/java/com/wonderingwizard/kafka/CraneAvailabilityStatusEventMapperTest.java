package com.wonderingwizard.kafka;

import com.wonderingwizard.events.CraneAvailabilityStatus;
import com.wonderingwizard.events.CraneAvailabilityStatusEvent;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CraneAvailabilityStatusEventMapperTest {

    private static final String AVRO_SCHEMA_JSON = """
            {
                "type": "record",
                "name": "CraneAvailabilityStatus",
                "namespace": "apmt.terminaloperations.craneavailabilitystatus.topic.confidential.dedicated.v1",
                "fields": [
                    {"name": "terminalCode", "type": "string"},
                    {"name": "cheId", "type": "string"},
                    {"name": "cheType", "type": "string"},
                    {"name": "cheStatus", "type": "string"},
                    {"name": "SOURCE_TS_MS", "type": "long"}
                ]
            }
            """;

    private Schema schema;
    private CraneAvailabilityStatusEventMapper mapper;

    @BeforeEach
    void setUp() {
        schema = new Schema.Parser().parse(AVRO_SCHEMA_JSON);
        mapper = new CraneAvailabilityStatusEventMapper();
    }

    @Test
    void shouldMapReadyStatus() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("terminalCode", "MAPTM");
        record.put("cheId", "QCZ9");
        record.put("cheType", "STS");
        record.put("cheStatus", "READY");
        record.put("SOURCE_TS_MS", 1700000000000L);

        CraneAvailabilityStatusEvent event = mapper.map(record);

        assertEquals("MAPTM", event.terminalCode());
        assertEquals("QCZ9", event.cheId());
        assertEquals("STS", event.cheType());
        assertEquals(CraneAvailabilityStatus.READY, event.cheStatus());
        assertEquals(1700000000000L, event.sourceTsMs());
    }

    @Test
    void shouldMapNotReadyStatus() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("terminalCode", "MAPTM");
        record.put("cheId", "QC01");
        record.put("cheType", "STS");
        record.put("cheStatus", "NOT_READY");
        record.put("SOURCE_TS_MS", 0L);

        CraneAvailabilityStatusEvent event = mapper.map(record);

        assertEquals(CraneAvailabilityStatus.NOT_READY, event.cheStatus());
    }

    @Test
    void shouldDefaultToNotReadyForUnknownStatus() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("terminalCode", "TEST");
        record.put("cheId", "QC01");
        record.put("cheType", "STS");
        record.put("cheStatus", "SOMETHING_ELSE");
        record.put("SOURCE_TS_MS", 0L);

        CraneAvailabilityStatusEvent event = mapper.map(record);

        assertEquals(CraneAvailabilityStatus.NOT_READY, event.cheStatus());
    }
}
