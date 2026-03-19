package com.wonderingwizard.kafka;

import com.wonderingwizard.events.ContainerMoveStateEvent;
import org.apache.avro.generic.GenericRecord;

import java.util.logging.Logger;

/**
 * Maps Kafka Avro ResponseContainerMoveState messages to engine
 * {@link ContainerMoveStateEvent} events.
 */
public class ContainerMoveStateEventMapper implements EventMapper<ContainerMoveStateEvent> {

    private static final Logger logger = Logger.getLogger(ContainerMoveStateEventMapper.class.getName());

    @Override
    public ContainerMoveStateEvent map(GenericRecord record) {
        String containerMoveAction = getStringField(record, "containerMoveAction");
        String containerMoveStateRequestStatus = getStringField(record, "containerMoveStateRequestStatus");
        String responseContainerMoveState = getStringField(record, "responseContainerMoveState");
        String carryCHEName = getStringField(record, "carryCHEName");
        long workInstructionId = getLongField(record, "workInstructionId", 0L);
        String moveKind = getStringField(record, "moveKind");
        String containerId = getStringField(record, "containerId");
        String terminalCode = getStringField(record, "terminalCode");
        String errorMessage = getStringField(record, "errorMessage");
        long sourceTsMs = getLongField(record, "SOURCE_TS_MS", 0L);

        logger.fine("Mapped ContainerMoveState: action=" + containerMoveAction
                + ", status=" + containerMoveStateRequestStatus
                + ", state=" + responseContainerMoveState
                + ", che=" + carryCHEName + ", wiId=" + workInstructionId);

        return new ContainerMoveStateEvent(
                containerMoveAction != null ? containerMoveAction : "",
                containerMoveStateRequestStatus != null ? containerMoveStateRequestStatus : "",
                responseContainerMoveState != null ? responseContainerMoveState : "",
                carryCHEName != null ? carryCHEName : "",
                workInstructionId,
                moveKind != null ? moveKind : "",
                containerId != null ? containerId : "",
                terminalCode != null ? terminalCode : "",
                errorMessage != null ? errorMessage : "",
                sourceTsMs);
    }

    private static String getStringField(GenericRecord record, String fieldName) {
        Object value = record.get(fieldName);
        return value != null ? value.toString() : null;
    }

    private static long getLongField(GenericRecord record, String fieldName, long defaultValue) {
        Object value = record.get(fieldName);
        if (value instanceof Long l) return l;
        if (value instanceof Number n) return n.longValue();
        return defaultValue;
    }
}
