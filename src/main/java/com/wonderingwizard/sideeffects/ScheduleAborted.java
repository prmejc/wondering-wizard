package com.wonderingwizard.sideeffects;

import com.wonderingwizard.engine.SideEffect;

/**
 * Side effect indicating that a schedule has been aborted for a work queue.
 *
 * @param workQueueId the identifier of the work queue for which the schedule was aborted
 */
public record ScheduleAborted(String workQueueId) implements SideEffect {

    @Override
    public String toString() {
        return "ScheduleAborted[workQueueId=" + workQueueId + "]";
    }
}
