package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.engine.Event;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Evaluates whether an event satisfies any completion conditions on active actions.
 * <p>
 * Implementations contain the domain logic for matching events to specific
 * completion conditions (e.g., a QC asset event satisfies a QC_LIFT completion condition).
 * <p>
 * Registered with {@link ScheduleRunnerProcessor} and called on every event.
 */
public interface CompletionConditionEvaluator {

    /**
     * Evaluates which completion condition IDs are satisfied by the given event.
     *
     * @param event the event to evaluate
     * @param activeActions map of actionId → Action for all ACTIVE actions across all schedules
     * @return list of completion condition IDs that are now satisfied
     */
    List<String> evaluateSatisfied(Event event, Map<UUID, Action> activeActions);
}
