package com.wonderingwizard.kafka;

import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.WorkInstructionStatus;
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
                getBooleanField(record, "isTwinCarry")
        );
    }

    /**
     * Convert a typed WorkInstructionKafkaMessage to the engine's WorkInstructionEvent.
     */
    WorkInstructionEvent toEngineEvent(WorkInstructionKafkaMessage kafkaMessage) {
        String workInstructionId = kafkaMessage.workInstructionId() != null
                ? String.valueOf(kafkaMessage.workInstructionId())
                : "";

        String workQueueId = kafkaMessage.workQueueId() != null
                ? String.valueOf(kafkaMessage.workQueueId())
                : "";

        String fetchChe = kafkaMessage.fetchCHEName() != null
                ? kafkaMessage.fetchCHEName()
                : "";

        WorkInstructionStatus status = mapStatus(kafkaMessage.workInstructionMoveStage());

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

        logger.fine("Mapped WorkInstruction Kafka message: workInstructionId=" + workInstructionId
                + ", workQueueId=" + workQueueId + ", status=" + status
                + ", fetchChe=" + fetchChe + ", putChe=" + putChe);

        return new WorkInstructionEvent(
                workInstructionId, workQueueId, fetchChe, status,
                estimatedMoveTime, estimatedCycleTimeSeconds, estimatedRtgCycleTimeSeconds,
                putChe, isTwinFetch, isTwinPut, isTwinCarry);
    }

    private WorkInstructionStatus mapStatus(String moveStage) {
        if (moveStage == null) {
            return WorkInstructionStatus.PENDING;
        }
        return switch (moveStage.toUpperCase()) {
            case "PLANNED", "READY" -> WorkInstructionStatus.PENDING;
            case "CARRY_UNDERWAY", "FETCH_UNDERWAY", "PUT_UNDERWAY" -> WorkInstructionStatus.IN_PROGRESS;
            case "COMPLETE", "COMPLETED" -> WorkInstructionStatus.COMPLETED;
            case "CANCELLED", "CANCEL" -> WorkInstructionStatus.CANCELLED;
            default -> {
                logger.warning("Unknown WorkInstruction moveStage from Kafka: " + moveStage
                        + ", defaulting to PENDING");
                yield WorkInstructionStatus.PENDING;
            }
        };
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
