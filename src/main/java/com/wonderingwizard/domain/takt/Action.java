package com.wonderingwizard.domain.takt;

import com.wonderingwizard.events.WorkInstructionEvent;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Represents an action within a Takt.
 */
public record Action(UUID id, DeviceType deviceType, ActionType actionType, String description, Set<UUID> dependsOn,
                     int containerIndex, int durationSeconds, int deviceIndex, List<WorkInstructionEvent> workInstructions,
                     List<EventGateCondition> eventGates, boolean skipWhenGatesSatisfied,
                     Long cheId, String cheShortName, String targetChe, CompletionReason completionReason,
                     ActionStatus status, List<LocationFreeCondition> locationSkipConditions,
                     List<CompletionCondition> completionConditions,
                     Instant plannedStartTime, Instant plannedEndTime,
                     Instant estimatedStartTime, Instant estimatedEndTime,
                     Instant actualStartTime, Instant actualEndTime) {

    private static final List<CompletionCondition> NO_CONDITIONS = List.of();

    public Action(UUID id, DeviceType deviceType, ActionType actionType, String description, Set<UUID> dependsOn,
                  int containerIndex, int durationSeconds, int deviceIndex, List<WorkInstructionEvent> workInstructions,
                  List<EventGateCondition> eventGates, boolean skipWhenGatesSatisfied) {
        this(id, deviceType, actionType, description, dependsOn, containerIndex, durationSeconds, deviceIndex,
                workInstructions, eventGates, skipWhenGatesSatisfied, null, null, null, null, ActionStatus.PENDING, List.of(), NO_CONDITIONS, null, null, null, null, null, null);
    }

    public Action(UUID id, DeviceType deviceType, ActionType actionType, String description, Set<UUID> dependsOn,
                  int containerIndex, int durationSeconds, int deviceIndex, List<WorkInstructionEvent> workInstructions,
                  List<EventGateCondition> eventGates) {
        this(id, deviceType, actionType, description, dependsOn, containerIndex, durationSeconds, deviceIndex, workInstructions, eventGates, false, null, null, null, null, ActionStatus.PENDING, List.of(), NO_CONDITIONS, null, null, null, null, null, null);
    }

    public Action(UUID id, DeviceType deviceType, ActionType actionType, String description, Set<UUID> dependsOn,
                  int containerIndex, int durationSeconds, int deviceIndex, List<WorkInstructionEvent> workInstructions) {
        this(id, deviceType, actionType, description, dependsOn, containerIndex, durationSeconds, deviceIndex, workInstructions, List.of(), false, null, null, null, null, ActionStatus.PENDING, List.of(), NO_CONDITIONS, null, null, null, null, null, null);
    }

    public Action(UUID id, DeviceType deviceType, ActionType actionType, String description, Set<UUID> dependsOn,
                  int containerIndex, int durationSeconds) {
        this(id, deviceType, actionType, description, dependsOn, containerIndex, durationSeconds, 0, List.of(), List.of(), false, null, null, null, null, ActionStatus.PENDING, List.of(), NO_CONDITIONS, null, null, null, null, null, null);
    }

    public Action(UUID id, DeviceType deviceType, ActionType actionType, String description, Set<UUID> dependsOn) {
        this(id, deviceType, actionType, description, dependsOn, 0, DeviceActionTemplate.DEFAULT_DURATION_SECONDS, 0, List.of(), List.of(), false, null, null, null, null, ActionStatus.PENDING, List.of(), NO_CONDITIONS, null, null, null, null, null, null);
    }

    public static Action create(DeviceType deviceType, ActionType actionType) {
        return new Action(UUID.randomUUID(), deviceType, actionType, actionType.displayName(), Set.of(), 0, DeviceActionTemplate.DEFAULT_DURATION_SECONDS, 0, List.of(), List.of(), false, null, null, null, null, ActionStatus.PENDING, List.of(), NO_CONDITIONS, null, null, null, null, null, null);
    }

    public static Action create(DeviceType deviceType, ActionType actionType, int containerIndex) {
        return new Action(UUID.randomUUID(), deviceType, actionType, actionType.displayName(), Set.of(), containerIndex, DeviceActionTemplate.DEFAULT_DURATION_SECONDS, 0, List.of(), List.of(), false, null, null, null, null, ActionStatus.PENDING, List.of(), NO_CONDITIONS, null, null, null, null, null, null);
    }

    public static Action create(DeviceType deviceType, ActionType actionType, int containerIndex, int durationSeconds) {
        return new Action(UUID.randomUUID(), deviceType, actionType, actionType.displayName(), Set.of(), containerIndex, durationSeconds, 0, List.of(), List.of(), false, null, null, null, null, ActionStatus.PENDING, List.of(), NO_CONDITIONS, null, null, null, null, null, null);
    }

    public static Action create(DeviceType deviceType, ActionType actionType, int containerSuffix, int containerIndex, int durationSeconds) {
        return new Action(UUID.randomUUID(), deviceType, actionType, actionType.displayName(containerSuffix), Set.of(), containerIndex, durationSeconds, 0, List.of(), List.of(), false, null, null, null, null, ActionStatus.PENDING, List.of(), NO_CONDITIONS, null, null, null, null, null, null);
    }

    private Action with(Long cheId, String cheShortName, String targetChe, CompletionReason completionReason,
                        ActionStatus status, List<LocationFreeCondition> locationSkipConditions, List<CompletionCondition> completionConditions) {
        return new Action(this.id, this.deviceType, this.actionType, this.description, this.dependsOn, this.containerIndex, this.durationSeconds, this.deviceIndex, this.workInstructions, this.eventGates, this.skipWhenGatesSatisfied, cheId, cheShortName, targetChe, completionReason, status, locationSkipConditions, completionConditions, this.plannedStartTime, this.plannedEndTime, this.estimatedStartTime, this.estimatedEndTime, this.actualStartTime, this.actualEndTime);
    }

    public Action withDependencies(Set<UUID> dependsOn) {
        return new Action(this.id, this.deviceType, this.actionType, this.description, dependsOn, this.containerIndex, this.durationSeconds, this.deviceIndex, this.workInstructions, this.eventGates, this.skipWhenGatesSatisfied, this.cheId, this.cheShortName, this.targetChe, this.completionReason, this.status, this.locationSkipConditions, this.completionConditions, this.plannedStartTime, this.plannedEndTime, this.estimatedStartTime, this.estimatedEndTime, this.actualStartTime, this.actualEndTime);
    }

    public Action withWorkInstructions(List<WorkInstructionEvent> workInstructions) {
        return new Action(this.id, this.deviceType, this.actionType, this.description, this.dependsOn, this.containerIndex, this.durationSeconds, this.deviceIndex, workInstructions, this.eventGates, this.skipWhenGatesSatisfied, this.cheId, this.cheShortName, this.targetChe, this.completionReason, this.status, this.locationSkipConditions, this.completionConditions, this.plannedStartTime, this.plannedEndTime, this.estimatedStartTime, this.estimatedEndTime, this.actualStartTime, this.actualEndTime);
    }

    public Action withEventGates(List<EventGateCondition> eventGates) {
        return new Action(this.id, this.deviceType, this.actionType, this.description, this.dependsOn, this.containerIndex, this.durationSeconds, this.deviceIndex, this.workInstructions, eventGates, this.skipWhenGatesSatisfied, this.cheId, this.cheShortName, this.targetChe, this.completionReason, this.status, this.locationSkipConditions, this.completionConditions, this.plannedStartTime, this.plannedEndTime, this.estimatedStartTime, this.estimatedEndTime, this.actualStartTime, this.actualEndTime);
    }

    public Action withTruckAssignment(Long cheId, String cheShortName) {
        return with(cheId, cheShortName, this.targetChe, this.completionReason, this.status, this.locationSkipConditions, this.completionConditions);
    }

    public Action withTargetChe(String targetChe) {
        return with(this.cheId, this.cheShortName, targetChe, this.completionReason, this.status, this.locationSkipConditions, this.completionConditions);
    }

    public Action withCompletionReason(CompletionReason completionReason) {
        return with(this.cheId, this.cheShortName, this.targetChe, completionReason, this.status, this.locationSkipConditions, this.completionConditions);
    }

    public Action withLocationSkipConditions(List<LocationFreeCondition> locationSkipConditions) {
        return with(this.cheId, this.cheShortName, this.targetChe, this.completionReason, this.status, locationSkipConditions, this.completionConditions);
    }

    public Action withCompletionConditions(List<CompletionCondition> completionConditions) {
        return with(this.cheId, this.cheShortName, this.targetChe, this.completionReason, this.status, this.locationSkipConditions, completionConditions);
    }

    public Action withStatus(ActionStatus status) {
        return with(this.cheId, this.cheShortName, this.targetChe, this.completionReason, status, this.locationSkipConditions, this.completionConditions);
    }

    public Action withPlannedTimes(Instant plannedStartTime, Instant plannedEndTime) {
        return new Action(this.id, this.deviceType, this.actionType, this.description, this.dependsOn, this.containerIndex, this.durationSeconds, this.deviceIndex, this.workInstructions, this.eventGates, this.skipWhenGatesSatisfied, this.cheId, this.cheShortName, this.targetChe, this.completionReason, this.status, this.locationSkipConditions, this.completionConditions, plannedStartTime, plannedEndTime, this.estimatedStartTime, this.estimatedEndTime, this.actualStartTime, this.actualEndTime);
    }

    public Action withEstimatedTimes(Instant estimatedStartTime, Instant estimatedEndTime) {
        return new Action(this.id, this.deviceType, this.actionType, this.description, this.dependsOn, this.containerIndex, this.durationSeconds, this.deviceIndex, this.workInstructions, this.eventGates, this.skipWhenGatesSatisfied, this.cheId, this.cheShortName, this.targetChe, this.completionReason, this.status, this.locationSkipConditions, this.completionConditions, this.plannedStartTime, this.plannedEndTime, estimatedStartTime, estimatedEndTime, this.actualStartTime, this.actualEndTime);
    }

    public Action withActualTimes(Instant actualStartTime, Instant actualEndTime) {
        return new Action(this.id, this.deviceType, this.actionType, this.description, this.dependsOn, this.containerIndex, this.durationSeconds, this.deviceIndex, this.workInstructions, this.eventGates, this.skipWhenGatesSatisfied, this.cheId, this.cheShortName, this.targetChe, this.completionReason, this.status, this.locationSkipConditions, this.completionConditions, this.plannedStartTime, this.plannedEndTime, this.estimatedStartTime, this.estimatedEndTime, actualStartTime, actualEndTime);
    }

    public boolean hasNoDependencies() {
        return dependsOn == null || dependsOn.isEmpty();
    }
}
