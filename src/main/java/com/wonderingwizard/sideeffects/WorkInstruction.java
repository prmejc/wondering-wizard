package com.wonderingwizard.sideeffects;

import com.wonderingwizard.events.WorkInstructionStatus;

/**
 * Represents a work instruction included in a ScheduleCreated side effect.
 * <p>
 * This record captures the state of a work instruction at the time the
 * schedule was created.
 *
 * @param workInstructionId unique identifier for the work instruction
 * @param workQueueId identifier of the work queue this instruction belongs to
 * @param fetchChe the CHE (Container Handling Equipment) identifier for fetching
 * @param status the status of the work instruction
 */
public record WorkInstruction(
        String workInstructionId,
        String workQueueId,
        String fetchChe,
        WorkInstructionStatus status
) {

    @Override
    public String toString() {
        return "WorkInstruction[workInstructionId=" + workInstructionId +
                ", workQueueId=" + workQueueId +
                ", fetchChe=" + fetchChe +
                ", status=" + status + "]";
    }
}
