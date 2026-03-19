package com.wonderingwizard.kafka;

import com.wonderingwizard.events.CheJobStepState;
import com.wonderingwizard.events.CheStatus;
import com.wonderingwizard.events.ContainerHandlingEquipmentEvent;
import org.apache.avro.generic.GenericRecord;

import java.util.logging.Logger;

/**
 * Maps Kafka Avro ContainerHandlingEquipment messages to engine
 * {@link ContainerHandlingEquipmentEvent} events.
 */
public class ContainerHandlingEquipmentEventMapper implements EventMapper<ContainerHandlingEquipmentEvent> {

    private static final Logger logger = Logger.getLogger(ContainerHandlingEquipmentEventMapper.class.getName());

    @Override
    public ContainerHandlingEquipmentEvent map(GenericRecord record) {
        String eventType = getStringField(record, "eventType");
        Long cheId = getLongField(record, "cheId");
        String opType = getStringField(record, "opType");
        String cdhTerminalCode = getStringField(record, "cdhTerminalCode");
        Long messageSequenceNumber = getLongField(record, "messageSequenceNumber");
        String cheShortName = getStringField(record, "cheShortName");
        String cheStatusStr = getStringField(record, "cheStatus");
        String cheKind = getStringField(record, "cheKind");
        Long chePoolId = getLongField(record, "chePoolId");
        String cheJobStepStateStr = getStringField(record, "cheJobStepState");
        Long sourceTsMs = getLongField(record, "SOURCE_TS_MS");

        CheStatus cheStatus = mapCheStatus(cheStatusStr);
        CheJobStepState cheJobStepState = mapCheJobStepState(cheJobStepStateStr);

        logger.fine("Mapped CHE Kafka message: cheShortName=" + cheShortName
                + ", cheKind=" + cheKind + ", cheStatus=" + cheStatus
                + ", cheJobStepState=" + cheJobStepState);

        return new ContainerHandlingEquipmentEvent(
                eventType != null ? eventType : "",
                cheId,
                opType != null ? opType : "",
                cdhTerminalCode != null ? cdhTerminalCode : "",
                messageSequenceNumber,
                cheShortName,
                cheStatus,
                cheKind != null ? cheKind : "TT",
                chePoolId,
                cheJobStepState,
                sourceTsMs
        );
    }

    private CheStatus mapCheStatus(String statusStr) {
        if (statusStr == null || statusStr.isBlank()) {
            return CheStatus.WORKING;
        }
        try {
            return CheStatus.fromDisplayName(statusStr);
        } catch (IllegalArgumentException e) {
            logger.warning("Unknown CHE status: " + statusStr + ", defaulting to WORKING");
            return CheStatus.WORKING;
        }
    }

    private CheJobStepState mapCheJobStepState(String stateStr) {
        if (stateStr == null || stateStr.isBlank()) {
            return CheJobStepState.IDLE;
        }
        try {
            return CheJobStepState.fromCode(stateStr);
        } catch (IllegalArgumentException e) {
            logger.warning("Unknown CHE job step state: " + stateStr + ", defaulting to IDLE");
            return CheJobStepState.IDLE;
        }
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
