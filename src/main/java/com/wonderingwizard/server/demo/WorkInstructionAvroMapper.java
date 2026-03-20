package com.wonderingwizard.server.demo;

import com.wonderingwizard.events.WorkInstructionEvent;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

import java.io.IOException;
import java.io.InputStream;

/**
 * Maps {@link WorkInstructionEvent} to Avro {@link GenericRecord} for publishing to the WI Kafka topic.
 * <p>
 * This mapper is used exclusively by the DemoServer to send WI events through Kafka
 * instead of directly to the engine. It is NOT part of the production Kafka pipeline.
 */
public class WorkInstructionAvroMapper {

    private static final String SCHEMA_PATH = "/schemas/WorkInstruction.avro";
    private static final Schema SCHEMA = loadSchema();

    public GenericRecord toAvro(WorkInstructionEvent event) {
        GenericRecord record = new GenericData.Record(SCHEMA);
        record.put("eventType", event.eventType());
        record.put("workInstructionId", event.workInstructionId());
        record.put("workQueueId", event.workQueueId());
        record.put("workInstructionMoveStage", event.workInstructionMoveStage());
        record.put("fetchCHEName", event.fetchChe());
        record.put("putCHEName", event.putChe());
        record.put("moveKind", event.moveKind());
        record.put("containerId", event.containerId());
        record.put("fromPosition", event.fromPosition());
        record.put("toPosition", event.toPosition());
        record.put("isTwinFetch", event.isTwinFetch());
        record.put("isTwinPut", event.isTwinPut());
        record.put("isTwinCarry", event.isTwinCarry());
        record.put("twinCompanionWorkInstruction", event.twinCompanionWorkInstruction());
        record.put("jobPosition", event.jobPosition());
        record.put("isoType", event.isoType());
        record.put("freightKind", event.freightKind());
        record.put("pinning", event.pinning());

        if (event.estimatedMoveTime() != null) {
            record.put("estimatedMoveTime", event.estimatedMoveTime().toEpochMilli());
        }
        if (event.estimatedCycleTimeSeconds() > 0) {
            record.put("estimatedCycleTime", (double) event.estimatedCycleTimeSeconds());
        }
        if (event.estimatedRtgCycleTimeSeconds() > 0) {
            record.put("estimatedRTGCycleTime", (double) event.estimatedRtgCycleTimeSeconds());
        }

        record.put("SOURCE_TS_MS", System.currentTimeMillis());
        return record;
    }

    private static Schema loadSchema() {
        try (InputStream is = WorkInstructionAvroMapper.class.getResourceAsStream(SCHEMA_PATH)) {
            if (is == null) {
                throw new IllegalStateException("WorkInstruction schema not found at " + SCHEMA_PATH);
            }
            return new Schema.Parser().parse(is);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load WorkInstruction schema", e);
        }
    }
}
