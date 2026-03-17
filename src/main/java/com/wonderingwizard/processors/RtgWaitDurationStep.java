package com.wonderingwizard.processors;

import com.wonderingwizard.events.WorkInstructionEvent;

import java.util.ArrayList;
import java.util.List;

import static com.wonderingwizard.domain.takt.ActionType.*;
import static com.wonderingwizard.domain.takt.DeviceType.RTG;
import static com.wonderingwizard.domain.takt.DeviceType.TT;

/**
 * Pipeline step that computes RTG_WAIT_FOR_TRUCK duration based on actual TT action durations.
 * <p>
 * Must run after DigitalMapProcessor so TT driving times are populated from the map.
 * Computes the wait as the sum of TT durations from HANDOVER_FROM_QC to each HANDOVER_TO_RTG.
 */
public class RtgWaitDurationStep implements SchedulePipelineStep {

    @Override
    public List<GraphScheduleBuilder.ActionTemplate> enrichTemplates(
            long workQueueId,
            List<GraphScheduleBuilder.ActionTemplate> templates,
            WorkInstructionEvent workInstruction) {

        List<GraphScheduleBuilder.ActionTemplate> ttActions = templates.stream()
                .filter(t -> t.deviceType() == TT)
                .toList();

        // Find last HANDOVER_FROM_QC index
        int fromQcIndex = -1;
        for (int i = 0; i < ttActions.size(); i++) {
            if (ttActions.get(i).actionType() == TT_HANDOVER_FROM_QC) {
                fromQcIndex = i;
            }
        }
        if (fromQcIndex == -1) return templates;

        // Find all HANDOVER_TO_RTG indices (in order)
        List<Integer> toRtgIndices = new ArrayList<>();
        for (int i = fromQcIndex + 1; i < ttActions.size(); i++) {
            if (ttActions.get(i).actionType() == TT_HANDOVER_TO_RTG) {
                toRtgIndices.add(i);
            }
        }
        if (toRtgIndices.isEmpty()) return templates;

        // Compute cumulative sum from HANDOVER_FROM_QC to each HANDOVER_TO_RTG
        List<Integer> waitDurations = new ArrayList<>();
        for (int toRtgIndex : toRtgIndices) {
            int sum = 0;
            for (int i = fromQcIndex; i < toRtgIndex; i++) {
                sum += ttActions.get(i).durationSeconds();
            }
            waitDurations.add(sum);
        }

        // Subtract RTG_FETCH durations – the fetch happens in parallel with TT travel,
        // so the actual wait is reduced by the fetch time.
        List<Integer> fetchDurations = templates.stream()
                .filter(t -> t.deviceType() == RTG && t.actionType() == RTG_DRIVE)
                .map(GraphScheduleBuilder.ActionTemplate::durationSeconds)
                .toList();
        for (int i = 0; i < Math.min(waitDurations.size(), fetchDurations.size()); i++) {
            waitDurations.set(i, Math.max(0, waitDurations.get(i) - fetchDurations.get(i)));
        }


        // Replace RTG_WAIT_FOR_TRUCK durations
        int[] waitIndex = {0};
        return templates.stream()
                .map(t -> {
                    if (t.actionType() == RTG_WAIT_FOR_TRUCK && waitIndex[0] < waitDurations.size()) {
                        return t.withDuration(waitDurations.get(waitIndex[0]++));
                    }
                    return t;
                })
                .toList();
    }
}
