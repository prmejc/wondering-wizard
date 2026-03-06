package com.wonderingwizard.domain.takt;

import java.util.Set;
import java.util.UUID;

/**
 * Represents an action within a Takt.
 * Actions are the individual operations performed during a takt cycle.
 * Each action has a unique ID, belongs to a device type, and can depend on other actions
 * to complete before it can be activated.
 *
 * @param id unique identifier for this action
 * @param deviceType the type of device performing this action
 * @param description the action description (e.g., "container lifted from truck")
 * @param dependsOn set of action IDs that must be completed before this action can be activated
 * @param containerIndex the index of the container (work instruction) this action belongs to
 * @param durationSeconds the duration of this action in seconds
 */
public record Action(UUID id, DeviceType deviceType, String description, Set<UUID> dependsOn,
                     int containerIndex, int durationSeconds) {

    /**
     * Backward-compatible constructor without containerIndex and duration (defaults to 0 and 30).
     */
    public Action(UUID id, DeviceType deviceType, String description, Set<UUID> dependsOn) {
        this(id, deviceType, description, dependsOn, 0, DeviceActionTemplate.DEFAULT_DURATION_SECONDS);
    }

    /**
     * Backward-compatible constructor without duration (defaults to 30).
     */
    public Action(UUID id, DeviceType deviceType, String description, Set<UUID> dependsOn, int containerIndex) {
        this(id, deviceType, description, dependsOn, containerIndex, DeviceActionTemplate.DEFAULT_DURATION_SECONDS);
    }

    /**
     * Creates an action with a generated UUID and no dependencies.
     */
    public static Action create(DeviceType deviceType, String description) {
        return new Action(UUID.randomUUID(), deviceType, description, Set.of(), 0, DeviceActionTemplate.DEFAULT_DURATION_SECONDS);
    }

    /**
     * Creates an action with a generated UUID, no dependencies, and a container index.
     */
    public static Action create(DeviceType deviceType, String description, int containerIndex) {
        return new Action(UUID.randomUUID(), deviceType, description, Set.of(), containerIndex, DeviceActionTemplate.DEFAULT_DURATION_SECONDS);
    }

    /**
     * Creates an action with a generated UUID, no dependencies, a container index, and custom duration.
     */
    public static Action create(DeviceType deviceType, String description, int containerIndex, int durationSeconds) {
        return new Action(UUID.randomUUID(), deviceType, description, Set.of(), containerIndex, durationSeconds);
    }

    /**
     * Creates an action with a generated UUID and no dependencies (legacy method).
     *
     * @deprecated Use {@link #create(DeviceType, String)} instead
     */
    @Deprecated
    public static Action create(String description) {
        return new Action(UUID.randomUUID(), DeviceType.QC, description, Set.of(), 0, DeviceActionTemplate.DEFAULT_DURATION_SECONDS);
    }

    /**
     * Creates a copy of this action with the specified dependencies.
     */
    public Action withDependencies(Set<UUID> dependsOn) {
        return new Action(this.id, this.deviceType, this.description, dependsOn, this.containerIndex, this.durationSeconds);
    }

    /**
     * Checks if this action has no dependencies (can be activated immediately).
     */
    public boolean hasNoDependencies() {
        return dependsOn == null || dependsOn.isEmpty();
    }
}
