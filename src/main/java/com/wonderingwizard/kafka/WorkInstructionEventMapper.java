package com.wonderingwizard.kafka;

import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.kafka.messages.WorkInstructionKafkaMessage;
import org.apache.avro.generic.GenericRecord;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * Maps Kafka Avro WorkInstruction messages to engine {@link WorkInstructionEvent} events.
 * <p>
 * Extracts relevant fields from the Avro record and maps them to the engine's domain model.
 */
public class WorkInstructionEventMapper implements EventMapper<WorkInstructionEvent> {

    private static final Logger logger = Logger.getLogger(WorkInstructionEventMapper.class.getName());
    private static final int DEFAULT_RTG_CYCLE_TIME_SECONDS = 60;

    @Override
    public WorkInstructionEvent map(GenericRecord record) {
        WorkInstructionKafkaMessage kafkaMessage = fromAvro(record);
        return toEngineEvent(kafkaMessage);
    }

    /**
     * Extract fields from Avro GenericRecord into a typed WorkInstructionKafkaMessage.
     */
    WorkInstructionKafkaMessage fromAvro(GenericRecord record) {
        return new WorkInstructionKafkaMessage(
                getStringField(record, "eventType"),
                getStringField(record, "opType"),
                getStringField(record, "cdhTerminalCode"),
                getLongField(record, "messageSequenceNumber"),
                getLongField(record, "workInstructionId"),
                getLongField(record, "workQueueId"),
                getStringField(record, "stateFetch"),
                getLongField(record, "workInstructionQueueSequence"),
                getStringField(record, "moveKind"),
                getStringField(record, "workInstructionMoveStage"),
                getStringField(record, "fetchCHEName"),
                getStringField(record, "carryCHEName"),
                getStringField(record, "putCHEName"),
                getLongField(record, "estimatedMoveTime"),
                getDoubleField(record, "estimatedCycleTime"),
                getDoubleField(record, "estimatedRTGCycleTime"),
                getDoubleField(record, "estimatedEHCycleTime"),
                getStringField(record, "containerId"),
                getLongField(record, "SOURCE_TS_MS"),
                getBooleanField(record, "isTwinFetch"),
                getBooleanField(record, "isTwinPut"),
                getBooleanField(record, "isTwinCarry"),
                getLongField(record, "twinCompanionWorkInstruction"),
                getStringField(record, "fromPosition"),
                getStringField(record, "toPosition"),
                getStringField(record, "jobPosition"),
                getStringField(record, "isoType"),
                getStringField(record, "freightKind"),
                getStringField(record, "pinning")
        );
    }

    /**
     * Convert a typed WorkInstructionKafkaMessage to the engine's WorkInstructionEvent.
     */
    WorkInstructionEvent toEngineEvent(WorkInstructionKafkaMessage kafkaMessage) {
        String eventType = kafkaMessage.eventType() != null
                ? kafkaMessage.eventType()
                : "";

        long workInstructionId = kafkaMessage.workInstructionId() != null
                ? kafkaMessage.workInstructionId()
                : 0;

        long workQueueId = kafkaMessage.workQueueId() != null
                ? kafkaMessage.workQueueId()
                : 0;

        String fetchChe = kafkaMessage.fetchCHEName() != null
                ? kafkaMessage.fetchCHEName()
                : "";

        String workInstructionMoveStage = kafkaMessage.workInstructionMoveStage() != null
                ? kafkaMessage.workInstructionMoveStage()
                : "Planned";

        Instant estimatedMoveTime = kafkaMessage.estimatedMoveTime() != null
                ? Instant.ofEpochMilli(kafkaMessage.estimatedMoveTime())
                : null;

        int estimatedCycleTimeSeconds = kafkaMessage.estimatedCycleTime() != null
                ? (int) Math.round(kafkaMessage.estimatedCycleTime())
                : 0;

        int estimatedRtgCycleTimeSeconds = kafkaMessage.estimatedRTGCycleTime() != null
                ? (int) Math.round(kafkaMessage.estimatedRTGCycleTime())
                : DEFAULT_RTG_CYCLE_TIME_SECONDS;

        String putChe = kafkaMessage.putCHEName() != null
                ? kafkaMessage.putCHEName()
                : "";

        boolean isTwinFetch = Boolean.TRUE.equals(kafkaMessage.isTwinFetch());
        boolean isTwinPut = Boolean.TRUE.equals(kafkaMessage.isTwinPut());
        boolean isTwinCarry = Boolean.TRUE.equals(kafkaMessage.isTwinCarry());

        long twinCompanionWorkInstruction = kafkaMessage.twinCompanionWorkInstruction() != null
                ? kafkaMessage.twinCompanionWorkInstruction()
                : 0;

        String fromPosition = kafkaMessage.fromPosition() != null
                ? kafkaMessage.fromPosition()
                : "";

        String toPosition = kafkaMessage.toPosition() != null
                ? kafkaMessage.toPosition()
                : "";

        String containerId = kafkaMessage.containerId() != null
                ? kafkaMessage.containerId()
                : "";

        String moveKind = kafkaMessage.moveKind() != null
                ? kafkaMessage.moveKind()
                : "";

        String jobPosition = kafkaMessage.jobPosition() != null
                ? kafkaMessage.jobPosition()
                : "FWD";

        String isoType = kafkaMessage.isoType() != null ? kafkaMessage.isoType() : "";
        String freightKind = kafkaMessage.freightKind() != null ? kafkaMessage.freightKind() : "";
        String pinning = kafkaMessage.pinning() != null ? kafkaMessage.pinning() : "";

        logger.fine("Mapped WorkInstruction Kafka message: workInstructionId=" + workInstructionId
                + ", workQueueId=" + workQueueId + ", workInstructionMoveStage=" + workInstructionMoveStage
                + ", fetchChe=" + fetchChe + ", putChe=" + putChe);

        return new WorkInstructionEvent(
                eventType, workInstructionId, workQueueId, fetchChe, workInstructionMoveStage,
                estimatedMoveTime, estimatedCycleTimeSeconds, estimatedRtgCycleTimeSeconds,
                putChe, isTwinFetch, isTwinPut, isTwinCarry, twinCompanionWorkInstruction,
                fromPosition, toPosition, containerId, moveKind, jobPosition,
                isoType, freightKind, pinning);
    }



    private static String getStringField(GenericRecord record, String fieldName) {
        Object value = record.get(fieldName);
        return value != null ? value.toString() : null;
    }

    private static Long getLongField(GenericRecord record, String fieldName) {
        Object value = record.get(fieldName);
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    private static Boolean getBooleanField(GenericRecord record, String fieldName) {
        Object value = record.get(fieldName);
        if (value instanceof Boolean b) {
            return b;
        }
        return null;
    }

    private static Double getDoubleField(GenericRecord record, String fieldName) {
        Object value = record.get(fieldName);
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }
}
