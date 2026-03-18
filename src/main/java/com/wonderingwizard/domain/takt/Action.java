package com.wonderingwizard.domain.takt;

import com.wonderingwizard.events.WorkInstructionEvent;

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
 * @param eventGates event gate conditions that must be satisfied before this action can activate
 * @param cheId the CHE identifier of the assigned truck (nullable, only for TT actions)
 * @param cheShortName the short name of the assigned truck (nullable, only for TT actions)
 * @param completionReason the reason for force-completion (nullable, null means normal completion)
 */
public record Action(UUID id, DeviceType deviceType, ActionType actionType, String description, Set<UUID> dependsOn,
                     int containerIndex, int durationSeconds, int deviceIndex, List<WorkInstructionEvent> workInstructions,
                     List<EventGateCondition> eventGates, boolean skipWhenGatesSatisfied,
                     Long cheId, String cheShortName, CompletionReason completionReason) {

    /**
     * Constructor without cheId and cheShortName.
     */
    public Action(UUID id, DeviceType deviceType, ActionType actionType, String description, Set<UUID> dependsOn,
                  int containerIndex, int durationSeconds, int deviceIndex, List<WorkInstructionEvent> workInstructions,
                  List<EventGateCondition> eventGates, boolean skipWhenGatesSatisfied) {
        this(id, deviceType, actionType, description, dependsOn, containerIndex, durationSeconds, deviceIndex,
                workInstructions, eventGates, skipWhenGatesSatisfied, null, null, null);
    }

    /**
     * Constructor without skipWhenGatesSatisfied.
     */
    public Action(UUID id, DeviceType deviceType, ActionType actionType, String description, Set<UUID> dependsOn,
                  int containerIndex, int durationSeconds, int deviceIndex, List<WorkInstructionEvent> workInstructions,
                  List<EventGateCondition> eventGates) {
        this(id, deviceType, actionType, description, dependsOn, containerIndex, durationSeconds, deviceIndex, workInstructions, eventGates, false, null, null, null);
    }

    /**
     * Constructor without eventGates.
     */
    public Action(UUID id, DeviceType deviceType, ActionType actionType, String description, Set<UUID> dependsOn,
                  int containerIndex, int durationSeconds, int deviceIndex, List<WorkInstructionEvent> workInstructions) {
        this(id, deviceType, actionType, description, dependsOn, containerIndex, durationSeconds, deviceIndex, workInstructions, List.of(), false, null, null, null);
    }

    /**
     * Constructor without deviceIndex, workInstructions, and eventGates.
     */
    public Action(UUID id, DeviceType deviceType, ActionType actionType, String description, Set<UUID> dependsOn,
                  int containerIndex, int durationSeconds) {
        this(id, deviceType, actionType, description, dependsOn, containerIndex, durationSeconds, 0, List.of(), List.of(), false, null, null, null);
    }

    /**
     * Constructor without containerIndex, duration, deviceIndex, workInstructions, and eventGates.
     */
    public Action(UUID id, DeviceType deviceType, ActionType actionType, String description, Set<UUID> dependsOn) {
        this(id, deviceType, actionType, description, dependsOn, 0, DeviceActionTemplate.DEFAULT_DURATION_SECONDS, 0, List.of(), List.of(), false, null, null, null);
    }

    /**
     * Creates an action with a generated UUID and no dependencies.
     */
    public static Action create(DeviceType deviceType, ActionType actionType) {
        return new Action(UUID.randomUUID(), deviceType, actionType, actionType.displayName(), Set.of(), 0, DeviceActionTemplate.DEFAULT_DURATION_SECONDS, 0, List.of(), List.of(), false, null, null, null);
    }

    /**
     * Creates an action with a generated UUID, no dependencies, and a container index.
     */
    public static Action create(DeviceType deviceType, ActionType actionType, int containerIndex) {
        return new Action(UUID.randomUUID(), deviceType, actionType, actionType.displayName(), Set.of(), containerIndex, DeviceActionTemplate.DEFAULT_DURATION_SECONDS, 0, List.of(), List.of(), false, null, null, null);
    }

    /**
     * Creates an action with a generated UUID, no dependencies, a container index, and custom duration.
     */
    public static Action create(DeviceType deviceType, ActionType actionType, int containerIndex, int durationSeconds) {
        return new Action(UUID.randomUUID(), deviceType, actionType, actionType.displayName(), Set.of(), containerIndex, durationSeconds, 0, List.of(), List.of(), false, null, null, null);
    }

    /**
     * Creates an action with a generated UUID, no dependencies, a container index, custom duration, and display suffix.
     */
    public static Action create(DeviceType deviceType, ActionType actionType, int containerSuffix, int containerIndex, int durationSeconds) {
        return new Action(UUID.randomUUID(), deviceType, actionType, actionType.displayName(containerSuffix), Set.of(), containerIndex, durationSeconds, 0, List.of(), List.of(), false, null, null, null);
    }

    /**
     * Creates a copy of this action with the specified dependencies.
     */
    public Action withDependencies(Set<UUID> dependsOn) {
        return new Action(this.id, this.deviceType, this.actionType, this.description, dependsOn, this.containerIndex, this.durationSeconds, this.deviceIndex, this.workInstructions, this.eventGates, this.skipWhenGatesSatisfied, this.cheId, this.cheShortName, this.completionReason);
    }

    /**
     * Creates a copy of this action with the specified work instructions.
     */
    public Action withWorkInstructions(List<WorkInstructionEvent> workInstructions) {
        return new Action(this.id, this.deviceType, this.actionType, this.description, this.dependsOn, this.containerIndex, this.durationSeconds, this.deviceIndex, workInstructions, this.eventGates, this.skipWhenGatesSatisfied, this.cheId, this.cheShortName, this.completionReason);
    }

    /**
     * Creates a copy of this action with the specified event gates.
     */
    public Action withEventGates(List<EventGateCondition> eventGates) {
        return new Action(this.id, this.deviceType, this.actionType, this.description, this.dependsOn, this.containerIndex, this.durationSeconds, this.deviceIndex, this.workInstructions, eventGates, this.skipWhenGatesSatisfied, this.cheId, this.cheShortName, this.completionReason);
    }

    /**
     * Creates a copy of this action with the assigned truck.
     *
     * @param cheId the CHE identifier of the truck
     * @param cheShortName the short name of the truck
     */
    public Action withTruckAssignment(Long cheId, String cheShortName) {
        return new Action(this.id, this.deviceType, this.actionType, this.description, this.dependsOn, this.containerIndex, this.durationSeconds, this.deviceIndex, this.workInstructions, this.eventGates, this.skipWhenGatesSatisfied, cheId, cheShortName, this.completionReason);
    }

    /**
     * Creates a copy of this action with the specified completion reason.
     *
     * @param completionReason the reason for force-completion
     */
    public Action withCompletionReason(CompletionReason completionReason) {
        return new Action(this.id, this.deviceType, this.actionType, this.description, this.dependsOn, this.containerIndex, this.durationSeconds, this.deviceIndex, this.workInstructions, this.eventGates, this.skipWhenGatesSatisfied, this.cheId, this.cheShortName, completionReason);
    }

    /**
     * Checks if this action has no dependencies (can be activated immediately).
     */
    public boolean hasNoDependencies() {
        return dependsOn == null || dependsOn.isEmpty();
    }
}
