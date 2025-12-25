package com.wonderingwizard.sideeffects;

import com.wonderingwizard.engine.SideEffect;

/**
 * Side effect indicating that a schedule has been created for a work queue.
 *
 * @param workQueueId the identifier of the work queue for which the schedule was created
 */
public record ScheduleCreated(String workQueueId) implements SideEffect {

    @Override
    public String toString() {
        return "ScheduleCreated[workQueueId=" + workQueueId + "]";
    }
}
