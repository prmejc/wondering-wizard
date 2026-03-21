package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.Takt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes planned and estimated times for each action based on takt start times
 * and same-device dependencies.
 *
 * <ul>
 *   <li>{@code plannedStartTime}/{@code plannedEndTime} — derived from {@code takt.plannedStartTime()}</li>
 *   <li>{@code estimatedStartTime}/{@code estimatedEndTime} — derived from {@code takt.estimatedStartTime()}</li>
 *   <li>Only same-device-type dependencies are considered</li>
 *   <li>Cross-takt dependencies are ignored</li>
 * </ul>
 */
public class PlannedTimeStep implements SchedulePostProcessingStep {

    @Override
    public List<Takt> process(List<Takt> takts) {
        List<Takt> result = new ArrayList<>(takts.size());

        for (Takt takt : takts) {
            Instant plannedStart = takt.plannedStartTime();
            Instant estimatedStart = takt.estimatedStartTime();
            if (plannedStart == null && estimatedStart == null) {
                result.add(takt);
                continue;
            }

            Map<UUID, Action> taktActions = new HashMap<>();
            List<Action> updatedActions = new ArrayList<>(takt.actions().size());
            for (Action action : takt.actions()) {
                Action updated = action;
                if (plannedStart != null) {
                    Instant start = ActionTimeCalculator.computeActionStart(plannedStart, action, taktActions, Action::plannedEndTime);
                    updated = updated.withPlannedTimes(start, start.plusSeconds(action.durationSeconds()));
                }
                if (estimatedStart != null) {
                    Instant start = ActionTimeCalculator.computeActionStart(estimatedStart, updated, taktActions, Action::estimatedEndTime);
                    updated = updated.withEstimatedTimes(start, start.plusSeconds(action.durationSeconds()));
                }
                updatedActions.add(updated);
                taktActions.put(updated.id(), updated);
            }

            result.add(new Takt(takt.sequence(), updatedActions, plannedStart, estimatedStart, takt.durationSeconds()));
        }

        return result;
    }
}
