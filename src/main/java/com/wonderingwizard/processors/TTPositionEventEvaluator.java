package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionStatus;
import com.wonderingwizard.domain.takt.CompletionCondition;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.events.CheTargetPositionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Evaluates CheTargetPositionEvent against active TT action completion conditions.
 * <p>
 * Matches by equipmentInstructionId (which is the action UUID) directly.
 */
public class TTPositionEventEvaluator implements CompletionConditionEvaluator {

    private static final Logger logger = Logger.getLogger(TTPositionEventEvaluator.class.getName());

    public static final String CONDITION_TYPE = "TT_POSITION_EVENT";

    @Override
    public List<String> evaluateSatisfied(Event event, Map<UUID, Action> activeActions) {
        if (!(event instanceof CheTargetPositionEvent positionEvent)) {
            return List.of();
        }

        String instructionId = positionEvent.equipmentInstructionId();
        if (instructionId == null || instructionId.isEmpty()) {
            return List.of();
        }

        UUID actionId;
        try {
            actionId = UUID.fromString(instructionId);
        } catch (IllegalArgumentException e) {
            return List.of();
        }

        Action action = activeActions.get(actionId);
        if (action == null || action.status() != ActionStatus.ACTIVE) {
            return List.of();
        }

        List<String> satisfiedConditionIds = new ArrayList<>();
        for (CompletionCondition condition : action.completionConditions()) {
            if (CONDITION_TYPE.equals(condition.type())) {
                logger.info("TT position confirmed condition '" + condition.id()
                        + "' on action " + actionId + " by " + positionEvent.cheShortName());
                satisfiedConditionIds.add(condition.id());
            }
        }

        return satisfiedConditionIds;
    }
}
