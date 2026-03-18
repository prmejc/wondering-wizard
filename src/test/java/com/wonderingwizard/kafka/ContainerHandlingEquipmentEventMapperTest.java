package com.wonderingwizard.kafka;

import com.wonderingwizard.events.CheJobStepState;
import com.wonderingwizard.events.CheStatus;
import com.wonderingwizard.events.ContainerHandlingEquipmentEvent;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class ContainerHandlingEquipmentEventMapperTest {

    private static final String AVRO_SCHEMA_JSON = """
            {
                "type": "record",
                "name": "ContainerHandlingEquipment",
                "namespace": "APMT.terminalOperations.containerHandlingEquipment.topic.confidential.dedicated.v1",
                "fields": [
                    {"name": "eventType", "type": ["null", "string"], "default": null},
                    {"name": "cheId", "type": ["null", "long"], "default": null},
                    {"name": "opType", "type": ["null", "string"], "default": null},
                    {"name": "cdhTerminalCode", "type": ["null", "string"], "default": null},
                    {"name": "messageSequenceNumber", "type": ["null", "long"], "default": null},
                    {"name": "cheShortName", "type": ["null", "string"], "default": null},
                    {"name": "cheStatus", "type": ["null", "string"], "default": null},
                    {"name": "cheKind", "type": ["null", "string"], "default": null},
                    {"name": "chePoolId", "type": ["null", "long"], "default": null},
                    {"name": "cheJobStepState", "type": ["null", "string"], "default": null},
                    {"name": "SOURCE_TS_MS", "type": ["null", "long"], "default": null}
                ]
            }
            """;

    private Schema schema;
    private ContainerHandlingEquipmentEventMapper mapper;

    @BeforeEach
    void setUp() {
        schema = new Schema.Parser().parse(AVRO_SCHEMA_JSON);
        mapper = new ContainerHandlingEquipmentEventMapper();
    }

    @Test
    void shouldMapAllFieldsFromAvro() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("eventType", "CHE Status Changed");
        record.put("cheId", 100L);
        record.put("opType", "U");
        record.put("cdhTerminalCode", "TERM1");
        record.put("messageSequenceNumber", 1L);
        record.put("cheShortName", "TT01");
        record.put("cheStatus", "Working");
        record.put("cheKind", "TT");
        record.put("chePoolId", 23L);
        record.put("cheJobStepState", "IDLE");
        record.put("SOURCE_TS_MS", 1700000000000L);

        ContainerHandlingEquipmentEvent event = mapper.map(record);

        assertEquals("CHE Status Changed", event.eventType());
        assertEquals(100L, event.cheId());
        assertEquals("U", event.opType());
        assertEquals("TERM1", event.cdhTerminalCode());
        assertEquals(1L, event.messageSequenceNumber());
        assertEquals("TT01", event.cheShortName());
        assertEquals(CheStatus.WORKING, event.cheStatus());
        assertEquals("TT", event.cheKind());
        assertEquals(23L, event.chePoolId());
        assertEquals(CheJobStepState.IDLE, event.cheJobStepState());
        assertEquals(1700000000000L, event.sourceTsMs());
    }

    @Test
    void shouldHandleNullFields() {
        GenericRecord record = new GenericData.Record(schema);
        // All fields default to null

        ContainerHandlingEquipmentEvent event = mapper.map(record);

        assertEquals("", event.eventType());
        assertNull(event.cheId());
        assertEquals("", event.opType());
        assertEquals("", event.cdhTerminalCode());
        assertNull(event.messageSequenceNumber());
        assertNull(event.cheShortName());
        assertEquals(CheStatus.WORKING, event.cheStatus());
        assertNull(event.cheKind());
        assertNull(event.chePoolId());
        assertEquals(CheJobStepState.IDLE, event.cheJobStepState());
        assertNull(event.sourceTsMs());
    }

    @ParameterizedTest
    @CsvSource({
            "Working, WORKING",
            "Unavailable, UNAVAILABLE"
    })
    void shouldMapCheStatus(String avroValue, CheStatus expected) {
        GenericRecord record = new GenericData.Record(schema);
        record.put("cheStatus", avroValue);

        ContainerHandlingEquipmentEvent event = mapper.map(record);

        assertEquals(expected, event.cheStatus());
    }

    @Test
    void shouldDefaultUnknownCheStatusToWorking() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("cheStatus", "SomeUnknownStatus");

        ContainerHandlingEquipmentEvent event = mapper.map(record);

        assertEquals(CheStatus.WORKING, event.cheStatus());
    }

    @ParameterizedTest
    @CsvSource({
            "IDLE, IDLE",
            "LOGGEDOUT, LOGGED_OUT",
            "TOROWTOCOLLECTCNTR, TO_ROW_TO_COLLECT_CNTR",
            "ATROWTOCOLLECTCNTR, AT_ROW_TO_COLLECT_CNTR",
            "TOROWTODROPCNTR, TO_ROW_TO_DROP_CNTR",
            "ATROWTODROPCNTR, AT_ROW_TO_DROP_CNTR",
            "TOSHIPTOCOLLECTCNTR, TO_SHIP_TO_COLLECT_CNTR",
            "ATSHIPTOCOLLECTCNTR, AT_SHIP_TO_COLLECT_CNTR",
            "TOSHIPTODROPCNTR, TO_SHIP_TO_DROP_CNTR",
            "ATSHIPTODROPCNTR, AT_SHIP_TO_DROP_CNTR"
    })
    void shouldMapCheJobStepState(String avroValue, CheJobStepState expected) {
        GenericRecord record = new GenericData.Record(schema);
        record.put("cheJobStepState", avroValue);

        ContainerHandlingEquipmentEvent event = mapper.map(record);

        assertEquals(expected, event.cheJobStepState());
    }

    @Test
    void shouldDefaultUnknownJobStepStateToIdle() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("cheJobStepState", "UNKNOWN_STATE");

        ContainerHandlingEquipmentEvent event = mapper.map(record);

        assertEquals(CheJobStepState.IDLE, event.cheJobStepState());
    }

    @Test
    void shouldMapUnavailableTruckEvent() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("eventType", "CHE Status Changed");
        record.put("cheShortName", "TT05");
        record.put("cheStatus", "Unavailable");
        record.put("cheKind", "TT");
        record.put("cheJobStepState", "TOROWTOCOLLECTCNTR");

        ContainerHandlingEquipmentEvent event = mapper.map(record);

        assertEquals("TT05", event.cheShortName());
        assertEquals(CheStatus.UNAVAILABLE, event.cheStatus());
        assertEquals("TT", event.cheKind());
        assertEquals(CheJobStepState.TO_ROW_TO_COLLECT_CNTR, event.cheJobStepState());
    }
}
