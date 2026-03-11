package com.wonderingwizard.sideeffects;

import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.SideEffect;

import java.util.List;

/**
 * Side effect and event indicating that a schedule has been rescheduled for a work queue.
 * <p>
 * Produced when a WorkInstructionEvent with status FETCH_COMPLETE arrives and the actual
 * container data (twin/single flags) differs from what was originally planned. The
 * ScheduleRunnerProcessor replaces all WAITING takts with the new takts provided here.
 * <p>
 * {@code firstNewTaktSequence} indicates where the new takts start — all existing takts
 * at or after this sequence that are still WAITING will be replaced.
 *
 * @param workQueueId the identifier of the work queue being rescheduled
 * @param newTakts the replacement takts for the remaining (unstarted) portion of the schedule
 * @param firstNewTaktSequence the sequence number of the first takt to replace
 */
public record ScheduleModified(
        long workQueueId,
        List<Takt> newTakts,
        int firstNewTaktSequence
) implements SideEffect, Event {

    @Override
    public String toString() {
        return "ScheduleModified[workQueueId=" + workQueueId +
                ", newTakts=" + newTakts.size() +
                ", firstNewTaktSequence=" + firstNewTaktSequence + "]";
    }
}
