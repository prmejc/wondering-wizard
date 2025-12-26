package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

/**
 * Event representing a work instruction that is associated with a work queue.
 * <p>
 * Work instructions are registered with the system and are included in the
 * ScheduleCreated side effect when their associated work queue is activated.
 *
 * @param workInstructionId unique identifier for the work instruction
 * @param workQueueId identifier of the work queue this instruction belongs to
 * @param fetchChe the CHE (Container Handling Equipment) identifier for fetching
 * @param status the status of the work instruction
 */
public record WorkInstructionEvent(
        String workInstructionId,
        String workQueueId,
        String fetchChe,
        WorkInstructionStatus status
) implements Event {

    @Override
    public String toString() {
        return "WorkInstructionEvent[workInstructionId=" + workInstructionId +
                ", workQueueId=" + workQueueId +
                ", fetchChe=" + fetchChe +
                ", status=" + status + "]";
    }
}
