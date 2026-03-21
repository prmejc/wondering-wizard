package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionStatus;
import com.wonderingwizard.domain.takt.CompletionCondition;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.events.WorkInstructionEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Evaluates whether a completed action satisfies completion conditions on other actions.
 * <p>
 * This evaluator is state-based rather than event-based: it checks whether any
 * COMPLETED action of a specific type matches an ACTIVE action's ACTION_COMPLETED
 * condition by container ID. The condition's {@code description} specifies the
 * required trigger action type name (e.g., "TT_DRIVE_TO_RTG_UNDER", "RTG_LIFT_FROM_TT").
 * <p>
 * Used with the fixed-point loop in {@link ScheduleRunnerProcessor#evaluateCompletionConditions}
 * to cascade completions within a single processing cycle.
 */
public class ActionCompletedEvaluator implements CompletionConditionEvaluator {

    private static final Logger logger = Logger.getLogger(ActionCompletedEvaluator.class.getName());

    public static final String CONDITION_TYPE = "ACTION_COMPLETED";

    @Override
    public Map<UUID, List<String>> evaluateSatisfied(Event event, Map<UUID, Action> allActions) {
        // Build map: actionType name → set of containerIds from COMPLETED actions of that type
        Map<String, Set<String>> completedByType = new HashMap<>();
        for (Action action : allActions.values()) {
            if (action.status() != ActionStatus.COMPLETED) continue;
            for (WorkInstructionEvent wi : action.workInstructions()) {
                if (wi.containerId() != null && !wi.containerId().isEmpty()) {
                    completedByType.computeIfAbsent(action.actionType().name(), k -> new HashSet<>())
                            .add(wi.containerId());
                }
            }
        }

        if (completedByType.isEmpty()) return Map.of();

        // Find ACTIVE actions with ACTION_COMPLETED conditions matching by trigger type + container
        Map<UUID, List<String>> result = new HashMap<>();
        for (Action action : allActions.values()) {
            if (action.status() != ActionStatus.ACTIVE) continue;
            if (action.completionConditions() == null || action.completionConditions().isEmpty()) continue;

            Set<String> actionContainerIds = new HashSet<>();
            for (WorkInstructionEvent wi : action.workInstructions()) {
                if (wi.containerId() != null && !wi.containerId().isEmpty()) {
                    actionContainerIds.add(wi.containerId());
                }
            }
            if (actionContainerIds.isEmpty()) continue;

            List<String> satisfiedConditionIds = new ArrayList<>();
            for (CompletionCondition condition : action.completionConditions()) {
                if (!CONDITION_TYPE.equals(condition.type())) continue;

                // condition.description() = the trigger action type name
                Set<String> completedContainers = completedByType.get(condition.description());
                if (completedContainers == null) continue;

                for (String containerId : actionContainerIds) {
                    if (completedContainers.contains(containerId)) {
                        logger.fine("Action completed condition '" + condition.id()
                                + "' satisfied on action " + action.id()
                                + " (" + action.actionType() + ") by " + condition.description() + " completion");
                        satisfiedConditionIds.add(condition.id());
                        break;
                    }
                }
            }
            if (!satisfiedConditionIds.isEmpty()) {
                result.put(action.id(), satisfiedConditionIds);
            }
        }

        return result;
    }
}
