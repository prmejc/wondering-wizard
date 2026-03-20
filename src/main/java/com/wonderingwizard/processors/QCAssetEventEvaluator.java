package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionStatus;
import com.wonderingwizard.domain.takt.CompletionCondition;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.events.AssetEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Evaluates QC asset events against active QC action completion conditions.
 * <p>
 * Matches by cheID (from the asset event) against the QC name on the action's
 * work instructions (fetchChe), and by operationalEvent against the condition type.
 */
public class QCAssetEventEvaluator implements CompletionConditionEvaluator {

    private static final Logger logger = Logger.getLogger(QCAssetEventEvaluator.class.getName());

    public static final String CONDITION_TYPE = "QC_ASSET_EVENT";

    @Override
    public List<String> evaluateSatisfied(Event event, Map<UUID, Action> activeActions) {
        if (!(event instanceof AssetEvent assetEvent)) {
            return List.of();
        }

        String cheId = assetEvent.cheId();
        String operationalEvent = assetEvent.operationalEvent();
        if (cheId == null || operationalEvent == null) {
            return List.of();
        }

        List<String> satisfiedConditionIds = new ArrayList<>();

        for (Map.Entry<UUID, Action> entry : activeActions.entrySet()) {
            Action action = entry.getValue();
            if (action.status() != ActionStatus.ACTIVE) continue;
            if (action.completionConditions() == null || action.completionConditions().isEmpty()) continue;

            // Match cheID against the QC name from work instructions
            String actionQc = resolveQcName(action);
            if (actionQc == null || !actionQc.equals(cheId)) continue;

            for (CompletionCondition condition : action.completionConditions()) {
                if (CONDITION_TYPE.equals(condition.type()) && operationalEvent.equals(condition.description())) {
                    logger.info("QC asset event satisfied condition '" + condition.id()
                            + "' on action " + entry.getKey() + ": " + operationalEvent + " from " + cheId);
                    satisfiedConditionIds.add(condition.id());
                }
            }
        }

        return satisfiedConditionIds;
    }

    private String resolveQcName(Action action) {
        if (action.workInstructions() == null || action.workInstructions().isEmpty()) {
            return null;
        }
        return action.workInstructions().getFirst().fetchChe();
    }
}
