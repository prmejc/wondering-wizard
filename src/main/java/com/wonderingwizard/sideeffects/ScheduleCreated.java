package com.wonderingwizard.sideeffects;

import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.SideEffect;

import java.time.Instant;
import java.util.List;

/**
 * Side effect and event indicating that a schedule has been created for a work queue.
 * <p>
 * When a work queue is activated, takts are generated from the work instructions
 * associated with that work queue. Each work instruction produces one takt,
 * named sequentially starting from TAKT100.
 * <p>
 * This record implements both SideEffect (produced by WorkQueueProcessor) and Event
 * (can be processed by ScheduleRunnerProcessor to initialize schedule execution).
 *
 * @param workQueueId the identifier of the work queue for which the schedule was created
 * @param takts the list of takts in this schedule
 * @param estimatedMoveTime the estimated time when the first action should start
 */
public record ScheduleCreated(String workQueueId, List<Takt> takts, Instant estimatedMoveTime) implements SideEffect, Event {

    @Override
    public String toString() {
        return "ScheduleCreated[workQueueId=" + workQueueId + ", takts=" + takts + ", estimatedMoveTime=" + estimatedMoveTime + "]";
    }
}
