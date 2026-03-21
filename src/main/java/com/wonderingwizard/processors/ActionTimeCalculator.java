package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.Takt;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Shared utility for computing action start/end times within a takt,
 * based on same-device dependencies.
 */
public final class ActionTimeCalculator {

    private ActionTimeCalculator() {}

    /**
     * Computes the start time for an action based on same-device-type dependencies.
     *
     * @param taktStart the takt's start time (planned or estimated)
     * @param action the action to compute start time for
     * @param taktActions map of already-computed actions in this takt
     * @param endTimeExtractor extracts the relevant end time from a dependency
     * @return the computed start time
     */
    public static Instant computeActionStart(Instant taktStart, Action action,
                                              Map<UUID, Action> taktActions,
                                              Function<Action, Instant> endTimeExtractor) {
        Instant start = taktStart;
        if (action.dependsOn() != null) {
            for (UUID depId : action.dependsOn()) {
                Action dep = taktActions.get(depId);
                if (dep != null && dep.deviceType() == action.deviceType()) {
                    Instant depEnd = endTimeExtractor.apply(dep);
                    if (depEnd != null && depEnd.isAfter(start)) {
                        start = depEnd;
                    }
                }
            }
        }
        return start;
    }

    /**
     * Recomputes estimated start/end times for non-completed actions in a takt,
     * updating the takt's action list in place and syncing to the context's action lookup.
     * <p>
     * Completed actions are computed for dependency chaining but not written back.
     *
     * @param takt the takt whose actions to update
     * @param wqId the work queue ID
     * @param isCompleted predicate to check if an action is completed
     * @param context the schedule context for syncing action lookup
     */
    public static void recomputeEstimatedTimes(Takt takt, long wqId,
                                                Predicate<UUID> isCompleted,
                                                ScheduleContext context) {
        Instant estimatedStart = takt.estimatedStartTime();
        if (estimatedStart == null) return;

        List<Action> actions = takt.actions();
        Map<UUID, Action> allActions = context.getActions(wqId);
        Map<UUID, Action> computed = new HashMap<>();
        for (int i = 0; i < actions.size(); i++) {
            // Use the authoritative action from actionLookup (has actualStartTime etc.)
            Action action = allActions.getOrDefault(actions.get(i).id(), actions.get(i));
            Instant start = computeActionStart(estimatedStart, action, computed, Action::estimatedEndTime);
            Action withTimes = action.withEstimatedTimes(start, start.plusSeconds(action.durationSeconds()));
            computed.put(action.id(), withTimes);
            if (!isCompleted.test(action.id())) {
                actions.set(i, withTimes);
                context.updateAction(wqId, action.id(), withTimes);
            }
        }
    }
}
