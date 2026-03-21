package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.ActionStatus;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.TimeEvent;

import java.time.Instant;
import java.util.List;

/**
 * Sub-processor that recalculates takt estimated start times on each TimeEvent.
 * <p>
 * Algorithm:
 * <ol>
 *   <li>Find the anchor takt — the last ACTIVE takt, or if none, the last COMPLETED takt</li>
 *   <li>Anchor takt: {@code estimatedStartTime = max(now - duration, plannedStartTime)}</li>
 *   <li>Earlier ACTIVE/COMPLETED takts are calculated backwards:
 *       {@code prev.estimatedStartTime = next.estimatedStartTime - prev.durationSeconds}</li>
 *   <li>WAITING takts are calculated forwards:
 *       {@code next.estimatedStartTime = prev.estimatedStartTime + prev.durationSeconds}</li>
 * </ol>
 */
public class EstimatedTimeCalculator implements ScheduleSubProcessor {

    @Override
    public List<SideEffect> process(Event event, ScheduleContext context) {
        if (!(event instanceof TimeEvent)) {
            return List.of();
        }

        Instant now = context.getCurrentTime();
        for (long wqId : context.getScheduleWorkQueueIds()) {
            recalculate(context.getTakts(wqId), wqId, now, context);
        }

        return List.of();
    }

    private void recalculate(List<Takt> takts, long wqId, Instant now, ScheduleContext context) {
        // Find anchor: last ACTIVE takt, or failing that, last COMPLETED takt
        int anchorIdx = -1;
        for (int i = 0; i < takts.size(); i++) {
            var state = context.getTaktState(wqId, takts.get(i).name());
            if (state == ScheduleRunnerProcessor.TaktState.ACTIVE) {
                anchorIdx = i;
            }
        }
        if (anchorIdx < 0) {
            for (int i = takts.size() - 1; i >= 0; i--) {
                if (context.getTaktState(wqId, takts.get(i).name()) == ScheduleRunnerProcessor.TaktState.COMPLETED) {
                    anchorIdx = i;
                    break;
                }
            }
        }

        if (anchorIdx < 0) {
            return;
        }

        // Anchor takt: only update if ACTIVE (don't touch COMPLETED takts)
        Takt anchor = takts.get(anchorIdx);
        if (context.getTaktState(wqId, anchor.name()) == ScheduleRunnerProcessor.TaktState.ACTIVE) {
            Instant derived = now.minusSeconds(anchor.durationSeconds());
            Instant newEstimated = anchor.plannedStartTime() != null && anchor.plannedStartTime().isAfter(derived)
                    ? anchor.plannedStartTime() : derived;
            anchor.setEstimatedStartTime(newEstimated);
        }

        // Walk backwards from anchor — only update ACTIVE takts, skip COMPLETED and WAITING
        for (int i = anchorIdx - 1; i >= 0; i--) {
            Takt current = takts.get(i);
            if (context.getTaktState(wqId, current.name()) != ScheduleRunnerProcessor.TaktState.ACTIVE) {
                continue;
            }
            Takt next = takts.get(i + 1);
            current.setEstimatedStartTime(next.estimatedStartTime().minusSeconds(current.durationSeconds()));
        }

        // Walk forwards from anchor through waiting takts
        for (int i = anchorIdx + 1; i < takts.size(); i++) {
            Takt current = takts.get(i);
            if (context.getTaktState(wqId, current.name()) != ScheduleRunnerProcessor.TaktState.WAITING) {
                continue;
            }
            Takt prev = takts.get(i - 1);
            current.setEstimatedStartTime(prev.estimatedStartTime().plusSeconds(prev.durationSeconds()));
        }

        // Recompute action estimated times for all non-completed takts
        for (Takt takt : takts) {
            var state = context.getTaktState(wqId, takt.name());
            if (state != ScheduleRunnerProcessor.TaktState.COMPLETED) {
                ActionTimeCalculator.recomputeEstimatedTimes(takt, wqId,
                        actionId -> context.getActionStatus(wqId, actionId) == ActionStatus.COMPLETED,
                        context);
            }
        }
    }
}
