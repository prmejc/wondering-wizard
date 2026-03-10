package com.wonderingwizard.kafka;

import com.wonderingwizard.events.LoadMode;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.events.WorkQueueStatus;
import com.wonderingwizard.kafka.messages.WorkQueueKafkaMessage;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class WorkQueueEventMapperTest {

    private static final String AVRO_SCHEMA_JSON = """
            {
                "type": "record",
                "name": "WorkQueue",
                "namespace": "APMT.terminalOperations.workQueue.topic.confidential.dedicated.v1",
                "fields": [
                    {"name": "eventType", "type": ["null", "string"], "default": null},
                    {"name": "opType", "type": ["null", "string"], "default": null},
                    {"name": "cdhTerminalCode", "type": ["null", "string"], "default": null},
                    {"name": "messageSequenceNumber", "type": ["null", "long"], "default": null},
                    {"name": "workQueueId", "type": ["null", "long"], "default": null},
                    {"name": "vesselVisitId", "type": ["null", "string"], "default": null},
                    {"name": "vesselName", "type": ["null", "string"], "default": null},
                    {"name": "vesselRow", "type": ["null", "string"], "default": null},
                    {"name": "vesselDeck", "type": ["null", "string"], "default": null},
                    {"name": "workQueueName", "type": ["null", "string"], "default": null},
                    {"name": "workQueueStatus", "type": ["null", "string"], "default": null},
                    {"name": "workQueueSequence", "type": ["null", "string"], "default": null},
                    {"name": "workQueueType", "type": ["null", "string"], "default": null},
                    {"name": "pointOfWorkName", "type": ["null", "string"], "default": null},
                    {"name": "load_mode", "type": ["null", "string"], "default": null},
                    {"name": "note", "type": ["null", "string"], "default": null},
                    {"name": "workQueueCycleCompanionId", "type": ["null", "long"], "default": null},
                    {"name": "workQueueDoubleCycleFromSequence", "type": ["null", "long"], "default": null},
                    {"name": "workQueueDoubleCycleToSequence", "type": ["null", "long"], "default": null},
                    {"name": "vesselOrientation", "type": ["null", "string"], "default": null},
                    {"name": "bollardPosition", "type": ["null", "string"], "default": null},
                    {"name": "mudaQCCycleTime", "type": ["null", "long"], "default": null},
                    {"name": "workQueueManaged", "type": ["null", "string"], "default": null},
                    {"name": "SOURCE_TS_MS", "type": ["null", "long"], "default": null}
                ]
            }
            """;

    private Schema schema;
    private WorkQueueEventMapper mapper;

    @BeforeEach
    void setUp() {
        schema = new Schema.Parser().parse(AVRO_SCHEMA_JSON);
        mapper = new WorkQueueEventMapper();
    }

    @Test
    void shouldMapActiveWorkQueueFromAvro() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("workQueueId", 42L);
        record.put("workQueueStatus", "ACTIVE");
        record.put("mudaQCCycleTime", 15L);
        record.put("workQueueName", "WQ-042");

        WorkQueueMessage event = mapper.map(record);

        assertEquals(42L, event.workQueueId());
        assertEquals(WorkQueueStatus.ACTIVE, event.status());
        assertEquals(15, event.qcMudaSeconds());
    }

    @Test
    void shouldMapInactiveWorkQueueFromAvro() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("workQueueId", 99L);
        record.put("workQueueStatus", "COMPLETE");
        record.put("mudaQCCycleTime", null);

        WorkQueueMessage event = mapper.map(record);

        assertEquals(99L, event.workQueueId());
        assertEquals(WorkQueueStatus.INACTIVE, event.status());
        assertEquals(0, event.qcMudaSeconds());
    }

    @Test
    void shouldUseZeroWhenIdIsNull() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("workQueueId", null);
        record.put("workQueueName", "WQ-FALLBACK");
        record.put("workQueueStatus", "ACTIVE");

        WorkQueueMessage event = mapper.map(record);

        assertEquals(0L, event.workQueueId());
    }

    @Test
    void shouldHandleNullMudaCycleTime() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("workQueueId", 1L);
        record.put("workQueueStatus", "ACTIVE");
        record.put("mudaQCCycleTime", null);

        WorkQueueMessage event = mapper.map(record);

        assertEquals(0, event.qcMudaSeconds());
    }

    @ParameterizedTest
    @CsvSource({
            "ACTIVE, ACTIVE",
            "WORKING, ACTIVE",
            "CREATED, ACTIVE",
            "INACTIVE, INACTIVE",
            "COMPLETE, INACTIVE",
            "CANCELLED, INACTIVE",
            "DELETED, INACTIVE"
    })
    void shouldMapKafkaStatusToEngineStatus(String kafkaStatus, String expectedEngineStatus) {
        GenericRecord record = new GenericData.Record(schema);
        record.put("workQueueId", 1L);
        record.put("workQueueStatus", kafkaStatus);

        WorkQueueMessage event = mapper.map(record);

        assertEquals(WorkQueueStatus.valueOf(expectedEngineStatus), event.status());
    }

    @Test
    void shouldMapUnknownStatusToInactive() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("workQueueId", 1L);
        record.put("workQueueStatus", "SOME_UNKNOWN_STATUS");

        WorkQueueMessage event = mapper.map(record);

        assertEquals(WorkQueueStatus.INACTIVE, event.status());
    }

    @Test
    void shouldHandleNullStatus() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("workQueueId", 1L);
        record.put("workQueueStatus", null);

        WorkQueueMessage event = mapper.map(record);

        assertNull(event.status());
    }

    @Test
    void shouldExtractAllFieldsFromAvro() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("eventType", "INSERT");
        record.put("opType", "CREATE");
        record.put("cdhTerminalCode", "TC1");
        record.put("messageSequenceNumber", 123L);
        record.put("workQueueId", 42L);
        record.put("vesselVisitId", "VV-001");
        record.put("vesselName", "MAERSK ALABAMA");
        record.put("vesselRow", "R01");
        record.put("vesselDeck", "D01");
        record.put("workQueueName", "WQ-042");
        record.put("workQueueStatus", "ACTIVE");
        record.put("workQueueSequence", "1");
        record.put("workQueueType", "DISCHARGE");
        record.put("pointOfWorkName", "QC01");
        record.put("load_mode", "SINGLE");
        record.put("note", "Test note");
        record.put("workQueueCycleCompanionId", 100L);
        record.put("workQueueDoubleCycleFromSequence", 1L);
        record.put("workQueueDoubleCycleToSequence", 5L);
        record.put("vesselOrientation", "PORT");
        record.put("bollardPosition", "B12");
        record.put("mudaQCCycleTime", 30L);
        record.put("workQueueManaged", "Y");
        record.put("SOURCE_TS_MS", 1700000000000L);

        WorkQueueKafkaMessage kafkaMessage = mapper.fromAvro(record);

        assertEquals("INSERT", kafkaMessage.eventType());
        assertEquals("CREATE", kafkaMessage.opType());
        assertEquals("TC1", kafkaMessage.cdhTerminalCode());
        assertEquals(123L, kafkaMessage.messageSequenceNumber());
        assertEquals(42L, kafkaMessage.workQueueId());
        assertEquals("VV-001", kafkaMessage.vesselVisitId());
        assertEquals("MAERSK ALABAMA", kafkaMessage.vesselName());
        assertEquals("R01", kafkaMessage.vesselRow());
        assertEquals("D01", kafkaMessage.vesselDeck());
        assertEquals("WQ-042", kafkaMessage.workQueueName());
        assertEquals("ACTIVE", kafkaMessage.workQueueStatus());
        assertEquals("1", kafkaMessage.workQueueSequence());
        assertEquals("DISCHARGE", kafkaMessage.workQueueType());
        assertEquals("QC01", kafkaMessage.pointOfWorkName());
        assertEquals("SINGLE", kafkaMessage.loadMode());
        assertEquals("Test note", kafkaMessage.note());
        assertEquals(100L, kafkaMessage.workQueueCycleCompanionId());
        assertEquals(1L, kafkaMessage.workQueueDoubleCycleFromSequence());
        assertEquals(5L, kafkaMessage.workQueueDoubleCycleToSequence());
        assertEquals("PORT", kafkaMessage.vesselOrientation());
        assertEquals("B12", kafkaMessage.bollardPosition());
        assertEquals(30L, kafkaMessage.mudaQCCycleTime());
        assertEquals("Y", kafkaMessage.workQueueManaged());
        assertEquals(1700000000000L, kafkaMessage.sourceTsMs());
    }

    @Test
    void shouldMapLoadModeLoad() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("workQueueId", 1L);
        record.put("workQueueStatus", "ACTIVE");
        record.put("load_mode", "LOAD");

        WorkQueueMessage event = mapper.map(record);

        assertEquals(LoadMode.LOAD, event.loadMode());
    }

    @Test
    void shouldMapLoadModeDsch() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("workQueueId", 1L);
        record.put("workQueueStatus", "ACTIVE");
        record.put("load_mode", "DSCH");

        WorkQueueMessage event = mapper.map(record);

        assertEquals(LoadMode.DSCH, event.loadMode());
    }

    @Test
    void shouldMapNullLoadModeToNull() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("workQueueId", 1L);
        record.put("workQueueStatus", "ACTIVE");
        record.put("load_mode", null);

        WorkQueueMessage event = mapper.map(record);

        assertNull(event.loadMode());
    }

    @Test
    void shouldMapUnknownLoadModeToDsch() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("workQueueId", 1L);
        record.put("workQueueStatus", "ACTIVE");
        record.put("load_mode", "UNKNOWN_MODE");

        WorkQueueMessage event = mapper.map(record);

        assertEquals(LoadMode.DSCH, event.loadMode());
    }

    @Test
    void shouldHandleAllNullFields() {
        GenericRecord record = new GenericData.Record(schema);
        // All fields default to null per Avro schema

        WorkQueueKafkaMessage kafkaMessage = mapper.fromAvro(record);

        assertNull(kafkaMessage.eventType());
        assertNull(kafkaMessage.workQueueId());
        assertNull(kafkaMessage.workQueueStatus());
        assertNull(kafkaMessage.mudaQCCycleTime());
    }
}
