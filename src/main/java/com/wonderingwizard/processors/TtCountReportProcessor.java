package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.EventProcessor;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import com.wonderingwizard.sideeffects.TtCountReport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Processor that generates a TT count per 5-minute interval report
 * whenever a takt schedule is created.
 * <p>
 * For each takt in the schedule, the processor counts the TT (Terminal Truck) actions
 * and groups them into 5-minute intervals based on the takt's planned start time.
 * The result is a {@link TtCountReport} side effect showing TT demand over time.
 */
public class TtCountReportProcessor implements EventProcessor {

    private static final int INTERVAL_SECONDS = 300; // 5 minutes

    @Override
    public List<SideEffect> process(Event event) {
        if (event instanceof ScheduleCreated scheduleCreated) {
            return handleScheduleCreated(scheduleCreated);
        }
        return List.of();
    }

    private List<SideEffect> handleScheduleCreated(ScheduleCreated scheduleCreated) {
        TreeMap<Instant, Integer> intervalCounts = new TreeMap<>();

        for (Takt takt : scheduleCreated.takts()) {
            Instant plannedStart = takt.plannedStartTime();
            if (plannedStart == null) {
                continue;
            }

            int ttCount = 0;
            for (Action action : takt.actions()) {
                if (action.deviceType() == DeviceType.TT) {
                    ttCount++;
                }
            }

            if (ttCount > 0) {
                Instant intervalStart = truncateToInterval(plannedStart);
                intervalCounts.merge(intervalStart, ttCount, Integer::sum);
            }
        }

        List<TtCountReport.IntervalEntry> entries = new ArrayList<>();
        for (Map.Entry<Instant, Integer> entry : intervalCounts.entrySet()) {
            entries.add(new TtCountReport.IntervalEntry(entry.getKey(), entry.getValue()));
        }

        return List.of(new TtCountReport(scheduleCreated.workQueueId(), entries));
    }

    /**
     * Truncates an instant to the start of its 5-minute interval.
     */
    private Instant truncateToInterval(Instant instant) {
        long epochSecond = instant.getEpochSecond();
        long intervalStart = (epochSecond / INTERVAL_SECONDS) * INTERVAL_SECONDS;
        return Instant.ofEpochSecond(intervalStart);
    }

    @Override
    public Object captureState() {
        // Stateless processor — no state to capture
        return Map.of();
    }

    @Override
    public void restoreState(Object state) {
        // Stateless processor — nothing to restore
    }
}
