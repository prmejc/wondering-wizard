package com.wonderingwizard.sideeffects;

import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.engine.SideEffect;

import java.util.List;

/**
 * Side effect indicating that a schedule has been created for a work queue.
 * <p>
 * When a work queue is activated, takts are generated from the work instructions
 * associated with that work queue. Each work instruction produces one takt,
 * named sequentially starting from TAKT100.
 *
 * @param workQueueId the identifier of the work queue for which the schedule was created
 * @param takts the list of takts in this schedule
 */
public record ScheduleCreated(String workQueueId, List<Takt> takts) implements SideEffect {

    /**
     * Creates a ScheduleCreated with an empty list of takts.
     *
     * @param workQueueId the identifier of the work queue
     */
    public ScheduleCreated(String workQueueId) {
        this(workQueueId, List.of());
    }

    @Override
    public String toString() {
        return "ScheduleCreated[workQueueId=" + workQueueId + ", takts=" + takts + "]";
    }
}
