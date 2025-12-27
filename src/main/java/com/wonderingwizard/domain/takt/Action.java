package com.wonderingwizard.domain.takt;

import java.util.Set;
import java.util.UUID;

/**
 * Represents an action within a Takt.
 * Actions are the individual operations performed during a takt cycle.
 * Each action has a unique ID and can depend on other actions to complete before it can be activated.
 *
 * @param id unique identifier for this action
 * @param description the action description (e.g., "QC lift container from truck")
 * @param dependsOn set of action IDs that must be completed before this action can be activated
 */
public record Action(UUID id, String description, Set<UUID> dependsOn) {

    /**
     * Creates an action with a generated UUID and no dependencies.
     *
     * @param description the action description
     * @return a new Action with generated ID and no dependencies
     */
    public static Action create(String description) {
        return new Action(UUID.randomUUID(), description, Set.of());
    }

    /**
     * Creates a copy of this action with the specified dependencies.
     *
     * @param dependsOn set of action IDs this action depends on
     * @return a new Action with the same id and description but with the specified dependencies
     */
    public Action withDependencies(Set<UUID> dependsOn) {
        return new Action(this.id, this.description, dependsOn);
    }

    /**
     * Checks if this action has no dependencies (can be activated immediately).
     *
     * @return true if this action has no dependencies
     */
    public boolean hasNoDependencies() {
        return dependsOn == null || dependsOn.isEmpty();
    }
}
