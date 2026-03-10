package com.wonderingwizard.kafka;

import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.WorkInstructionStatus;
import com.wonderingwizard.kafka.messages.WorkInstructionKafkaMessage;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class WorkInstructionEventMapperTest {

    private static final String AVRO_SCHEMA_JSON = """
            {
                "type": "record",
                "name": "WorkInstruction",
                "namespace": "APMT.terminalOperations.workInstruction.topic.confidential.dedicated.v1",
                "fields": [
                    {"name": "eventType", "type": ["null", "string"], "default": null},
                    {"name": "opType", "type": ["null", "string"], "default": null},
                    {"name": "cdhTerminalCode", "type": ["null", "string"], "default": null},
                    {"name": "messageSequenceNumber", "type": ["null", "long"], "default": null},
                    {"name": "workInstructionId", "type": ["null", "long"], "default": null},
                    {"name": "workQueueId", "type": ["null", "long"], "default": null},
                    {"name": "stateFetch", "type": ["null", "string"], "default": null},
                    {"name": "workInstructionQueueSequence", "type": ["null", "long"], "default": null},
                    {"name": "moveKind", "type": ["null", "string"], "default": null},
                    {"name": "workInstructionMoveStage", "type": ["null", "string"], "default": null},
                    {"name": "fetchCHEName", "type": ["null", "string"], "default": null},
                    {"name": "carryCHEName", "type": ["null", "string"], "default": null},
                    {"name": "putCHEName", "type": ["null", "string"], "default": null},
                    {"name": "estimatedMoveTime", "type": ["null", "long"], "default": null},
                    {"name": "estimatedCycleTime", "type": ["null", "double"], "default": null},
                    {"name": "estimatedRTGCycleTime", "type": ["null", "double"], "default": null},
                    {"name": "estimatedEHCycleTime", "type": ["null", "double"], "default": null},
                    {"name": "containerId", "type": ["null", "string"], "default": null},
                    {"name": "SOURCE_TS_MS", "type": ["null", "long"], "default": null},
                    {"name": "isTwinFetch", "type": ["null", "boolean"], "default": null},
                    {"name": "isTwinPut", "type": ["null", "boolean"], "default": null},
                    {"name": "isTwinCarry", "type": ["null", "boolean"], "default": null},
                    {"name": "twinCompanionWorkInstruction", "type": ["null", "long"], "default": null}
                ]
            }
            """;

    private Schema schema;
    private WorkInstructionEventMapper mapper;

    @BeforeEach
    void setUp() {
        schema = new Schema.Parser().parse(AVRO_SCHEMA_JSON);
        mapper = new WorkInstructionEventMapper();
    }

    @Test
    void shouldMapPendingWorkInstructionFromAvro() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("workInstructionId", 101L);
        record.put("workQueueId", 42L);
        record.put("fetchCHEName", "RTG-01");
        record.put("workInstructionMoveStage", "PLANNED");
        record.put("estimatedMoveTime", 1700000000000L);
        record.put("estimatedCycleTime", 120.0);
        record.put("estimatedRTGCycleTime", 45.0);
        record.put("putCHEName", "QC-01");
        record.put("isTwinFetch", true);
        record.put("isTwinPut", false);
        record.put("isTwinCarry", true);
        record.put("twinCompanionWorkInstruction", 202L);

        WorkInstructionEvent event = mapper.map(record);

        assertEquals(101L, event.workInstructionId());
        assertEquals(42L, event.workQueueId());
        assertEquals("RTG-01", event.fetchChe());
        assertEquals("QC-01", event.putChe());
        assertEquals(WorkInstructionStatus.PENDING, event.status());
        assertEquals(Instant.ofEpochMilli(1700000000000L), event.estimatedMoveTime());
        assertEquals(120, event.estimatedCycleTimeSeconds());
        assertEquals(45, event.estimatedRtgCycleTimeSeconds());
        assertTrue(event.isTwinFetch());
        assertFalse(event.isTwinPut());
        assertTrue(event.isTwinCarry());
        assertEquals(202L, event.twinCompanionWorkInstruction());
    }

    @Test
    void shouldUseDefaultRtgCycleTimeWhenNull() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("workInstructionId", 1L);
        record.put("workQueueId", 1L);
        record.put("workInstructionMoveStage", "PLANNED");
        record.put("estimatedRTGCycleTime", null);

        WorkInstructionEvent event = mapper.map(record);

        assertEquals(60, event.estimatedRtgCycleTimeSeconds());
    }

    @Test
    void shouldHandleNullEstimatedMoveTime() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("workInstructionId", 1L);
        record.put("workQueueId", 1L);
        record.put("estimatedMoveTime", null);

        WorkInstructionEvent event = mapper.map(record);

        assertNull(event.estimatedMoveTime());
    }

    @Test
    void shouldHandleNullCycleTime() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("workInstructionId", 1L);
        record.put("workQueueId", 1L);
        record.put("estimatedCycleTime", null);

        WorkInstructionEvent event = mapper.map(record);

        assertEquals(0, event.estimatedCycleTimeSeconds());
    }

    @Test
    void shouldHandleNullPutCHEName() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("workInstructionId", 1L);
        record.put("workQueueId", 1L);
        record.put("putCHEName", null);

        WorkInstructionEvent event = mapper.map(record);

        assertEquals("", event.putChe());
    }

    @Test
    void shouldHandleNullTwinFields() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("workInstructionId", 1L);
        record.put("workQueueId", 1L);

        WorkInstructionEvent event = mapper.map(record);

        assertFalse(event.isTwinFetch());
        assertFalse(event.isTwinPut());
        assertFalse(event.isTwinCarry());
        assertEquals(0L, event.twinCompanionWorkInstruction());
    }

    @Test
    void shouldHandleNullFetchCHEName() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("workInstructionId", 1L);
        record.put("workQueueId", 1L);
        record.put("fetchCHEName", null);

        WorkInstructionEvent event = mapper.map(record);

        assertEquals("", event.fetchChe());
    }

    @Test
    void shouldHandleNullIds() {
        GenericRecord record = new GenericData.Record(schema);
        // All null

        WorkInstructionEvent event = mapper.map(record);

        assertEquals(0L, event.workInstructionId());
        assertEquals(0L, event.workQueueId());
    }

    @ParameterizedTest
    @CsvSource({
            "PLANNED, PENDING",
            "READY, PENDING",
            "CARRY_UNDERWAY, IN_PROGRESS",
            "FETCH_UNDERWAY, IN_PROGRESS",
            "PUT_UNDERWAY, IN_PROGRESS",
            "COMPLETE, COMPLETED",
            "COMPLETED, COMPLETED",
            "CANCELLED, CANCELLED",
            "CANCEL, CANCELLED"
    })
    void shouldMapMoveStageToStatus(String moveStage, String expectedStatus) {
        GenericRecord record = new GenericData.Record(schema);
        record.put("workInstructionId", 1L);
        record.put("workQueueId", 1L);
        record.put("workInstructionMoveStage", moveStage);

        WorkInstructionEvent event = mapper.map(record);

        assertEquals(WorkInstructionStatus.valueOf(expectedStatus), event.status());
    }

    @Test
    void shouldMapNullMoveStageToPlanned() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("workInstructionId", 1L);
        record.put("workQueueId", 1L);
        record.put("workInstructionMoveStage", null);

        WorkInstructionEvent event = mapper.map(record);

        assertEquals(WorkInstructionStatus.PENDING, event.status());
    }

    @Test
    void shouldMapUnknownMoveStageToPlanned() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("workInstructionId", 1L);
        record.put("workQueueId", 1L);
        record.put("workInstructionMoveStage", "SOME_UNKNOWN_STAGE");

        WorkInstructionEvent event = mapper.map(record);

        assertEquals(WorkInstructionStatus.PENDING, event.status());
    }

    @Test
    void shouldExtractAllFieldsFromAvro() {
        GenericRecord record = new GenericData.Record(schema);
        record.put("eventType", "INSERT");
        record.put("opType", "CREATE");
        record.put("cdhTerminalCode", "TC1");
        record.put("messageSequenceNumber", 456L);
        record.put("workInstructionId", 101L);
        record.put("workQueueId", 42L);
        record.put("stateFetch", "READY");
        record.put("workInstructionQueueSequence", 3L);
        record.put("moveKind", "DISCHARGE");
        record.put("workInstructionMoveStage", "PLANNED");
        record.put("fetchCHEName", "RTG-01");
        record.put("carryCHEName", "TT-05");
        record.put("putCHEName", "QC-02");
        record.put("estimatedMoveTime", 1700000000000L);
        record.put("estimatedCycleTime", 120.5);
        record.put("estimatedRTGCycleTime", 45.3);
        record.put("estimatedEHCycleTime", 30.0);
        record.put("containerId", "MAEU1234567");
        record.put("SOURCE_TS_MS", 1700000000000L);
        record.put("isTwinFetch", true);
        record.put("isTwinPut", false);
        record.put("isTwinCarry", true);
        record.put("twinCompanionWorkInstruction", 999L);

        WorkInstructionKafkaMessage kafkaMessage = mapper.fromAvro(record);

        assertEquals("INSERT", kafkaMessage.eventType());
        assertEquals("CREATE", kafkaMessage.opType());
        assertEquals("TC1", kafkaMessage.cdhTerminalCode());
        assertEquals(456L, kafkaMessage.messageSequenceNumber());
        assertEquals(101L, kafkaMessage.workInstructionId());
        assertEquals(42L, kafkaMessage.workQueueId());
        assertEquals("READY", kafkaMessage.stateFetch());
        assertEquals(3L, kafkaMessage.workInstructionQueueSequence());
        assertEquals("DISCHARGE", kafkaMessage.moveKind());
        assertEquals("PLANNED", kafkaMessage.workInstructionMoveStage());
        assertEquals("RTG-01", kafkaMessage.fetchCHEName());
        assertEquals("TT-05", kafkaMessage.carryCHEName());
        assertEquals("QC-02", kafkaMessage.putCHEName());
        assertEquals(1700000000000L, kafkaMessage.estimatedMoveTime());
        assertEquals(120.5, kafkaMessage.estimatedCycleTime());
        assertEquals(45.3, kafkaMessage.estimatedRTGCycleTime());
        assertEquals(30.0, kafkaMessage.estimatedEHCycleTime());
        assertEquals("MAEU1234567", kafkaMessage.containerId());
        assertEquals(1700000000000L, kafkaMessage.sourceTsMs());
        assertEquals(true, kafkaMessage.isTwinFetch());
        assertEquals(false, kafkaMessage.isTwinPut());
        assertEquals(true, kafkaMessage.isTwinCarry());
        assertEquals(999L, kafkaMessage.twinCompanionWorkInstruction());
    }
}
