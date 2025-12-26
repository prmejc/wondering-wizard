package com.wonderingwizard.sideeffects;

import com.wonderingwizard.engine.SideEffect;

import java.util.List;

/**
 * Side effect indicating that a schedule has been created for a work queue.
 * <p>
 * When a work queue is activated, any work instructions associated with that
 * work queue are included in this side effect.
 *
 * @param workQueueId the identifier of the work queue for which the schedule was created
 * @param workInstructions the list of work instructions associated with this work queue
 */
public record ScheduleCreated(String workQueueId, List<WorkInstruction> workInstructions) implements SideEffect {

    /**
     * Creates a ScheduleCreated with an empty list of work instructions.
     * Provided for backward compatibility.
     *
     * @param workQueueId the identifier of the work queue
     */
    public ScheduleCreated(String workQueueId) {
        this(workQueueId, List.of());
    }

    @Override
    public String toString() {
        return "ScheduleCreated[workQueueId=" + workQueueId + ", workInstructions=" + workInstructions + "]";
    }
}
