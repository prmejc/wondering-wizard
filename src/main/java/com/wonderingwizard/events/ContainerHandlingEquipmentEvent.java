package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

/**
 * Event representing a Container Handling Equipment (CHE) state update,
 * matching the ContainerHandlingEquipment.avro schema.
 * When cheKind is "TT", this represents a terminal truck state update.
 *
 * @param eventType the type of event
 * @param cheId the CHE identifier
 * @param opType the operation type
 * @param cdhTerminalCode the terminal code
 * @param messageSequenceNumber the message sequence number
 * @param cheShortName the short name of the CHE (e.g., "TT01")
 * @param cheStatus the status of the CHE
 * @param cheKind the kind of CHE (e.g., "TT", "RTG", "QC")
 * @param chePoolId the pool identifier
 * @param cheJobStepState the current job step state
 * @param sourceTsMs the source timestamp in milliseconds
 */
public record ContainerHandlingEquipmentEvent(
        String eventType,
        Long cheId,
        String opType,
        String cdhTerminalCode,
        Long messageSequenceNumber,
        String cheShortName,
        CheStatus cheStatus,
        String cheKind,
        Long chePoolId,
        CheJobStepState cheJobStepState,
        Long sourceTsMs
) implements Event {

    @Override
    public String toString() {
        return "ContainerHandlingEquipmentEvent[cheShortName=" + cheShortName
                + ", cheKind=" + cheKind
                + ", cheStatus=" + cheStatus
                + ", cheJobStepState=" + cheJobStepState + "]";
    }
}
