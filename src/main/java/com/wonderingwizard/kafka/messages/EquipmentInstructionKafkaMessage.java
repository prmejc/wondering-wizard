package com.wonderingwizard.kafka.messages;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Record representing an EquipmentInstruction message to be published to Kafka.
 * <p>
 * This is the outbound counterpart to {@link WorkInstructionKafkaMessage}.
 * The mapper builds this typed record from side effects, and {@link #toAvro()}
 * converts it to the Avro {@link GenericRecord} required by the Kafka producer.
 */
public record EquipmentInstructionKafkaMessage(
        String equipmentInstructionType,
        String equipmentInstructionId,
        String equipmentInstructionText,
        String destinationNodeId,
        String destinationNodeName,
        long targetTime,
        String recipientCHEShortName,
        String destinationCHEShortName,
        String recipientCHEKind,
        boolean confirmationRequired,
        String moveKind,
        List<Container> containers,
        String fetchCHEShortName,
        String carryCHEShortName,
        String putCHEShortName,
        String eventSource,
        long sourceTsMs,
        String terminalCode
) {

    private static final String SCHEMA_PATH = "/schemas/EquipmentInstruction.avro";
    private static final Schema SCHEMA = loadSchema();
    private static final Schema CONTAINER_SCHEMA = SCHEMA.getField("containers").schema().getElementType();

    /**
     * A container entry within an equipment instruction.
     */
    public record Container(
            long sequence,
            String containerId,
            List<String> instructionDetails,
            String frozenWorkQueueId,
            long workInstructionId,
            String isoType,
            String containerTruckPosition,
            String freightKind,
            String fromPosition,
            String toPosition
    ) {

        GenericRecord toAvro() {
            GenericRecord record = new GenericData.Record(CONTAINER_SCHEMA);
            record.put("sequence", sequence);
            record.put("containerId", containerId);
            record.put("instructionDetails", instructionDetails);
            record.put("frozenWorkQueueId", frozenWorkQueueId);
            record.put("workInstructionId", workInstructionId);
            record.put("isoType", isoType);
            record.put("containerTruckPosition", containerTruckPosition);
            record.put("freightKind", freightKind);
            record.put("fromPosition", fromPosition);
            record.put("toPosition", toPosition);
            return record;
        }
    }

    /**
     * Converts this typed record to an Avro {@link GenericRecord} for Kafka serialization.
     */
    public GenericRecord toAvro() {
        GenericRecord record = new GenericData.Record(SCHEMA);
        record.put("equipmentInstructionType", equipmentInstructionType);
        record.put("equipmentInstructionId", equipmentInstructionId);
        record.put("equipmentInstructionText", equipmentInstructionText);
        record.put("destinationNodeId", destinationNodeId);
        record.put("destinationNodeName", destinationNodeName);
        record.put("targetTime", targetTime);
        record.put("recipientCHEShortName", recipientCHEShortName);
        record.put("destinationCHEShortName", destinationCHEShortName);
        record.put("recipientCHEKind", recipientCHEKind);
        record.put("confirmationRequired", confirmationRequired);
        record.put("moveKind", moveKind);
        record.put("containers", containers.stream().map(Container::toAvro).toList());
        record.put("fetchCHEShortName", fetchCHEShortName);
        record.put("carryCHEShortName", carryCHEShortName);
        record.put("putCHEShortName", putCHEShortName);
        record.put("eventSource", eventSource);
        record.put("SOURCE_TS_MS", sourceTsMs);
        record.put("terminalCode", terminalCode);
        return record;
    }

    private static Schema loadSchema() {
        try (InputStream is = EquipmentInstructionKafkaMessage.class.getResourceAsStream(SCHEMA_PATH)) {
            if (is == null) {
                throw new IllegalStateException("EquipmentInstruction schema not found at " + SCHEMA_PATH);
            }
            return new Schema.Parser().parse(is);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load EquipmentInstruction schema", e);
        }
    }
}
