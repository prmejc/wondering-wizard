package com.wonderingwizard.kafka.messages;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

import java.io.IOException;
import java.io.InputStream;

/**
 * Record representing a ContainerMoveState message to be published to Kafka.
 * <p>
 * Built from {@link com.wonderingwizard.sideeffects.TruckAssigned} side effects
 * to notify T-ONE that a truck has been assigned to a work instruction.
 */
public record ContainerMoveStateKafkaMessage(
        String containerMoveState,
        String fetchCHEName,
        String carryCHEName,
        String putCHEName,
        long workInstructionId,
        String moveKind,
        String fromPosition,
        String toPosition,
        String containerId,
        int messageSequenceNumber,
        String terminalCode,
        String eventSource,
        long sourceTsMs
) {

    private static final String SCHEMA_PATH = "/schemas/ContainerMoveState.avro";
    private static final Schema SCHEMA = loadSchema();

    /**
     * Converts this typed message to an Avro {@link GenericRecord} for Kafka publishing.
     */
    public GenericRecord toAvro() {
        GenericRecord record = new GenericData.Record(SCHEMA);
        record.put("containerMoveState", containerMoveState);
        record.put("fetchCHEName", fetchCHEName);
        record.put("carryCHEName", carryCHEName);
        record.put("putCHEName", putCHEName);
        record.put("workInstructionId", workInstructionId);
        record.put("moveKind", moveKind);
        record.put("fromPosition", fromPosition);
        record.put("toPosition", toPosition);
        record.put("containerId", containerId);
        record.put("messageSequenceNumber", messageSequenceNumber);
        record.put("terminalCode", terminalCode);
        record.put("eventSource", eventSource);
        record.put("SOURCE_TS_MS", sourceTsMs);
        return record;
    }

    private static Schema loadSchema() {
        try (InputStream is = ContainerMoveStateKafkaMessage.class.getResourceAsStream(SCHEMA_PATH)) {
            if (is == null) {
                throw new IllegalStateException("Schema not found: " + SCHEMA_PATH);
            }
            return new Schema.Parser().parse(is);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load Avro schema: " + SCHEMA_PATH, e);
        }
    }
}
