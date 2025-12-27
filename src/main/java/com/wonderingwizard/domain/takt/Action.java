package com.wonderingwizard.domain.takt;

import java.util.UUID;

/**
 * Represents an action within a Takt.
 * Actions are the individual operations performed during a takt cycle.
 * Each action has a unique ID and can reference the next action in the sequence.
 *
 * @param id unique identifier for this action
 * @param description the action description (e.g., "QC lift container from truck")
 * @param nextActionId the ID of the next action in the sequence, or null if this is the last action
 */
public record Action(UUID id, String description, UUID nextActionId) {

    /**
     * Creates an action with a generated UUID and no next action.
     *
     * @param description the action description
     * @return a new Action with generated ID
     */
    public static Action create(String description) {
        return new Action(UUID.randomUUID(), description, null);
    }

    /**
     * Creates a copy of this action with the specified next action ID.
     *
     * @param nextActionId the ID of the next action
     * @return a new Action with the same id and description but with the specified nextActionId
     */
    public Action withNextActionId(UUID nextActionId) {
        return new Action(this.id, this.description, nextActionId);
    }
}
