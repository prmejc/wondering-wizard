package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionStatus;
import com.wonderingwizard.domain.takt.CompletionCondition;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.events.JobOperationEvent;
import com.wonderingwizard.events.WorkInstructionEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Evaluates JobOperationEvent against active RTG action completion conditions.
 * <p>
 * Matches by cheId (RTG name = wi.putChe()), workInstructionId, containerId,
 * and job operation action code (condition description must match the event's action, e.g. "A" or "D").
 */
public class RTGJobOperationEvaluator implements CompletionConditionEvaluator {

    private static final Logger logger = Logger.getLogger(RTGJobOperationEvaluator.class.getName());

    public static final String CONDITION_TYPE = "RTG_JOB_OPERATION";

    @Override
    public Map<UUID, List<String>> evaluateSatisfied(Event event, Map<UUID, Action> activeActions) {
        if (!(event instanceof JobOperationEvent jobOp)) {
            return Map.of();
        }

        String jobAction = jobOp.action();
        String cheId = jobOp.cheId();
        String wiIdStr = jobOp.workInstructionId();
        String containerId = jobOp.containerId();

        if (jobAction == null || cheId == null || wiIdStr == null || containerId == null) {
            return Map.of();
        }

        long wiId;
        try {
            wiId = Long.parseLong(wiIdStr);
        } catch (NumberFormatException e) {
            return Map.of();
        }

        Map<UUID, List<String>> result = new HashMap<>();

        for (Map.Entry<UUID, Action> entry : activeActions.entrySet()) {
            Action action = entry.getValue();
            if (action.status() != ActionStatus.ACTIVE) continue;
            if (action.completionConditions() == null || action.completionConditions().isEmpty()) continue;

            // Match: cheId against putChe (RTG name for DSCH), workInstructionId and containerId
            boolean matches = false;
            for (WorkInstructionEvent wi : action.workInstructions()) {
                if (cheId.equals(wi.putChe())
                        && wi.workInstructionId() == wiId
                        && containerId.equals(wi.containerId())) {
                    matches = true;
                    break;
                }
            }
            if (!matches) continue;

            List<String> satisfiedConditionIds = new ArrayList<>();
            for (CompletionCondition condition : action.completionConditions()) {
                if (CONDITION_TYPE.equals(condition.type()) && jobAction.equals(condition.description())) {
                    logger.fine("RTG job operation '" + jobAction + "' satisfied condition '" + condition.id()
                            + "' on action " + entry.getKey() + ": " + cheId + " for WI " + wiId);
                    satisfiedConditionIds.add(condition.id());
                }
            }
            if (!satisfiedConditionIds.isEmpty()) {
                result.put(entry.getKey(), satisfiedConditionIds);
            }
        }

        return result;
    }
}
