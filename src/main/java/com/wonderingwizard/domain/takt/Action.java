package com.wonderingwizard.domain.takt;

import com.wonderingwizard.sideeffects.WorkInstruction;

import java.util.List;
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
 * @param actionType the type-safe action identifier for compile-time checked mapping
 * @param description the display name (e.g., "QC Lift1" for twin operations)
 * @param dependsOn set of action IDs that must be completed before this action can be activated
 * @param containerIndex the index of the container (work instruction) this action belongs to
 * @param durationSeconds the duration of this action in seconds
 * @param deviceIndex the index of the physical device instance (e.g., 0 for RTG-A, 1 for RTG-B in different-bay twin carry)
 * @param workInstructions the work instructions associated with this action
 */
public record Action(UUID id, DeviceType deviceType, ActionType actionType, String description, Set<UUID> dependsOn,
                     int containerIndex, int durationSeconds, int deviceIndex, List<WorkInstruction> workInstructions) {

    /**
     * Constructor without deviceIndex and workInstructions.
     */
    public Action(UUID id, DeviceType deviceType, ActionType actionType, String description, Set<UUID> dependsOn,
                  int containerIndex, int durationSeconds) {
        this(id, deviceType, actionType, description, dependsOn, containerIndex, durationSeconds, 0, List.of());
    }

    /**
     * Constructor without containerIndex, duration, deviceIndex, and workInstructions.
     */
    public Action(UUID id, DeviceType deviceType, ActionType actionType, String description, Set<UUID> dependsOn) {
        this(id, deviceType, actionType, description, dependsOn, 0, DeviceActionTemplate.DEFAULT_DURATION_SECONDS, 0, List.of());
    }

    /**
     * Creates an action with a generated UUID and no dependencies.
     */
    public static Action create(DeviceType deviceType, ActionType actionType) {
        return new Action(UUID.randomUUID(), deviceType, actionType, actionType.displayName(), Set.of(), 0, DeviceActionTemplate.DEFAULT_DURATION_SECONDS, 0, List.of());
    }

    /**
     * Creates an action with a generated UUID, no dependencies, and a container index.
     */
    public static Action create(DeviceType deviceType, ActionType actionType, int containerIndex) {
        return new Action(UUID.randomUUID(), deviceType, actionType, actionType.displayName(), Set.of(), containerIndex, DeviceActionTemplate.DEFAULT_DURATION_SECONDS, 0, List.of());
    }

    /**
     * Creates an action with a generated UUID, no dependencies, a container index, and custom duration.
     */
    public static Action create(DeviceType deviceType, ActionType actionType, int containerIndex, int durationSeconds) {
        return new Action(UUID.randomUUID(), deviceType, actionType, actionType.displayName(), Set.of(), containerIndex, durationSeconds, 0, List.of());
    }

    /**
     * Creates an action with a generated UUID, no dependencies, a container index, custom duration, and display suffix.
     */
    public static Action create(DeviceType deviceType, ActionType actionType, String suffix, int containerIndex, int durationSeconds) {
        return new Action(UUID.randomUUID(), deviceType, actionType, actionType.displayName(suffix), Set.of(), containerIndex, durationSeconds, 0, List.of());
    }

    /**
     * Creates a copy of this action with the specified dependencies.
     */
    public Action withDependencies(Set<UUID> dependsOn) {
        return new Action(this.id, this.deviceType, this.actionType, this.description, dependsOn, this.containerIndex, this.durationSeconds, this.deviceIndex, this.workInstructions);
    }

    /**
     * Creates a copy of this action with the specified work instructions.
     */
    public Action withWorkInstructions(List<WorkInstruction> workInstructions) {
        return new Action(this.id, this.deviceType, this.actionType, this.description, this.dependsOn, this.containerIndex, this.durationSeconds, this.deviceIndex, workInstructions);
    }

    /**
     * Checks if this action has no dependencies (can be activated immediately).
     */
    public boolean hasNoDependencies() {
        return dependsOn == null || dependsOn.isEmpty();
    }
}
