package com.wonderingwizard.sideeffects;

import com.wonderingwizard.engine.SideEffect;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Side effect representing the required TT (Terminal Truck) count per 5-minute interval.
 * <p>
 * Produced by TtCountReportProcessor when a ScheduleCreated event is processed.
 * Each entry in the intervals map represents a 5-minute window starting at the key instant,
 * with the value being the number of TT actions scheduled in that window.
 *
 * @param workQueueId the work queue this report belongs to
 * @param intervals ordered list of interval entries, each mapping an interval start time to a TT action count
 */
public record TtCountReport(String workQueueId, List<IntervalEntry> intervals) implements SideEffect {

    /**
     * A single 5-minute interval with its required TT count.
     *
     * @param intervalStart the start of the 5-minute interval
     * @param ttCount the number of TT actions required in this interval
     */
    public record IntervalEntry(Instant intervalStart, int ttCount) {}
}
