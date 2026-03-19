package com.wonderingwizard.kafka;

import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.kafka.messages.ContainerMoveStateKafkaMessage;
import com.wonderingwizard.sideeffects.TruckAssigned;
import org.apache.avro.generic.GenericRecord;

import java.util.logging.Logger;

/**
 * Maps {@link TruckAssigned} side effects to {@link ContainerMoveStateKafkaMessage}
 * Avro records for publishing to the ContainerMoveState topic.
 * <p>
 * Each TruckAssigned produces one message per work instruction with
 * containerMoveState = "TT_ASSIGNED".
 */
public class TruckAssignedToContainerMoveStateMapper implements SideEffectMapper<TruckAssigned> {

    private static final Logger logger = Logger.getLogger(TruckAssignedToContainerMoveStateMapper.class.getName());
    private static final String CONTAINER_MOVE_STATE = "TT_ASSIGNED";

    private final String terminalCode;
    private final String eventSource;

    public TruckAssignedToContainerMoveStateMapper(String terminalCode, String eventSource) {
        this.terminalCode = terminalCode;
        this.eventSource = eventSource;
    }

    @Override
    public GenericRecord map(TruckAssigned truckAssigned) {
        ContainerMoveStateKafkaMessage message = mapToMessage(truckAssigned);
        return message != null ? message.toAvro() : null;
    }

    ContainerMoveStateKafkaMessage mapToMessage(TruckAssigned truckAssigned) {
        if (truckAssigned.workInstructions() == null || truckAssigned.workInstructions().isEmpty()) {
            logger.fine("Skipping TruckAssigned without work instructions: " + truckAssigned.actionId());
            return null;
        }

        WorkInstructionEvent wi = truckAssigned.workInstructions().getFirst();

        logger.fine("Mapping TruckAssigned to ContainerMoveState: truck=" + truckAssigned.cheShortName()
                + ", wiId=" + wi.workInstructionId());

        return new ContainerMoveStateKafkaMessage(
                CONTAINER_MOVE_STATE,
                wi.fetchChe() != null ? wi.fetchChe() : "",
                truckAssigned.cheShortName(),
                wi.putChe() != null ? wi.putChe() : "",
                wi.workInstructionId(),
                wi.moveKind() != null && !wi.moveKind().isEmpty() ? wi.moveKind() : "DSCH",
                wi.fromPosition() != null ? wi.fromPosition() : "",
                wi.toPosition() != null ? wi.toPosition() : "",
                wi.containerId() != null ? wi.containerId() : "",
                0,
                terminalCode,
                eventSource,
                System.currentTimeMillis()
        );
    }
}
