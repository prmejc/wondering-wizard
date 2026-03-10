package com.wonderingwizard.kafka;

import com.wonderingwizard.events.LoadMode;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.events.WorkQueueStatus;
import com.wonderingwizard.kafka.messages.WorkQueueKafkaMessage;
import org.apache.avro.generic.GenericRecord;

import java.util.logging.Logger;

/**
 * Maps Kafka Avro WorkQueue messages to engine {@link WorkQueueMessage} events.
 * <p>
 * Extracts the workQueueId, workQueueStatus, and mudaQCCycleTime from the Avro
 * record and maps them to the engine's domain model.
 */
public class WorkQueueEventMapper implements EventMapper<WorkQueueMessage> {

    private static final Logger logger = Logger.getLogger(WorkQueueEventMapper.class.getName());

    @Override
    public WorkQueueMessage map(GenericRecord record) {
        WorkQueueKafkaMessage kafkaMessage = fromAvro(record);
        return toEngineEvent(kafkaMessage);
    }

    /**
     * Extract fields from Avro GenericRecord into a typed WorkQueueKafkaMessage.
     */
    WorkQueueKafkaMessage fromAvro(GenericRecord record) {
        return new WorkQueueKafkaMessage(
                getStringField(record, "eventType"),
                getStringField(record, "opType"),
                getStringField(record, "cdhTerminalCode"),
                getLongField(record, "messageSequenceNumber"),
                getLongField(record, "workQueueId"),
                getStringField(record, "vesselVisitId"),
                getStringField(record, "vesselName"),
                getStringField(record, "vesselRow"),
                getStringField(record, "vesselDeck"),
                getStringField(record, "workQueueName"),
                getStringField(record, "workQueueStatus"),
                getStringField(record, "workQueueSequence"),
                getStringField(record, "workQueueType"),
                getStringField(record, "pointOfWorkName"),
                getStringField(record, "load_mode"),
                getStringField(record, "note"),
                getLongField(record, "workQueueCycleCompanionId"),
                getLongField(record, "workQueueDoubleCycleFromSequence"),
                getLongField(record, "workQueueDoubleCycleToSequence"),
                getStringField(record, "vesselOrientation"),
                getStringField(record, "bollardPosition"),
                getLongField(record, "mudaQCCycleTime"),
                getStringField(record, "workQueueManaged"),
                getLongField(record, "SOURCE_TS_MS")
        );
    }

    /**
     * Convert a typed WorkQueueKafkaMessage to the engine's WorkQueueMessage event.
     */
    WorkQueueMessage toEngineEvent(WorkQueueKafkaMessage kafkaMessage) {
        String workQueueId = kafkaMessage.workQueueId() != null
                ? String.valueOf(kafkaMessage.workQueueId())
                : kafkaMessage.workQueueName();

        WorkQueueStatus status = mapStatus(kafkaMessage.workQueueStatus());

        int qcMudaSeconds = kafkaMessage.mudaQCCycleTime() != null
                ? kafkaMessage.mudaQCCycleTime().intValue()
                : 0;

        LoadMode loadMode = mapLoadMode(kafkaMessage.loadMode());

        logger.fine("Mapped WorkQueue Kafka message: workQueueId=" + workQueueId
                + ", status=" + status + ", qcMudaSeconds=" + qcMudaSeconds + ", loadMode=" + loadMode);

        return new WorkQueueMessage(workQueueId, status, qcMudaSeconds, loadMode);
    }

    private WorkQueueStatus mapStatus(String kafkaStatus) {
        if (kafkaStatus == null) {
            return null;
        }
        return switch (kafkaStatus.toUpperCase()) {
            case "ACTIVE", "WORKING", "CREATED" -> WorkQueueStatus.ACTIVE;
            case "INACTIVE", "COMPLETE", "CANCELLED", "DELETED" -> WorkQueueStatus.INACTIVE;
            default -> {
                logger.warning("Unknown WorkQueue status from Kafka: " + kafkaStatus
                        + ", defaulting to INACTIVE");
                yield WorkQueueStatus.INACTIVE;
            }
        };
    }

    private LoadMode mapLoadMode(String kafkaLoadMode) {
        if (kafkaLoadMode == null) {
            return null;
        }
        return switch (kafkaLoadMode.toUpperCase()) {
            case "LOAD" -> LoadMode.LOAD;
            case "DSCH" -> LoadMode.DSCH;
            default -> {
                logger.warning("Unknown load_mode from Kafka: " + kafkaLoadMode
                        + ", defaulting to DSCH");
                yield LoadMode.DSCH;
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
}
