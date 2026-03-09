package com.wonderingwizard.domain.takt;

import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Condition that requires dependency actions to be completed before this action can start.
 * Applies to non-first actions that depend on preceding actions within the takt.
 */
public record ActionDependencyCondition(String id, Set<UUID> dependencyIds,
                                         Map<UUID, String> dependencyDescriptions) implements ActionCondition {

    public ActionDependencyCondition(Set<UUID> dependencyIds, Map<UUID, String> dependencyDescriptions) {
        this("action-dependencies", dependencyIds, dependencyDescriptions);
    }

    @Override
    public boolean evaluate(ActionConditionContext context) {
        return context.completedActionIds().containsAll(dependencyIds);
    }

    @Override
    public String explanation(ActionConditionContext context) {
        StringJoiner pending = new StringJoiner(", ");
        for (UUID depId : dependencyIds) {
            if (!context.completedActionIds().contains(depId)) {
                String desc = dependencyDescriptions.getOrDefault(depId, depId.toString().substring(0, 8));
                pending.add("'" + desc + "'");
            }
        }
        return "Waiting for action " + pending + " to complete";
    }

    @Override
    public String type() {
        return "ACTION_DEPENDENCY";
    }
}
