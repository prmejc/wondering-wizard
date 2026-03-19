package com.wonderingwizard.kafka;

import com.wonderingwizard.events.ContainerMoveStateEvent;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContainerMoveStateEventMapperTest {

    private static final String AVRO_SCHEMA_JSON = """
            {
                "type": "record",
                "name": "ResponseContainerMoveState",
                "namespace": "apmt.terminaloperations.containermovestate.topic.confidential.status.v1",
                "fields": [
                    {"name": "containerMoveAction", "type": "string"},
                    {"name": "containerMoveStateRequestStatus", "type": "string"},
                    {"name": "responseContainerMoveState", "type": "string"},
                    {"name": "carryCHEName", "type": "string"},
                    {"name": "workInstructionId", "type": "long"},
                    {"name": "moveKind", "type": "string"},
                    {"name": "containerId", "type": "string"},
                    {"name": "terminalCode", "type": "string"},
                    {"name": "errorMessage", "type": "string"},
                    {"name": "SOURCE_TS_MS", "type": "long"}
                ]
            }
            """;

    private Schema schema;
    private ContainerMoveStateEventMapper mapper;

    @BeforeEach
    void setUp() {
        schema = new Schema.Parser().parse(AVRO_SCHEMA_JSON);
        mapper = new ContainerMoveStateEventMapper();
    }

    @Test
    void shouldMapAllFields() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("containerMoveAction", "STOPPED");
        record.put("containerMoveStateRequestStatus", "ERROR");
        record.put("responseContainerMoveState", "TT_ASSIGNED");
        record.put("carryCHEName", "TG04");
        record.put("workInstructionId", 12345L);
        record.put("moveKind", "DSCH");
        record.put("containerId", "MSKU1234567");
        record.put("terminalCode", "MAPTM");
        record.put("errorMessage", "TT unavailable");
        record.put("SOURCE_TS_MS", 1773846543604L);

        ContainerMoveStateEvent event = mapper.map(record);

        assertEquals("STOPPED", event.containerMoveAction());
        assertEquals("ERROR", event.containerMoveStateRequestStatus());
        assertEquals("TT_ASSIGNED", event.responseContainerMoveState());
        assertEquals("TG04", event.carryCHEName());
        assertEquals(12345L, event.workInstructionId());
        assertEquals("DSCH", event.moveKind());
        assertEquals("MSKU1234567", event.containerId());
        assertEquals("MAPTM", event.terminalCode());
        assertEquals("TT unavailable", event.errorMessage());
        assertEquals(1773846543604L, event.sourceTsMs());
    }
}
