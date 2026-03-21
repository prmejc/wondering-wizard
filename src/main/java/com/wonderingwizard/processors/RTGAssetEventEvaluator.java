package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionStatus;
import com.wonderingwizard.domain.takt.CompletionCondition;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.events.AssetEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Evaluates RTG asset events against active RTG action completion conditions.
 * <p>
 * Matches by cheID (from the asset event) against the RTG name on the action's
 * work instructions (putChe for DSCH mode), and by operationalEvent against the condition description.
 */
public class RTGAssetEventEvaluator implements CompletionConditionEvaluator {

    private static final Logger logger = Logger.getLogger(RTGAssetEventEvaluator.class.getName());

    public static final String CONDITION_TYPE = "RTG_ASSET_EVENT";

    @Override
    public Map<UUID, List<String>> evaluateSatisfied(Event event, Map<UUID, Action> allActions) {
        if (!(event instanceof AssetEvent assetEvent)) {
            return Map.of();
        }

        String cheId = assetEvent.cheId();
        String operationalEvent = assetEvent.operationalEvent();
        if (cheId == null || operationalEvent == null) {
            return Map.of();
        }

        Map<UUID, List<String>> result = new HashMap<>();

        for (Map.Entry<UUID, Action> entry : allActions.entrySet()) {
            Action action = entry.getValue();
            if (action.status() != ActionStatus.ACTIVE) continue;
            if (action.completionConditions() == null || action.completionConditions().isEmpty()) continue;

            String actionRtg = resolveRtgName(action);
            if (actionRtg == null || !actionRtg.equals(cheId)) continue;

            List<String> satisfiedConditionIds = new ArrayList<>();
            for (CompletionCondition condition : action.completionConditions()) {
                if (CONDITION_TYPE.equals(condition.type()) && operationalEvent.equals(condition.description())) {
                    logger.fine("RTG asset event satisfied condition '" + condition.id()
                            + "' on action " + entry.getKey() + ": " + operationalEvent + " from " + cheId);
                    satisfiedConditionIds.add(condition.id());
                }
            }
            if (!satisfiedConditionIds.isEmpty()) {
                result.put(entry.getKey(), satisfiedConditionIds);
            }
        }

        return result;
    }

    private String resolveRtgName(Action action) {
        if (action.workInstructions() == null || action.workInstructions().isEmpty()) {
            return null;
        }
        return action.workInstructions().getFirst().putChe();
    }
}
