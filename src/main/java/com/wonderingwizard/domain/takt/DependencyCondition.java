package com.wonderingwizard.domain.takt;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Condition that requires all external dependencies of a takt's first actions to be completed.
 * "First actions" are those with no intra-takt dependencies — they are the entry points of the takt.
 * This condition checks that their cross-takt dependencies (from previous takts) are all completed.
 *
 * @param id the condition identifier
 * @param externalDependencies map from first action description to its external dependency action IDs
 * @param dependencyDescriptions map from dependency action ID to its description (for explanation)
 */
public record DependencyCondition(
        String id,
        Map<String, Set<UUID>> externalDependencies,
        Map<UUID, String> dependencyDescriptions
) implements TaktCondition {

    public DependencyCondition(Map<String, Set<UUID>> externalDependencies,
                                Map<UUID, String> dependencyDescriptions) {
        this("dependencies", externalDependencies, dependencyDescriptions);
    }

    @Override
    public boolean evaluate(ConditionContext context) {
        for (Set<UUID> deps : externalDependencies.values()) {
            if (!context.completedActionIds().containsAll(deps)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String explanation(ConditionContext context) {
        StringBuilder sb = new StringBuilder("Waiting for actions to complete: ");
        boolean first = true;
        for (Map.Entry<String, Set<UUID>> entry : externalDependencies.entrySet()) {
            for (UUID depId : entry.getValue()) {
                if (!context.completedActionIds().contains(depId)) {
                    if (!first) sb.append(", ");
                    String desc = dependencyDescriptions.getOrDefault(depId, depId.toString().substring(0, 8));
                    sb.append("\"").append(desc).append("\"");
                    first = false;
                }
            }
        }
        return sb.toString();
    }

    @Override
    public String type() {
        return "DEPENDENCY";
    }
}
