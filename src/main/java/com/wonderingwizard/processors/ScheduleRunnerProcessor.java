package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionStatus;
import com.wonderingwizard.domain.takt.CompletionCondition;
import com.wonderingwizard.domain.takt.ActionConditionContext;
import com.wonderingwizard.domain.takt.ConditionMode;
import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.CompletionReason;
import com.wonderingwizard.domain.takt.EquipmentPosition;
import com.wonderingwizard.domain.takt.LocationFreeCondition;
import com.wonderingwizard.domain.takt.ConditionContext;
import com.wonderingwizard.domain.takt.DependencyCondition;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.domain.takt.EventGateCondition;
import com.wonderingwizard.domain.takt.TaktCondition;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.domain.takt.TimeCondition;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.EventProcessor;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.ActionCompletedEvent;
import com.wonderingwizard.events.AssetEvent;
import com.wonderingwizard.events.CheTargetPositionEvent;
import com.wonderingwizard.events.JobOperationEvent;
import com.wonderingwizard.events.NukeWorkQueueEvent;
import com.wonderingwizard.events.OverrideActionConditionEvent;
import com.wonderingwizard.events.OverrideConditionEvent;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.sideeffects.ActionActivated;
import com.wonderingwizard.sideeffects.ActionCompleted;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import com.wonderingwizard.sideeffects.TaktActivated;
import com.wonderingwizard.sideeffects.TaktCompleted;
import com.wonderingwizard.sideeffects.TruckAssigned;
import com.wonderingwizard.sideeffects.TruckUnassigned;
import com.wonderingwizard.sideeffects.WorkInstructionCanceled;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.logging.Logger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Processor that handles schedule execution with takt and action state machines.
 * <p>
 * Takt state machine: Waiting → Active → Completed
 * <ul>
 *   <li>A takt transitions to Active when all its {@link TaktCondition}s are satisfied
 *       (or overridden)</li>
 *   <li>A takt transitions to Completed when all its actions are Completed</li>
 * </ul>
 * <p>
 * Action state machine: Waiting → Active → Completed
 * <ul>
 *   <li>An action transitions to Active when its takt is Active AND all its dependencies are Completed</li>
 *   <li>An action transitions to Completed when an ActionCompletedEvent with matching UUID is received</li>
 * </ul>
 */
public class ScheduleRunnerProcessor implements EventProcessor {

    public enum TaktState { WAITING, ACTIVE, COMPLETED }

    /**
     * Tracks the schedule state for each work queue.
     */
    private static class ScheduleState {
        Instant estimatedMoveTime;
        List<Takt> takts;
        Map<UUID, ActionInfo> actionLookup;
        Map<String, TaktState> taktStates;
        Map<String, Instant> actualStartTimes;
        /** Conditions per takt, keyed by takt name. */
        Map<String, List<TaktCondition>> taktConditions;
        /** Overridden condition IDs per takt, keyed by takt name. */
        Map<String, Set<String>> overriddenConditions;
        /** Overridden action condition IDs per action, keyed by action UUID. */
        Map<UUID, Set<String>> overriddenActionConditions;
        /** Satisfied event gate IDs per gated action UUID. */
        Map<UUID, Set<String>> satisfiedEventGates;
        /** Armed event gate IDs per gated action UUID. */
        Map<UUID, Set<String>> armedEventGates;
        /** Index from event type to gated action UUIDs for fast lookup on WI event arrival. */
        Map<String, List<UUID>> eventTypeToGatedActions;
        /** Maps gated action UUID to the source action UUID that arms the gate. */
        Map<UUID, UUID> gateArmSourceActions;

        ScheduleState(Instant estimatedMoveTime, List<Takt> takts) {
            this.estimatedMoveTime = estimatedMoveTime;
            this.takts = takts;
            this.actionLookup = new HashMap<>();
            this.taktStates = new HashMap<>();
            this.actualStartTimes = new HashMap<>();
            this.taktConditions = new LinkedHashMap<>();
            this.overriddenConditions = new HashMap<>();
            this.overriddenActionConditions = new HashMap<>();
            this.satisfiedEventGates = new HashMap<>();
            this.armedEventGates = new HashMap<>();
            this.eventTypeToGatedActions = new HashMap<>();
            this.gateArmSourceActions = new HashMap<>();

            // Build action lookup, initialize takt states, and index event gates
            for (Takt takt : takts) {
                taktStates.put(takt.name(), TaktState.WAITING);
                overriddenConditions.put(takt.name(), new HashSet<>());
                for (Action action : takt.actions()) {
                    actionLookup.put(action.id(), new ActionInfo(takt.name(), action));
                    // Index event gates for fast lookup
                    for (EventGateCondition gate : action.eventGates()) {
                        eventTypeToGatedActions
                                .computeIfAbsent(gate.requiredEventType(), k -> new ArrayList<>())
                                .add(action.id());
                    }
                }
            }

            // Resolve gate arm source action UUIDs
            for (ActionInfo info : actionLookup.values()) {
                for (EventGateCondition gate : info.action().eventGates()) {
                    // Find the source action in the same container
                    for (ActionInfo candidate : actionLookup.values()) {
                        if (candidate.action().containerIndex() == info.action().containerIndex()
                                && candidate.action().deviceType() == gate.sourceDeviceType()
                                && candidate.action().actionType() == gate.sourceActionType()) {
                            gateArmSourceActions.put(info.action().id(), candidate.action().id());
                            break;
                        }
                    }
                }
            }
        }

        /**
         * Returns actions from non-COMPLETED takts only. This avoids iterating
         * the thousands of COMPLETED actions that accumulate over time.
         */
        List<ActionInfo> getActiveAndWaitingTaktActions() {
            List<ActionInfo> result = new ArrayList<>();
            for (Takt takt : takts) {
                if (taktStates.get(takt.name()) == TaktState.COMPLETED) continue;
                for (Action action : takt.actions()) {
                    ActionInfo info = actionLookup.get(action.id());
                    if (info != null) result.add(info);
                }
            }
            return result;
        }

        /**
         * Gets all actions in a given takt that are eligible for activation.
         * An action is eligible when it is not yet active or completed,
         * and all its dependencies are completed.
         */
        List<UUID> getActivatableActionsInTakt(String taktName) {
            return getActivatableActionsInTakt(taktName, Set.of());
        }

        List<UUID> getActivatableActionsInTakt(String taktName, Set<String> occupiedPositions) {
            List<UUID> result = new ArrayList<>();
            // Find the takt and iterate only its actions (not all actions in the schedule)
            Takt targetTakt = null;
            for (Takt t : takts) {
                if (t.name().equals(taktName)) { targetTakt = t; break; }
            }
            if (targetTakt == null) return result;
            for (Action taktAction : targetTakt.actions()) {
                ActionInfo info = actionLookup.get(taktAction.id());
                if (info == null) continue;
                UUID actionId = info.action().id();
                if (info.action().status() != ActionStatus.PENDING) {
                    continue;
                }
                Set<String> actionOverrides = overriddenActionConditions.getOrDefault(actionId, Set.of());
                boolean depsOverridden = actionOverrides.contains("action-dependencies");
                Set<UUID> dependencies = info.action().dependsOn();
                boolean depsSatisfied = depsOverridden || dependencies == null || dependencies.isEmpty() || areDependenciesCompleted(dependencies);
                if (!depsSatisfied) {
                    continue;
                }
                // For skipWhenGatesSatisfied actions, event gates define the skip condition,
                // not an activation barrier — so don't block on them.
                if (!info.action().skipWhenGatesSatisfied()
                        && !areEventGatesSatisfied(actionId, info.action(), actionOverrides)) {
                    continue;
                }
                // Check ACTIVATE-mode location conditions: block if target position is occupied
                if (isBlockedByLocation(info.action(), occupiedPositions, actionOverrides)) {
                    continue;
                }

                result.add(actionId);
            }
            return result;
        }

        /**
         * Checks if any ACTIVATE-mode location condition blocks this action
         * (the target position is occupied, so the truck can't enter).
         */
        private boolean isBlockedByLocation(Action action, Set<String> occupiedPositions, Set<String> overrides) {
            for (LocationFreeCondition cond : action.locationSkipConditions()) {
                if (cond.conditionMode() != ConditionMode.ACTIVATE) continue;
                if (overrides.contains(cond.id())) continue;
                if (isPositionOccupied(cond, action, occupiedPositions)) return true;
            }
            return false;
        }

        /**
         * Checks if all SKIP-mode location conditions are satisfied
         * (the next position is free, so this action should be skipped).
         */
        boolean shouldSkipForLocation(Action action, Set<String> occupiedPositions, Set<String> overrides) {
            boolean hasSkipConditions = false;
            for (LocationFreeCondition cond : action.locationSkipConditions()) {
                if (cond.conditionMode() != ConditionMode.SKIP) continue;
                if (overrides.contains(cond.id())) continue;
                hasSkipConditions = true;
                if (isPositionOccupied(cond, action, occupiedPositions)) return false; // occupied → don't skip
            }
            return hasSkipConditions; // all free → skip
        }

        private boolean isPositionOccupied(LocationFreeCondition cond, Action action, Set<String> occupiedPositions) {
            String equipmentName = resolveEquipmentName(cond.deviceType(), action);
            if (equipmentName != null) {
                // Exact match: e.g., "QC:QCZ1:STANDBY"
                String exactKey = cond.deviceType().name() + ":" + equipmentName + ":" + cond.position().name();
                return occupiedPositions.contains(exactKey);
            }
            // Fallback to prefix/suffix match when equipment name is unknown
            String suffix = ":" + cond.position().name();
            String prefix = cond.deviceType().name() + ":";
            for (String key : occupiedPositions) {
                if (key.startsWith(prefix) && key.endsWith(suffix)) return true;
            }
            return false;
        }

        private String resolveEquipmentName(DeviceType deviceType, Action action) {
            return switch (deviceType) {
                case QC -> resolveQCName(action);
                case RTG -> resolveRTGName(action);
                case TT -> null;
            };
        }

        boolean areDependenciesCompleted(Set<UUID> dependencies) {
            for (UUID depId : dependencies) {
                ActionInfo depInfo = actionLookup.get(depId);
                if (depInfo == null || depInfo.action().status() != ActionStatus.COMPLETED) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Updates the action's status in the actionLookup.
         */
        void setActionStatus(UUID actionId, ActionStatus status) {
            ActionInfo info = actionLookup.get(actionId);
            if (info != null) {
                actionLookup.put(actionId, new ActionInfo(info.taktName(), info.action().withStatus(status)));
            }
        }

        boolean areEventGatesSatisfied(UUID actionId, Action action, Set<String> actionOverrides) {
            if (action.eventGates().isEmpty()) {
                return true;
            }
            Set<String> satisfied = satisfiedEventGates.getOrDefault(actionId, Set.of());
            for (EventGateCondition gate : action.eventGates()) {
                if (!satisfied.contains(gate.id()) && !actionOverrides.contains(gate.id())) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Gets all pending actions in the given takt whose dependencies are now all satisfied.
         */
        List<UUID> getNewlyActivatableActionsInTakt(String taktName) {
            return getActivatableActionsInTakt(taktName);
        }

        /**
         * Checks whether all actions in a takt are completed.
         */
        boolean isTaktFullyCompleted(String taktName) {
            // Find the takt by name and check its actions directly (avoids iterating all actions)
            for (Takt takt : takts) {
                if (takt.name().equals(taktName)) {
                    for (Action action : takt.actions()) {
                        ActionInfo info = actionLookup.get(action.id());
                        if (info != null && info.action().status() != ActionStatus.COMPLETED) {
                            return false;
                        }
                    }
                    return true;
                }
            }
            return true;
        }

        ScheduleState copy() {
            ScheduleState copy = new ScheduleState(this.estimatedMoveTime, new ArrayList<>(this.takts));
            copy.actionLookup = new HashMap<>(this.actionLookup);
            copy.taktStates = new HashMap<>(this.taktStates);
            copy.actualStartTimes = new HashMap<>(this.actualStartTimes);
            copy.taktConditions = new LinkedHashMap<>();
            for (Map.Entry<String, List<TaktCondition>> entry : this.taktConditions.entrySet()) {
                copy.taktConditions.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
            copy.overriddenConditions = new HashMap<>();
            for (Map.Entry<String, Set<String>> entry : this.overriddenConditions.entrySet()) {
                copy.overriddenConditions.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
            copy.overriddenActionConditions = new HashMap<>();
            for (Map.Entry<UUID, Set<String>> entry : this.overriddenActionConditions.entrySet()) {
                copy.overriddenActionConditions.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
            copy.satisfiedEventGates = new HashMap<>();
            for (Map.Entry<UUID, Set<String>> entry : this.satisfiedEventGates.entrySet()) {
                copy.satisfiedEventGates.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
            copy.armedEventGates = new HashMap<>();
            for (Map.Entry<UUID, Set<String>> entry : this.armedEventGates.entrySet()) {
                copy.armedEventGates.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
            return copy;
        }
    }

    private record ActionInfo(String taktName, Action action) {}

    private static final Logger logger = Logger.getLogger(ScheduleRunnerProcessor.class.getName());

    /**
     * Returns the current status of an action in a schedule.
     */
    public ActionStatus getActionStatus(long workQueueId, UUID actionId) {
        ScheduleState state = scheduleStates.get(workQueueId);
        if (state == null) return ActionStatus.PENDING;
        ActionInfo info = state.actionLookup.get(actionId);
        if (info == null) return ActionStatus.PENDING;
        return info.action().status();
    }

    /**
     * Returns the current takt state for a schedule.
     */
    public TaktState getTaktState(long workQueueId, String taktName) {
        ScheduleState state = scheduleStates.get(workQueueId);
        if (state == null) return TaktState.WAITING;
        return state.taktStates.getOrDefault(taktName, TaktState.WAITING);
    }

    /**
     * Returns the actual start time recorded for a takt.
     */
    public Instant getActualStartTime(long workQueueId, String taktName) {
        ScheduleState state = scheduleStates.get(workQueueId);
        if (state == null) return null;
        return state.actualStartTimes.get(taktName);
    }

    /**
     * Returns the set of satisfied event gate condition IDs for the given action.
     */
    public Set<String> getSatisfiedEventGates(long workQueueId, UUID actionId) {
        ScheduleState state = scheduleStates.get(workQueueId);
        if (state == null) return Set.of();
        return state.satisfiedEventGates.getOrDefault(actionId, Set.of());
    }

    /**
     * Returns the action for the given ID, with any truck assignment applied.
     */
    public Action getAction(long workQueueId, UUID actionId) {
        ScheduleState state = scheduleStates.get(workQueueId);
        if (state == null) return null;
        ActionInfo info = state.actionLookup.get(actionId);
        return info != null ? info.action() : null;
    }

    /**
     * Returns the takts for a given schedule.
     */
    public List<Takt> getScheduleTakts(long workQueueId) {
        ScheduleState state = scheduleStates.get(workQueueId);
        if (state == null) return List.of();
        return List.copyOf(state.takts);
    }

    /**
     * Returns whether a TT allocation strategy is registered.
     */
    public boolean hasTTAllocationStrategy() {
        return ttAllocationStrategy != null;
    }

    /**
     * A position occupied by a truck near QC or RTG equipment.
     *
     * @param equipmentType QC or RTG
     * @param equipmentName the equipment short name (e.g., "QCZ1", "RTZ01")
     * @param position the position (STANDBY, PULL, UNDER)
     * @param cheShortName the truck occupying this position
     * @param workQueueId the work queue the truck is working on
     * @param actionDescription the action description
     */
    public record LocationOccupancy(DeviceType equipmentType, String equipmentName,
                                     EquipmentPosition position, String cheShortName,
                                     long workQueueId, String actionDescription,
                                     Long workInstructionId, String containerId) {}

    /**
     * Computes current location occupancy across all schedules.
     * <p>
     * Simple rules:
     * <ul>
     *   <li>QC standby occupied if TT_DRIVE_TO_QC_STANDBY is (ACTIVE or COMPLETED) AND TT_DRIVE_UNDER_QC is PENDING</li>
     *   <li>QC under occupied if TT_DRIVE_UNDER_QC is (ACTIVE or COMPLETED) AND TT_DRIVE_TO_BUFFER is PENDING</li>
     *   <li>RTG standby occupied if TT_DRIVE_TO_RTG_STANDBY is (ACTIVE or COMPLETED) AND TT_DRIVE_TO_RTG_UNDER is PENDING</li>
     *   <li>RTG under occupied if TT_DRIVE_TO_RTG_UNDER is (ACTIVE or COMPLETED) AND TT_HANDOVER_TO_RTG is COMPLETED (truck still there) — cleared when TT_DRIVE_TO_QC_STANDBY activates</li>
     * </ul>
     */
    public List<LocationOccupancy> getLocationOccupancy() {
        List<LocationOccupancy> occupancies = new ArrayList<>();

        for (Map.Entry<Long, ScheduleState> entry : scheduleStates.entrySet()) {
            long workQueueId = entry.getKey();
            ScheduleState state = entry.getValue();

            // Group TT actions by containerIndex — only from non-COMPLETED takts
            // (completed takts can't have occupied positions)
            Map<Integer, Map<ActionType, List<Action>>> ttByContainer = new LinkedHashMap<>();
            for (ActionInfo info : state.getActiveAndWaitingTaktActions()) {
                Action action = info.action();
                if (action.deviceType() == DeviceType.TT && action.cheShortName() != null) {
                    ttByContainer.computeIfAbsent(action.containerIndex(), k -> new LinkedHashMap<>())
                            .computeIfAbsent(action.actionType(), k -> new ArrayList<>())
                            .add(action);
                }
            }

            for (Map<ActionType, List<Action>> actions : ttByContainer.values()) {
                // QC standby: occupied when drive_to_standby activated AND drive_under_qc is pending
                checkQCStandby(occupancies, workQueueId, actions);

                // QC under: occupied when drive_under_qc active OR handover_from_qc active
                //   OR (drive_under_qc completed AND handover_from_qc pending)
                checkQCUnder(occupancies, workQueueId, actions);

                // RTG standby: occupied when drive_to_rtg_standby activated AND drive_to_rtg_under is pending
                checkRTGStandby(occupancies, workQueueId, actions);

                // RTG under: occupied when drive_to_rtg_under active OR handover_to_rtg active
                //   OR (drive_to_rtg_under completed AND handover_to_rtg pending)
                checkRTGUnder(occupancies, workQueueId, actions);
            }
        }

        return occupancies;
    }

    private void checkQCStandby(List<LocationOccupancy> occupancies, long workQueueId,
                                Map<ActionType, List<Action>> actions) {
        // QC standby occupied when: drive_to_standby (ACTIVE or COMPLETED) AND drive_under_qc PENDING
        Action standby = findWithStatus(actions, ActionType.TT_DRIVE_TO_QC_STANDBY, workQueueId, ActionStatus.ACTIVE, ActionStatus.COMPLETED);
        if (standby == null) return;
        if (!allPending(actions, ActionType.TT_DRIVE_UNDER_QC, workQueueId)) return;
        addOccupancy(occupancies, standby, workQueueId, DeviceType.QC, EquipmentPosition.STANDBY, ScheduleRunnerProcessor::resolveQCName);
    }

    private void checkQCUnder(List<LocationOccupancy> occupancies, long workQueueId,
                               Map<ActionType, List<Action>> actions) {
        // QC under occupied when: drive_under_qc ACTIVE OR handover_from_qc ACTIVE
        //   OR (drive_under_qc COMPLETED AND handover_from_qc PENDING)
        Action underActive = findWithStatus(actions, ActionType.TT_DRIVE_UNDER_QC, workQueueId, ActionStatus.ACTIVE);
        if (underActive != null) {
            addOccupancy(occupancies, underActive, workQueueId, DeviceType.QC, EquipmentPosition.UNDER, ScheduleRunnerProcessor::resolveQCName);
            return;
        }
        Action handoverActive = findWithStatus(actions, ActionType.TT_HANDOVER_FROM_QC, workQueueId, ActionStatus.ACTIVE);
        if (handoverActive != null) {
            addOccupancy(occupancies, handoverActive, workQueueId, DeviceType.QC, EquipmentPosition.UNDER, ScheduleRunnerProcessor::resolveQCName);
            return;
        }
        Action underCompleted = findWithStatus(actions, ActionType.TT_DRIVE_UNDER_QC, workQueueId, ActionStatus.COMPLETED);
        if (underCompleted != null && allPending(actions, ActionType.TT_HANDOVER_FROM_QC, workQueueId)) {
            addOccupancy(occupancies, underCompleted, workQueueId, DeviceType.QC, EquipmentPosition.UNDER, ScheduleRunnerProcessor::resolveQCName);
        }
    }

    private void checkRTGStandby(List<LocationOccupancy> occupancies, long workQueueId,
                                  Map<ActionType, List<Action>> actions) {
        Action standby = findWithStatus(actions, ActionType.TT_DRIVE_TO_RTG_STANDBY, workQueueId, ActionStatus.ACTIVE, ActionStatus.COMPLETED);
        if (standby == null) return;
        if (!allPending(actions, ActionType.TT_DRIVE_TO_RTG_UNDER, workQueueId)) return;
        addOccupancy(occupancies, standby, workQueueId, DeviceType.RTG, EquipmentPosition.STANDBY, ScheduleRunnerProcessor::resolveRTGName);
    }

    private void checkRTGUnder(List<LocationOccupancy> occupancies, long workQueueId,
                                Map<ActionType, List<Action>> actions) {
        Action underActive = findWithStatus(actions, ActionType.TT_DRIVE_TO_RTG_UNDER, workQueueId, ActionStatus.ACTIVE);
        if (underActive != null) {
            addOccupancy(occupancies, underActive, workQueueId, DeviceType.RTG, EquipmentPosition.UNDER, ScheduleRunnerProcessor::resolveRTGName);
            return;
        }
        Action handoverActive = findWithStatus(actions, ActionType.TT_HANDOVER_TO_RTG, workQueueId, ActionStatus.ACTIVE);
        if (handoverActive != null) {
            addOccupancy(occupancies, handoverActive, workQueueId, DeviceType.RTG, EquipmentPosition.UNDER, ScheduleRunnerProcessor::resolveRTGName);
            return;
        }
        Action underCompleted = findWithStatus(actions, ActionType.TT_DRIVE_TO_RTG_UNDER, workQueueId, ActionStatus.COMPLETED);
        if (underCompleted != null && allPending(actions, ActionType.TT_HANDOVER_TO_RTG, workQueueId)) {
            addOccupancy(occupancies, underCompleted, workQueueId, DeviceType.RTG, EquipmentPosition.UNDER, ScheduleRunnerProcessor::resolveRTGName);
        }
    }

    /** Find any action of the given type that has one of the given statuses. */
    private Action findWithStatus(Map<ActionType, List<Action>> actions, ActionType type,
                                   long workQueueId, ActionStatus... statuses) {
        for (Action a : actions.getOrDefault(type, List.of())) {
            ActionStatus s = getActionStatus(workQueueId, a.id());
            for (ActionStatus expected : statuses) {
                if (s == expected) return a;
            }
        }
        return null;
    }

    /** Check if ALL actions of the given type are PENDING. */
    private boolean allPending(Map<ActionType, List<Action>> actions, ActionType type, long workQueueId) {
        List<Action> list = actions.getOrDefault(type, List.of());
        if (list.isEmpty()) return true;
        for (Action a : list) {
            if (getActionStatus(workQueueId, a.id()) != ActionStatus.PENDING) return false;
        }
        return true;
    }

    private void addOccupancy(List<LocationOccupancy> occupancies, Action action, long workQueueId,
                               DeviceType equipmentType, EquipmentPosition position,
                               java.util.function.Function<Action, String> nameResolver) {
        String equipmentName = nameResolver.apply(action);
        if (equipmentName == null || equipmentName.isBlank()) return;

        Long wiId = null;
        String containerId = null;
        if (action.workInstructions() != null && !action.workInstructions().isEmpty()) {
            var wi = action.workInstructions().getFirst();
            wiId = wi.workInstructionId();
            containerId = wi.containerId();
        }
        occupancies.add(new LocationOccupancy(
                equipmentType, equipmentName, position,
                action.cheShortName(), workQueueId, action.description(),
                wiId, containerId));
    }

    /**
     * Returns the set of occupied position keys for use in {@link ActionConditionContext}.
     * Format: "QC:QCZ1:STANDBY" or "RTG:RTZ01:UNDER".
     */
    public Set<String> getOccupiedPositionKeys() {
        Set<String> keys = new HashSet<>();
        for (LocationOccupancy occ : getLocationOccupancy()) {
            keys.add(ActionConditionContext.positionKey(occ.equipmentType(), occ.equipmentName(), occ.position()));
        }
        return keys;
    }

    private static String resolveQCName(Action action) {
        if (action.workInstructions() != null && !action.workInstructions().isEmpty()) {
            String fetchChe = action.workInstructions().getFirst().fetchChe();
            if (fetchChe != null && !fetchChe.isBlank()) return fetchChe;
        }
        return null;
    }

    private static String resolveRTGName(Action action) {
        if (action.workInstructions() != null && !action.workInstructions().isEmpty()) {
            String putChe = action.workInstructions().getFirst().putChe();
            if (putChe != null && !putChe.isBlank()) return putChe;
        }
        return null;
    }

    private final Map<Long, ScheduleState> scheduleStates = new HashMap<>();
    private final Map<Long, Instant> workInstructionEstimatedMoveTime = new HashMap<>();
    private final List<ScheduleSubProcessor> subProcessors = new ArrayList<>();
    private final List<CompletionConditionEvaluator> completionEvaluators = new ArrayList<>();
    private final Map<UUID, Set<String>> satisfiedCompletionConditions = new HashMap<>();
    private Instant currentTime = Instant.EPOCH;
    private TTAllocationStrategy ttAllocationStrategy;

    /**
     * Registers a TT allocation strategy for assigning trucks to TT actions.
     *
     * @param strategy the allocation strategy
     */
    public void registerTTAllocationStrategy(TTAllocationStrategy strategy) {
        this.ttAllocationStrategy = strategy;
    }

    /**
     * Registers a sub-processor that will receive events along with schedule context.
     *
     * @param subProcessor the sub-processor to register
     */
    public void registerSubProcessor(ScheduleSubProcessor subProcessor) {
        this.subProcessors.add(subProcessor);
    }

    public void registerCompletionEvaluator(CompletionConditionEvaluator evaluator) {
        this.completionEvaluators.add(evaluator);
    }

    /**
     * Creates a ScheduleContext for sub-processors to interact with schedule state.
     */
    private ScheduleContext createContext() {
        return new ScheduleContext() {
            @Override
            public Set<Long> getScheduleWorkQueueIds() {
                return Set.copyOf(scheduleStates.keySet());
            }

            @Override
            public Map<UUID, Action> getActions(long workQueueId) {
                ScheduleState state = scheduleStates.get(workQueueId);
                if (state == null) return Map.of();
                Map<UUID, Action> result = new HashMap<>();
                for (Map.Entry<UUID, ActionInfo> entry : state.actionLookup.entrySet()) {
                    result.put(entry.getKey(), entry.getValue().action());
                }
                return result;
            }

            @Override
            public String getTaktName(long workQueueId, UUID actionId) {
                ScheduleState state = scheduleStates.get(workQueueId);
                if (state == null) return null;
                ActionInfo info = state.actionLookup.get(actionId);
                return info != null ? info.taktName() : null;
            }

            @Override
            public ActionStatus getActionStatus(long workQueueId, UUID actionId) {
                return ScheduleRunnerProcessor.this.getActionStatus(workQueueId, actionId);
            }

            @Override
            public List<SideEffect> completeActionWithReason(long workQueueId, UUID actionId, CompletionReason reason) {
                ScheduleState state = scheduleStates.get(workQueueId);
                if (state == null) return List.of();
                ActionInfo info = state.actionLookup.get(actionId);
                if (info == null) return List.of();
                if (info.action().status() == ActionStatus.COMPLETED) return List.of();

                // Update the action with completion reason and status
                Action updatedAction = info.action().withCompletionReason(reason).withStatus(ActionStatus.COMPLETED);
                state.actionLookup.put(actionId, new ActionInfo(info.taktName(), updatedAction));

                List<SideEffect> effects = new ArrayList<>();
                effects.add(new ActionCompleted(
                        actionId, workQueueId, info.taktName(),
                        updatedAction.description(), currentTime, reason));

                // Emit WorkInstructionCanceled for each WI so WorkQueueProcessor
                // can exclude canceled containers from reschedule decisions
                for (var wi : updatedAction.workInstructions()) {
                    effects.add(new WorkInstructionCanceled(workQueueId, wi.workInstructionId()));
                }

                return effects;
            }

            @Override
            public List<SideEffect> resetTTAction(long workQueueId, UUID actionId) {
                ScheduleState state = scheduleStates.get(workQueueId);
                if (state == null) return List.of();
                ActionInfo info = state.actionLookup.get(actionId);
                if (info == null) return List.of();
                Action action = info.action();
                String cheShortName = action.cheShortName();
                if (cheShortName == null) return List.of();

                // Clear truck assignment and reset to pending
                Action resetAction = action.withTruckAssignment(null, null).withStatus(ActionStatus.PENDING);
                state.actionLookup.put(actionId, new ActionInfo(info.taktName(), resetAction));

                return List.of(new TruckUnassigned(actionId, workQueueId, cheShortName));
            }

            @Override
            public List<SideEffect> cascadeTaktCompletion(long workQueueId) {
                // No-op: handled by reactivateAllSchedules() at end of process()
                return List.of();
            }

            @Override
            public List<SideEffect> tryActivateEligibleActions(long workQueueId) {
                // No-op: handled by reactivateAllSchedules() at end of process()
                return List.of();
            }

            @Override
            public Instant getCurrentTime() {
                return currentTime;
            }
        };
    }

    /**
     * Collects all truck cheShortNames currently assigned to actions across all schedules.
     */
    private Set<String> collectAssignedTrucks() {
        Set<String> assigned = new HashSet<>();
        for (ScheduleState state : scheduleStates.values()) {
            // Only scan non-COMPLETED takts — completed actions have released their trucks
            for (ActionInfo info : state.getActiveAndWaitingTaktActions()) {
                Action action = info.action();
                if (action.cheShortName() != null
                        && action.status() != ActionStatus.COMPLETED
                        && !action.skipWhenGatesSatisfied()) {
                    assigned.add(action.cheShortName());
                }
            }
        }
        return assigned;
    }

    @Override
    public List<SideEffect> process(Event event) {
        long t0 = System.nanoTime();
        List<SideEffect> sideEffects = new ArrayList<>();

        if (event instanceof WorkQueueMessage message) {
            sideEffects.addAll(handleWorkQueueMessage(message));
        } else if (event instanceof WorkInstructionEvent instruction) {
            sideEffects.addAll(handleWorkInstructionEvent(instruction));
        } else if (event instanceof ScheduleCreated scheduleCreated) {
            sideEffects.addAll(handleScheduleCreated(scheduleCreated));
        } else if (event instanceof TimeEvent timeEvent) {
            sideEffects.addAll(handleTimeEvent(timeEvent));
        } else if (event instanceof ActionCompletedEvent completed) {
            sideEffects.addAll(handleActionCompleted(completed));
        } else if (event instanceof OverrideConditionEvent override) {
            sideEffects.addAll(handleOverrideCondition(override));
        } else if (event instanceof OverrideActionConditionEvent override) {
            sideEffects.addAll(handleOverrideActionCondition(override));
        } else if (event instanceof NukeWorkQueueEvent nuke) {
            scheduleStates.remove(nuke.workQueueId());
        }
        long t1 = System.nanoTime();

        // Delegate to sub-processors
        if (!subProcessors.isEmpty()) {
            ScheduleContext context = createContext();
            for (ScheduleSubProcessor sub : subProcessors) {
                sideEffects.addAll(sub.process(event, context));
            }
        }
        long t2 = System.nanoTime();

        // Evaluate completion conditions — only when the event or prior side effects
        // could actually satisfy a condition. Skips expensive all-actions iteration
        // for events like TimeEvent, WorkInstructionEvent, etc.
        if (canSatisfyCompletionConditions(event, sideEffects)) {
            sideEffects.addAll(evaluateCompletionConditions(event));
        }
        long t3 = System.nanoTime();

        // Final activation pass: always run after all state mutations.
        // This consolidates all activation logic into one place and ensures
        // takts are always processed in sequence order for TT allocation priority.
        sideEffects.addAll(reactivateAllSchedules());
        long t4 = System.nanoTime();

        long totalMs = (t4 - t0) / 1_000_000;
        if (totalMs > 5 && logger.isLoggable(java.util.logging.Level.FINE)) {
            logger.fine("PERF " + event.getClass().getSimpleName()
                    + " total=" + totalMs + "ms"
                    + " handler=" + (t1 - t0) / 1_000_000 + "ms"
                    + " subProc=" + (t2 - t1) / 1_000_000 + "ms"
                    + " evalCC=" + (t3 - t2) / 1_000_000 + "ms"
                    + " reactivate=" + (t4 - t3) / 1_000_000 + "ms"
                    + " schedules=" + scheduleStates.size()
                    + " effects=" + sideEffects.size());
        }

        return sideEffects;
    }

    private List<SideEffect> handleScheduleCreated(ScheduleCreated scheduleCreated) {
        long workQueueId = scheduleCreated.workQueueId();
        List<Takt> takts = scheduleCreated.takts();
        Instant estimatedMoveTime = scheduleCreated.estimatedMoveTime();

        ScheduleState oldState = scheduleStates.get(workQueueId);

        ScheduleState newState = new ScheduleState(estimatedMoveTime, takts);
        buildConditions(newState);
        scheduleStates.put(workQueueId, newState);

        List<SideEffect> sideEffects = new ArrayList<>();

        if (oldState != null) {
            sideEffects.addAll(transferState(workQueueId, oldState, newState));
        }

        // Takt activation and action activation are handled by reactivateAllSchedules()
        return sideEffects;
    }

    /**
     * Transfers completed and active state from the old schedule to the new one.
     * Matches takts by name (sequence-based) and actions by position within takt.
     * <ul>
     *   <li>Completed takts: all actions marked completed in new schedule</li>
     *   <li>Active takts: completed actions transferred by position, eligible actions re-activated</li>
     *   <li>Waiting takts: left as-is for normal activation</li>
     * </ul>
     */
    private List<SideEffect> transferState(long workQueueId, ScheduleState oldState, ScheduleState newState) {
        List<SideEffect> sideEffects = new ArrayList<>();

        // Build set of completed action keys from old state for matching by (actionType, containerIndex)
        Map<String, Set<String>> oldTaktCompletedKeys = new HashMap<>();
        for (Takt takt : oldState.takts) {
            Set<String> completedKeys = new HashSet<>();
            for (Action action : takt.actions()) {
                ActionInfo oldInfo = oldState.actionLookup.get(action.id());
                if (oldInfo != null && oldInfo.action().status() == ActionStatus.COMPLETED) {
                    completedKeys.add(action.actionType() + ":" + action.containerIndex());
                }
            }
            oldTaktCompletedKeys.put(takt.name(), completedKeys);
        }

        // Transfer event gate states from old actions to new actions by (actionType, containerIndex)
        transferEventGateState(oldState, newState);

        // Transfer truck assignments from old schedule to new schedule by containerIndex
        transferTruckAssignments(oldState, newState);

        for (Takt newTakt : newState.takts) {
            String taktName = newTakt.name();
            TaktState oldTaktState = oldState.taktStates.getOrDefault(taktName, TaktState.WAITING);

            if (oldTaktState == TaktState.COMPLETED) {
                // Completed takts: mark takt and all actions as completed
                newState.taktStates.put(taktName, TaktState.COMPLETED);
                for (Action action : newTakt.actions()) {
                    newState.setActionStatus(action.id(), ActionStatus.COMPLETED);
                }
            } else if (oldTaktState == TaktState.ACTIVE) {
                // Active takts: transfer completed action states by (actionType, containerIndex)
                newState.taktStates.put(taktName, TaktState.ACTIVE);
                newState.actualStartTimes.put(taktName, this.currentTime);
                sideEffects.add(new TaktActivated(workQueueId, taktName, this.currentTime));

                Set<String> completedKeys = oldTaktCompletedKeys.getOrDefault(taktName, Set.of());
                for (Action action : newTakt.actions()) {
                    String key = action.actionType() + ":" + action.containerIndex();
                    if (completedKeys.contains(key)) {
                        newState.setActionStatus(action.id(), ActionStatus.COMPLETED);
                    }
                }

                // Action activation is handled by reactivateAllSchedules()
            }
            // WAITING takts: leave as-is, tryActivateTakts will handle them
        }

        return sideEffects;
    }

    /**
     * Transfers armed and satisfied event gate states from old schedule to new schedule.
     * Matches actions by (actionType, containerIndex) since UUIDs change across schedules.
     * Also re-arms gates whose source action was already activated or completed in the old state.
     */
    private static String actionKey(Action a) {
        return a.actionType() + ":" + a.containerIndex() + ":" + a.deviceIndex();
    }

    /**
     * Transfers truck assignments from the old schedule to the new schedule.
     * For each containerIndex that had a truck assigned in the old state,
     * assigns the same truck to matching actions in the new state.
     */
    private void transferTruckAssignments(ScheduleState oldState, ScheduleState newState) {
        // Build containerIndex → truck assignment from old TT actions
        Map<Integer, Map.Entry<Long, String>> oldAssignments = new HashMap<>();
        for (ActionInfo info : oldState.actionLookup.values()) {
            Action a = info.action();
            if (a.deviceType() == DeviceType.TT && a.cheShortName() != null) {
                oldAssignments.putIfAbsent(a.containerIndex(), Map.entry(
                        a.cheId() != null ? a.cheId() : 0L, a.cheShortName()));
            }
        }

        // Apply to new state actions
        for (Map.Entry<Integer, Map.Entry<Long, String>> assignment : oldAssignments.entrySet()) {
            int containerIdx = assignment.getKey();
            Long cheId = assignment.getValue().getKey();
            String cheShortName = assignment.getValue().getValue();

            for (Map.Entry<UUID, ActionInfo> e : newState.actionLookup.entrySet()) {
                Action a = e.getValue().action();
                if (a.containerIndex() != containerIdx) continue;
                if (a.deviceType() == DeviceType.TT && a.cheShortName() == null) {
                    Action assigned = a.withTruckAssignment(cheId, cheShortName);
                    newState.actionLookup.put(e.getKey(), new ActionInfo(e.getValue().taktName(), assigned));
                } else if (a.deviceType() != DeviceType.TT && a.targetChe() == null) {
                    Action withTarget = a.withTargetChe(cheShortName);
                    newState.actionLookup.put(e.getKey(), new ActionInfo(e.getValue().taktName(), withTarget));
                }
            }
        }
    }

    private void transferEventGateState(ScheduleState oldState, ScheduleState newState) {
        // Build old action key → UUID mapping
        Map<String, UUID> oldActionKeyToId = new HashMap<>();
        for (ActionInfo info : oldState.actionLookup.values()) {
            oldActionKeyToId.put(actionKey(info.action()), info.action().id());
        }

        // Build new action key → UUID mapping
        Map<String, UUID> newActionKeyToId = new HashMap<>();
        for (ActionInfo info : newState.actionLookup.values()) {
            newActionKeyToId.put(actionKey(info.action()), info.action().id());
        }

        // Transfer armed gates
        for (Map.Entry<UUID, Set<String>> entry : oldState.armedEventGates.entrySet()) {
            UUID oldActionId = entry.getKey();
            ActionInfo oldInfo = oldState.actionLookup.get(oldActionId);
            if (oldInfo == null) continue;
            UUID newActionId = newActionKeyToId.get(actionKey(oldInfo.action()));
            if (newActionId != null) {
                newState.armedEventGates.put(newActionId, new HashSet<>(entry.getValue()));
            }
        }

        // Transfer satisfied gates
        for (Map.Entry<UUID, Set<String>> entry : oldState.satisfiedEventGates.entrySet()) {
            UUID oldActionId = entry.getKey();
            ActionInfo oldInfo = oldState.actionLookup.get(oldActionId);
            if (oldInfo == null) continue;
            UUID newActionId = newActionKeyToId.get(actionKey(oldInfo.action()));
            if (newActionId != null) {
                newState.satisfiedEventGates.put(newActionId, new HashSet<>(entry.getValue()));
            }
        }

        // Re-arm gates whose source action was already activated or completed in the old state
        // (covers cases where the gate wasn't armed yet but the source action already ran)
        for (ActionInfo newInfo : newState.actionLookup.values()) {
            Action newAction = newInfo.action();
            if (newAction.eventGates().isEmpty()) continue;
            UUID newActionId = newAction.id();
            if (newState.armedEventGates.containsKey(newActionId)) continue; // already transferred

            // Check if the source action was activated/completed in old state
            UUID sourceId = newState.gateArmSourceActions.get(newActionId);
            if (sourceId == null) continue;
            ActionInfo sourceInfo = newState.actionLookup.get(sourceId);
            if (sourceInfo == null) continue;
            UUID oldSourceId = oldActionKeyToId.get(actionKey(sourceInfo.action()));
            ActionInfo oldSourceInfo = oldSourceId != null ? oldState.actionLookup.get(oldSourceId) : null;
            if (oldSourceInfo != null &&
                    (oldSourceInfo.action().status() == ActionStatus.ACTIVE || oldSourceInfo.action().status() == ActionStatus.COMPLETED)) {
                for (EventGateCondition gate : newAction.eventGates()) {
                    newState.armedEventGates
                            .computeIfAbsent(newActionId, k -> new HashSet<>())
                            .add(gate.id());
                }
            }
        }

        // Auto-satisfy gates whose WIs already carry the required event type.
        // This covers the case where a WI event was processed before the reschedule:
        // the event updated the WI's eventType, but the old schedule's gates didn't match
        // (different WI assignment). After rescheduling, the new actions carry the updated WIs.
        // We arm AND satisfy in one pass — if the WI already has the event, the gate is done.
        for (ActionInfo newInfo : newState.actionLookup.values()) {
            Action newAction = newInfo.action();
            if (newAction.eventGates().isEmpty()) continue;
            UUID newActionId = newAction.id();

            for (EventGateCondition gate : newAction.eventGates()) {
                Set<String> satisfied = newState.satisfiedEventGates.getOrDefault(newActionId, Set.of());
                if (satisfied.contains(gate.id())) continue;

                boolean eventAlreadyReceived;
                var wis = newAction.workInstructions();
                if (gate.containerSuffix() > 0) {
                    int idx = gate.containerSuffix() - 1;
                    if (idx < wis.size()) {
                        // Check the specific WI by suffix index
                        eventAlreadyReceived = gate.requiredEventType().equals(wis.get(idx).eventType());
                    } else {
                        // Suffix index exceeds WI count (e.g., drive2 with only 1 WI but twin gates).
                        // Satisfy if ALL WIs on this action already have the required event type.
                        eventAlreadyReceived = !wis.isEmpty() && wis.stream()
                                .allMatch(wi -> gate.requiredEventType().equals(wi.eventType()));
                    }
                } else {
                    eventAlreadyReceived = wis.stream()
                            .anyMatch(wi -> gate.requiredEventType().equals(wi.eventType()));
                }

                if (eventAlreadyReceived) {
                    newState.armedEventGates
                            .computeIfAbsent(newActionId, k -> new HashSet<>())
                            .add(gate.id());
                    newState.satisfiedEventGates
                            .computeIfAbsent(newActionId, k -> new HashSet<>())
                            .add(gate.id());
                }
            }
        }
    }

    /**
     * Builds TaktConditions for each takt based on its properties and action dependencies.
     */
    private void buildConditions(ScheduleState state) {
        for (Takt takt : state.takts) {
            List<TaktCondition> conditions = new ArrayList<>();

            // Condition 1: Time — estimated start time must be reached
            if (takt.estimatedStartTime() != null) {
                conditions.add(new TimeCondition(takt.estimatedStartTime()));
            }

            // Condition 2: Dependencies — first actions' external dependencies must be completed
            DependencyCondition depCondition = buildDependencyCondition(takt, state);
            if (depCondition != null) {
                conditions.add(depCondition);
            }

            state.taktConditions.put(takt.name(), conditions);
        }
    }

    /**
     * Builds a DependencyCondition for a takt by finding first actions (those with no
     * intra-takt dependencies) and collecting their external dependencies from earlier takts.
     * Dependencies pointing to actions in later takts do not gate takt activation.
     */
    private DependencyCondition buildDependencyCondition(Takt takt, ScheduleState state) {
        if (takt.actions().isEmpty()) {
            return null;
        }

        Set<UUID> taktActionIds = new HashSet<>();
        for (Action action : takt.actions()) {
            taktActionIds.add(action.id());
        }

        Set<UUID> laterTaktActionIds = collectLaterTaktActionIds(takt, state);

        Map<String, Set<UUID>> externalDeps = new LinkedHashMap<>();
        Map<UUID, String> depDescriptions = new HashMap<>();

        for (Action action : takt.actions()) {
            boolean hasIntraTaktDep = action.dependsOn().stream().anyMatch(taktActionIds::contains);
            if (hasIntraTaktDep) {
                continue;
            }
            // This is a first action — collect its external dependencies from earlier takts
            Set<UUID> externalActionDeps = new HashSet<>();
            for (UUID depId : action.dependsOn()) {
                if (taktActionIds.contains(depId) || laterTaktActionIds.contains(depId)) {
                    continue; // skip intra-takt and later-takt dependencies
                }
                externalActionDeps.add(depId);
                ActionInfo depInfo = state.actionLookup.get(depId);
                if (depInfo != null) {
                    depDescriptions.put(depId, depInfo.action().description());
                }
            }
            if (!externalActionDeps.isEmpty()) {
                externalDeps.put(action.description(), externalActionDeps);
            }
        }

        if (externalDeps.isEmpty()) {
            return null;
        }

        return new DependencyCondition(externalDeps, depDescriptions);
    }

    private List<SideEffect> handleWorkInstructionEvent(WorkInstructionEvent event) {
        if (event.estimatedMoveTime() != null) {
            workInstructionEstimatedMoveTime.put(event.workInstructionId(), event.estimatedMoveTime());
        }

        // Check if this WI event satisfies any armed event gates
        if (event.eventType() != null && !event.eventType().isEmpty()) {
            List<SideEffect> sideEffects = new ArrayList<>();
            for (ScheduleState state : scheduleStates.values()) {
                List<UUID> gatedActions = state.eventTypeToGatedActions.getOrDefault(event.eventType(), List.of());
                for (UUID gatedActionId : gatedActions) {
                    Set<String> armed = state.armedEventGates.getOrDefault(gatedActionId, Set.of());
                    ActionInfo gatedInfo = state.actionLookup.get(gatedActionId);
                    if (gatedInfo == null) continue;

                    // Match against the gated action's own WIs — each action carries the WIs it's responsible for.
                    // In different-bay templates (two RTGs), each RTG_DRIVE carries only its own WI.
                    // Gates with containerSuffix > 0 only match the specific WI at that index.
                    boolean wiMatches = gatedInfo.action().workInstructions().stream()
                            .anyMatch(wi -> wi.workInstructionId() == event.workInstructionId());
                    if (!wiMatches) continue;

                    for (EventGateCondition gate : gatedInfo.action().eventGates()) {
                        if (!gate.requiredEventType().equals(event.eventType()) || !armed.contains(gate.id())) {
                            continue;
                        }
                        // If gate is scoped to a specific container, only that WI can satisfy it
                        if (gate.containerSuffix() > 0) {
                            var wis = gatedInfo.action().workInstructions();
                            int idx = gate.containerSuffix() - 1;
                            if (idx < wis.size()) {
                                // Check the specific WI by suffix index
                                if (wis.get(idx).workInstructionId() != event.workInstructionId()) {
                                    continue;
                                }
                            }
                            // If idx >= wis.size() (e.g., drive2 with 1 WI but twin gates),
                            // the wiMatches check above already confirmed this event's WI
                            // belongs to this action — satisfy the gate.
                        }
                        state.satisfiedEventGates
                                .computeIfAbsent(gatedActionId, k -> new HashSet<>())
                                .add(gate.id());
                    }
                }
                // Auto-completion and action activation handled by reactivateAllSchedules()
            }
            return sideEffects;
        }

        return List.of();
    }

    private List<SideEffect> handleWorkQueueMessage(WorkQueueMessage message) {
        long workQueueId = message.workQueueId();

        return switch (message.status()) {
            case ACTIVE -> handleScheduleActivation(workQueueId);
            case INACTIVE -> handleScheduleDeactivation(workQueueId);
            case null -> List.of();
        };
    }

    private List<SideEffect> handleScheduleActivation(long workQueueId) {
        if (scheduleStates.containsKey(workQueueId)) {
            return List.of();
        }
        scheduleStates.put(workQueueId, new ScheduleState(null, List.of()));
        return List.of();
    }

    private List<SideEffect> handleScheduleDeactivation(long workQueueId) {
        scheduleStates.remove(workQueueId);
        return List.of();
    }

    private List<SideEffect> handleTimeEvent(TimeEvent timeEvent) {
        this.currentTime = timeEvent.timestamp();
        // Takt activation and action activation handled by reactivateAllSchedules()
        return List.of();
    }

    private List<SideEffect> handleOverrideCondition(OverrideConditionEvent event) {
        ScheduleState state = scheduleStates.get(event.workQueueId());
        if (state == null) {
            return List.of();
        }

        Set<String> overrides = state.overriddenConditions.get(event.taktName());
        if (overrides == null) {
            return List.of();
        }

        overrides.add(event.conditionId());

        // Takt activation handled by reactivateAllSchedules()
        return List.of();
    }

    private List<SideEffect> handleOverrideActionCondition(OverrideActionConditionEvent event) {
        ScheduleState state = scheduleStates.get(event.workQueueId());
        if (state == null) {
            return List.of();
        }

        ActionInfo actionInfo = state.actionLookup.get(event.actionId());
        if (actionInfo == null) {
            return List.of();
        }

        state.overriddenActionConditions
                .computeIfAbsent(event.actionId(), k -> new HashSet<>())
                .add(event.conditionId());

        // Takt activation, action activation, and force-activation handled by reactivateAllSchedules()
        return List.of();
    }

    /**
     * Checks if all conditions for an action are satisfied or overridden,
     * and activates the action even if its takt is still WAITING.
     */
    private List<SideEffect> tryForceActivateAction(long workQueueId, ScheduleState state,
                                                      UUID actionId, ActionInfo actionInfo) {
        if (actionInfo.action().status() != ActionStatus.PENDING) {
            return List.of();
        }

        Set<String> overrides = state.overriddenActionConditions.getOrDefault(actionId, Set.of());
        Action action = actionInfo.action();
        String taktName = actionInfo.taktName();

        // Determine what conditions this action has and check each
        Set<UUID> taktActionIds = new HashSet<>();
        for (Action a : state.takts.stream().filter(t -> t.name().equals(taktName)).findFirst().get().actions()) {
            taktActionIds.add(a.id());
        }

        boolean hasIntraTaktDep = action.dependsOn() != null &&
                action.dependsOn().stream().anyMatch(taktActionIds::contains);

        if (!hasIntraTaktDep) {
            // First action: has takt-activation condition
            boolean taktActive = state.taktStates.get(taktName) == TaktState.ACTIVE;
            if (!taktActive && !overrides.contains("takt-activation")) {
                return List.of();
            }
        } else {
            // Non-first action: has action-dependencies condition
            boolean depsComplete = action.dependsOn() == null || action.dependsOn().isEmpty()
                    || state.areDependenciesCompleted(action.dependsOn());
            if (!depsComplete && !overrides.contains("action-dependencies")) {
                return List.of();
            }
        }

        // Check event gates (skip check for skipWhenGatesSatisfied actions)
        if (!action.skipWhenGatesSatisfied()
                && !state.areEventGatesSatisfied(actionId, action, overrides)) {
            return List.of();
        }

        // Auto-complete: if skipWhenGatesSatisfied and all gates are already satisfied, skip
        if (action.skipWhenGatesSatisfied()
                && state.areEventGatesSatisfied(actionId, action, overrides)) {
            state.setActionStatus(actionId, ActionStatus.COMPLETED);
            return List.of(new ActionCompleted(
                    actionId, workQueueId, taktName,
                    action.description(), this.currentTime
            ));
        }

        // All conditions met — activate the action
        state.setActionStatus(actionId, ActionStatus.ACTIVE);
        armEventGatesForAction(state, actionId);
        String cheForMessage = action.deviceType() == DeviceType.TT ? action.cheShortName() : action.targetChe();
        return List.of(new ActionActivated(
                actionId, workQueueId, taktName,
                action.actionType(), action.description(), this.currentTime,
                action.deviceType(), action.workInstructions(),
                cheForMessage
        ));
    }

    /**
     * Consolidated activation pass that runs after every event.
     * <p>
     * Runs a fixed-point loop over all schedules, processing takts sorted by sequence.
     * This ensures consistent TT allocation priority (earlier takts first) and handles
     * all cascading: takt activation, action activation, auto-completion, takt completion,
     * force-activation of overridden actions, and cross-schedule truck reallocation.
     */
    /**
     * Checks whether the event or prior side effects could possibly satisfy a completion condition.
     * Only CheTargetPositionEvent, AssetEvent, JobOperationEvent can directly satisfy conditions.
     * ActionCompletedEvaluator needs newly completed actions, which are signaled by ActionCompleted
     * side effects from earlier in this process() call.
     */
    private boolean canSatisfyCompletionConditions(Event event, List<SideEffect> priorEffects) {
        if (event instanceof CheTargetPositionEvent
                || event instanceof AssetEvent
                || event instanceof JobOperationEvent) {
            return true;
        }
        for (SideEffect effect : priorEffects) {
            if (effect instanceof ActionCompleted) {
                return true;
            }
        }
        return false;
    }

    /**
     * Evaluates completion condition evaluators against all actions.
     * When all completion conditions on an action are satisfied, the action is auto-completed.
     * Uses a fixed-point loop to cascade completions within a single processing cycle
     * (e.g., TT_DRIVE_TO_RTG_UNDER completing triggers RTG_WAIT_FOR_TRUCK completion).
     */
    private List<SideEffect> evaluateCompletionConditions(Event event) {
        if (completionEvaluators.isEmpty()) return List.of();

        List<SideEffect> allSideEffects = new ArrayList<>();
        boolean progress = true;
        boolean firstIteration = true;

        while (progress) {
            progress = false;

            // Build action maps: ACTIVE actions for event-based evaluators,
            // ACTIVE+COMPLETED for ActionCompletedEvaluator (cascading only).
            // ACTIVE is typically ~10-30 actions vs ~3000+ COMPLETED.
            Map<UUID, Action> activeActions = new HashMap<>();
            Map<UUID, Action> allNonPending = null; // lazy — only built if needed
            Map<UUID, Long> actionToWorkQueue = new HashMap<>();
            for (Map.Entry<Long, ScheduleState> entry : scheduleStates.entrySet()) {
                for (Map.Entry<UUID, ActionInfo> actionEntry : entry.getValue().actionLookup.entrySet()) {
                    Action action = actionEntry.getValue().action();
                    if (action.status() == ActionStatus.ACTIVE) {
                        activeActions.put(actionEntry.getKey(), action);
                        actionToWorkQueue.put(actionEntry.getKey(), entry.getKey());
                    } else if (action.status() == ActionStatus.COMPLETED) {
                        actionToWorkQueue.put(actionEntry.getKey(), entry.getKey());
                    }
                }
            }

            if (activeActions.isEmpty()) break;

            // Evaluate event-based evaluators with ACTIVE-only map (first iteration)
            // or all evaluators including ActionCompletedEvaluator (cascading iterations)
            Map<UUID, Set<String>> newlySatisfiedByAction = new HashMap<>();
            for (CompletionConditionEvaluator evaluator : completionEvaluators) {
                // ActionCompletedEvaluator only matters on cascading (non-first) iterations
                // when actions were just completed by other evaluators
                if (evaluator instanceof ActionCompletedEvaluator) {
                    if (firstIteration) continue;
                    // Build full non-pending map lazily for ActionCompletedEvaluator
                    if (allNonPending == null) {
                        allNonPending = new HashMap<>(activeActions);
                        for (Map.Entry<Long, ScheduleState> entry : scheduleStates.entrySet()) {
                            for (Map.Entry<UUID, ActionInfo> actionEntry : entry.getValue().actionLookup.entrySet()) {
                                if (actionEntry.getValue().action().status() == ActionStatus.COMPLETED) {
                                    allNonPending.put(actionEntry.getKey(), actionEntry.getValue().action());
                                }
                            }
                        }
                    }
                    Map<UUID, List<String>> evaluated = evaluator.evaluateSatisfied(event, allNonPending);
                    for (Map.Entry<UUID, List<String>> evalEntry : evaluated.entrySet()) {
                        newlySatisfiedByAction.computeIfAbsent(evalEntry.getKey(), k -> new HashSet<>())
                                .addAll(evalEntry.getValue());
                    }
                } else {
                    Map<UUID, List<String>> evaluated = evaluator.evaluateSatisfied(event, activeActions);
                    for (Map.Entry<UUID, List<String>> evalEntry : evaluated.entrySet()) {
                        newlySatisfiedByAction.computeIfAbsent(evalEntry.getKey(), k -> new HashSet<>())
                                .addAll(evalEntry.getValue());
                    }
                }
            }
            firstIteration = false;

            if (newlySatisfiedByAction.isEmpty()) break;

            // Track satisfied conditions and check for fully satisfied actions
            for (Map.Entry<UUID, Set<String>> entry : newlySatisfiedByAction.entrySet()) {
                UUID actionId = entry.getKey();
                Set<String> newCondIds = entry.getValue();
                Action action = activeActions.get(actionId);
                if (action == null || action.status() != ActionStatus.ACTIVE) continue;
                if (action.completionConditions() == null || action.completionConditions().isEmpty()) continue;

                Set<String> satisfied = satisfiedCompletionConditions.computeIfAbsent(actionId, k -> new HashSet<>());
                boolean changed = false;
                for (String condId : newCondIds) {
                    changed |= satisfied.add(condId);
                }

                if (changed && allConditionsSatisfied(action, satisfied)) {
                    long wqId = actionToWorkQueue.get(actionId);
                    ScheduleState state = scheduleStates.get(wqId);
                    if (state != null) {
                        String taktName = state.actionLookup.get(actionId).taktName();
                        state.setActionStatus(actionId, ActionStatus.COMPLETED);
                        satisfiedCompletionConditions.remove(actionId);
                        allSideEffects.add(new ActionCompleted(actionId, wqId, taktName,
                                action.description(), this.currentTime));
                        progress = true;
                    }
                }
            }
        }

        return allSideEffects;
    }

    private boolean allConditionsSatisfied(Action action, Set<String> satisfied) {
        for (CompletionCondition cond : action.completionConditions()) {
            if (!satisfied.contains(cond.id())) return false;
        }
        return true;
    }

    private List<SideEffect> reactivateAllSchedules() {
        List<SideEffect> allEffects = new ArrayList<>();

        boolean progress = true;
        int iterations = 0;
        long occupiedNs = 0, trucksNs = 0, taktsNs = 0, actionsNs = 0, gatedNs = 0, forceNs = 0, completeNs = 0;

        while (progress) {
            progress = false;
            iterations++;

            // Compute occupied positions and assigned trucks once per iteration.
            // These are invalidated when progress=true (actions changed status).
            long ot0 = System.nanoTime();
            Set<String> occupiedPositions = getOccupiedPositionKeys();
            long ot1 = System.nanoTime();
            Set<String> assignedTrucks = collectAssignedTrucks();
            long ot2 = System.nanoTime();
            occupiedNs += ot1 - ot0;
            trucksNs += ot2 - ot1;

            for (Map.Entry<Long, ScheduleState> entry : scheduleStates.entrySet()) {
                long wqId = entry.getKey();
                ScheduleState state = entry.getValue();

                // 1. Try activating WAITING takts (condition checks)
                long st0 = System.nanoTime();
                List<SideEffect> taktEffects = tryActivateTakts(wqId, state);
                taktsNs += System.nanoTime() - st0;
                if (!taktEffects.isEmpty()) {
                    allEffects.addAll(taktEffects);
                    progress = true;
                }

                // 2. Activate eligible actions in ACTIVE takts, sorted by sequence
                long st1 = System.nanoTime();
                List<Takt> sortedTakts = state.takts.stream()
                        .sorted(Comparator.comparingInt(Takt::sequence))
                        .toList();
                for (Takt takt : sortedTakts) {
                    if (state.taktStates.get(takt.name()) == TaktState.ACTIVE) {
                        List<SideEffect> actionEffects = activateEligibleActions(
                                wqId, state, takt.name(), occupiedPositions, assignedTrucks);
                        if (!actionEffects.isEmpty()) {
                            allEffects.addAll(actionEffects);
                            progress = true;
                        }
                    }
                }
                actionsNs += System.nanoTime() - st1;

                // 3. Auto-complete gated actions (skipWhenGatesSatisfied)
                long st2 = System.nanoTime();
                List<SideEffect> autoEffects = autoCompleteGatedActions(wqId, state);
                gatedNs += System.nanoTime() - st2;
                if (!autoEffects.isEmpty()) {
                    allEffects.addAll(autoEffects);
                    progress = true;
                }

                // 4. Force-activate actions in WAITING takts with all conditions overridden
                //    Skip entirely when no overrides exist (the common case)
                long st3 = System.nanoTime();
                if (!state.overriddenActionConditions.isEmpty()) {
                    for (ActionInfo info : state.getActiveAndWaitingTaktActions()) {
                        if (state.taktStates.get(info.taktName()) == TaktState.WAITING) {
                            UUID actionId = info.action().id();
                            if (info.action().status() == ActionStatus.PENDING) {
                                List<SideEffect> forceEffects = tryForceActivateAction(wqId, state, actionId, info);
                                if (!forceEffects.isEmpty()) {
                                    allEffects.addAll(forceEffects);
                                    progress = true;
                                }
                            }
                        }
                    }
                }
                forceNs += System.nanoTime() - st3;

                // 5. Try completing fully-completed takts
                long st4 = System.nanoTime();
                List<SideEffect> completionEffects = tryCompletePendingTakts(wqId, state);
                completeNs += System.nanoTime() - st4;
                if (!completionEffects.isEmpty()) {
                    allEffects.addAll(completionEffects);
                    progress = true;
                }
            }
        }

        long totalNs = occupiedNs + trucksNs + taktsNs + actionsNs + gatedNs + forceNs + completeNs;
        if (totalNs / 1_000_000 > 2 && logger.isLoggable(java.util.logging.Level.FINE)) {
            logger.fine("PERF reactivate iters=" + iterations
                    + " occupied=" + occupiedNs / 1_000_000 + "ms"
                    + " trucks=" + trucksNs / 1_000_000 + "ms"
                    + " takts=" + taktsNs / 1_000_000 + "ms"
                    + " actions=" + actionsNs / 1_000_000 + "ms"
                    + " gated=" + gatedNs / 1_000_000 + "ms"
                    + " force=" + forceNs / 1_000_000 + "ms"
                    + " complete=" + completeNs / 1_000_000 + "ms"
                    + " effects=" + allEffects.size());
        }

        return allEffects;
    }

    /**
     * Tries to activate takts whose conditions are all satisfied (or overridden).
     * Only handles takt state transitions (WAITING → ACTIVE). Action activation
     * is handled separately by {@link #reactivateAllSchedules()}.
     */
    private List<SideEffect> tryActivateTakts(long workQueueId, ScheduleState state) {
        List<SideEffect> sideEffects = new ArrayList<>();

        // Build completedActionIds on-the-fly for ConditionContext
        Set<UUID> completedActionIds = new HashSet<>();
        for (ActionInfo info : state.actionLookup.values()) {
            if (info.action().status() == ActionStatus.COMPLETED) {
                completedActionIds.add(info.action().id());
            }
        }
        ConditionContext context = new ConditionContext(this.currentTime, completedActionIds);

        for (int i = 0; i < state.takts.size(); i++) {
            Takt takt = state.takts.get(i);
            TaktState taktState = state.taktStates.get(takt.name());

            if (taktState != TaktState.WAITING) {
                continue;
            }

            // Check all conditions
            List<TaktCondition> conditions = state.taktConditions.getOrDefault(takt.name(), List.of());
            Set<String> overrides = state.overriddenConditions.getOrDefault(takt.name(), Set.of());

            boolean allSatisfied = true;
            for (TaktCondition condition : conditions) {
                if (!overrides.contains(condition.id()) && !condition.evaluate(context)) {
                    allSatisfied = false;
                    break;
                }
            }

            if (!allSatisfied) {
                continue;
            }

            // Activate this takt - record actual start time as current system time
            state.taktStates.put(takt.name(), TaktState.ACTIVE);
            state.actualStartTimes.put(takt.name(), this.currentTime);
            sideEffects.add(new TaktActivated(workQueueId, takt.name(), this.currentTime));

            if (takt.actions().isEmpty()) {
                // Empty takt completes immediately if previous takt is completed
                if (isPreviousTaktCompleted(state, takt.name())) {
                    state.taktStates.put(takt.name(), TaktState.COMPLETED);
                    sideEffects.add(new TaktCompleted(workQueueId, takt.name(), this.currentTime));
                }
            }
        }

        return sideEffects;
    }

    /**
     * Checks whether the takt immediately before the given takt (in list order) is completed.
     * Returns {@code true} if there is no previous takt (i.e. this is the first takt).
     */
    private boolean isPreviousTaktCompleted(ScheduleState state, String taktName) {
        Takt previousTakt = null;
        for (Takt t : state.takts) {
            if (t.name().equals(taktName)) {
                break;
            }
            previousTakt = t;
        }
        if (previousTakt == null) {
            return true;
        }
        return state.taktStates.get(previousTakt.name()) == TaktState.COMPLETED;
    }

    /**
     * Cascades takt completion: checks all active takts whose actions are fully completed
     * and whose previous takt is now completed.
     */
    private List<SideEffect> tryCompletePendingTakts(long workQueueId, ScheduleState state) {
        List<SideEffect> sideEffects = new ArrayList<>();
        for (Takt takt : state.takts) {
            TaktState taktState = state.taktStates.get(takt.name());
            if (taktState == TaktState.ACTIVE
                    && state.isTaktFullyCompleted(takt.name())
                    && isPreviousTaktCompleted(state, takt.name())) {
                state.taktStates.put(takt.name(), TaktState.COMPLETED);
                sideEffects.add(new TaktCompleted(workQueueId, takt.name(), this.currentTime));
            }
        }
        return sideEffects;
    }

    /**
     * Collects action IDs from all takts that come after the given takt in sequence order.
     */
    private Set<UUID> collectLaterTaktActionIds(Takt currentTakt, ScheduleState state) {
        Set<UUID> laterIds = new HashSet<>();
        for (Takt t : state.takts) {
            if (t.sequence() > currentTakt.sequence()) {
                for (Action a : t.actions()) {
                    laterIds.add(a.id());
                }
            }
        }
        return laterIds;
    }

    /**
     * Arms any event gates that have the given action as their source.
     */
    private void armEventGatesForAction(ScheduleState state, UUID activatedActionId) {
        for (Map.Entry<UUID, UUID> entry : state.gateArmSourceActions.entrySet()) {
            if (entry.getValue().equals(activatedActionId)) {
                UUID gatedActionId = entry.getKey();
                ActionInfo gatedInfo = state.actionLookup.get(gatedActionId);
                if (gatedInfo != null) {
                    for (EventGateCondition gate : gatedInfo.action().eventGates()) {
                        state.armedEventGates
                                .computeIfAbsent(gatedActionId, k -> new HashSet<>())
                                .add(gate.id());
                    }
                }
            }
        }
    }

    /**
     * Activates all actions in the given takt that have their dependencies satisfied.
     */
    private List<SideEffect> activateEligibleActions(long workQueueId, ScheduleState state, String taktName,
                                                       Set<String> occupiedPositions, Set<String> assignedTrucks) {
        List<SideEffect> sideEffects = new ArrayList<>();

        boolean progress = true;
        while (progress) {
            progress = false;
            List<UUID> actionsToActivate = state.getActivatableActionsInTakt(taktName, occupiedPositions);
            // Process TT actions first so truck allocation + targetChe propagation
            // happens before QC/RTG actions activate in the same pass
            actionsToActivate.sort(Comparator.comparingInt(id -> {
                ActionInfo info = state.actionLookup.get(id);
                return info != null && info.action().deviceType() == DeviceType.TT ? 0 : 1;
            }));
            for (UUID actionId : actionsToActivate) {
                ActionInfo actionInfo = state.actionLookup.get(actionId);
                Action action = actionInfo.action();
                Set<String> overrides = state.overriddenActionConditions.getOrDefault(actionId, Set.of());

                // Auto-complete: if skipWhenGatesSatisfied and all gates are already satisfied,
                // skip this action (mark completed without activating).
                // Extra safety: verify all dependencies are truly COMPLETED, not just activatable.
                if (action.skipWhenGatesSatisfied()
                        && state.areEventGatesSatisfied(actionId, action, overrides)
                        && state.areDependenciesCompleted(action.dependsOn())) {
                    state.setActionStatus(actionId, ActionStatus.COMPLETED);
                    sideEffects.add(new ActionCompleted(
                            actionId, workQueueId, actionInfo.taktName(),
                            action.description(), this.currentTime
                    ));
                    progress = true;
                    continue;
                }

                // TT allocation: if this is a TT action without a truck assigned, try to allocate one
                if (action.deviceType() == DeviceType.TT && action.cheShortName() == null
                        && ttAllocationStrategy != null) {
                    var allocation = ttAllocationStrategy.allocateFreeTruck(assignedTrucks);
                    if (allocation.isEmpty()) {
                        // No truck available — action stays pending
                        continue;
                    }
                    String truckName = allocation.get().cheShortName();
                    Long truckCheId = allocation.get().cheId();
                    assignedTrucks.add(truckName);

                    Action assignedAction = action.withTruckAssignment(truckCheId, truckName);
                    state.actionLookup.put(actionId, new ActionInfo(actionInfo.taktName(), assignedAction));
                    actionInfo = state.actionLookup.get(actionId);
                    action = assignedAction;
                    sideEffects.add(new TruckAssigned(actionId, workQueueId, truckName, truckCheId,
                            action.workInstructions()));

                    // Propagate truck assignment to all other TT actions with the same containerIndex,
                    // and set targetChe on non-TT actions (QC, RTG) with the same containerIndex
                    int containerIdx = action.containerIndex();
                    for (Map.Entry<UUID, ActionInfo> e : state.actionLookup.entrySet()) {
                        Action other = e.getValue().action();
                        if (other.containerIndex() == containerIdx && !e.getKey().equals(actionId)) {
                            if (other.deviceType() == DeviceType.TT && other.cheShortName() == null) {
                                Action propagated = other.withTruckAssignment(truckCheId, truckName);
                                state.actionLookup.put(e.getKey(), new ActionInfo(e.getValue().taktName(), propagated));
                            } else if (other.deviceType() != DeviceType.TT && other.targetChe() == null) {
                                Action withTarget = other.withTargetChe(truckName);
                                state.actionLookup.put(e.getKey(), new ActionInfo(e.getValue().taktName(), withTarget));
                            }
                        }
                    }
                }

                // Location skip: if the next position is free, skip this action (auto-complete)
                // Must be after TT allocation — can't skip without a truck assigned
                if (action.cheShortName() != null
                        && state.shouldSkipForLocation(action, occupiedPositions, overrides)) {
                    state.setActionStatus(actionId, ActionStatus.COMPLETED);
                    sideEffects.add(new ActionCompleted(
                            actionId, workQueueId, actionInfo.taktName(),
                            action.description(), this.currentTime,
                            CompletionReason.LOCATION_SKIPPED
                    ));
                    progress = true;
                    continue;
                }

                state.setActionStatus(actionId, ActionStatus.ACTIVE);
                armEventGatesForAction(state, actionId);
                String cheForMsg = action.deviceType() == DeviceType.TT ? action.cheShortName() : action.targetChe();
                sideEffects.add(new ActionActivated(
                        actionId,
                        workQueueId,
                        actionInfo.taktName(),
                        action.actionType(),
                        action.description(),
                        this.currentTime,
                        action.deviceType(),
                        action.workInstructions(),
                        cheForMsg
                ));
            }
        }

        return sideEffects;
    }

    /**
     * Auto-completes any currently active actions that have skipWhenGatesSatisfied=true
     * and whose event gates are now all satisfied. This handles the case where a
     * conditional action was activated (gates not yet satisfied) and then all gates
     * become satisfied while the action is still active.
     */
    private List<SideEffect> autoCompleteGatedActions(long workQueueId, ScheduleState state) {
        List<SideEffect> sideEffects = new ArrayList<>();
        for (ActionInfo info : state.getActiveAndWaitingTaktActions()) {
            Action action = info.action();
            if (action.status() != ActionStatus.ACTIVE) continue;
            if (!action.skipWhenGatesSatisfied()) continue;
            UUID actionId = action.id();
            Set<String> overrides = state.overriddenActionConditions.getOrDefault(actionId, Set.of());
            if (!state.areEventGatesSatisfied(actionId, action, overrides)) continue;

            state.setActionStatus(actionId, ActionStatus.COMPLETED);
            sideEffects.add(new ActionCompleted(
                    actionId, workQueueId, info.taktName(),
                    action.description(), this.currentTime
            ));
        }
        return sideEffects;
    }

    private List<SideEffect> handleActionCompleted(ActionCompletedEvent event) {
        long workQueueId = event.workQueueId();
        UUID completedActionId = event.actionId();

        ScheduleState state = scheduleStates.get(workQueueId);
        if (state == null) {
            return List.of();
        }

        ActionInfo completedActionInfo = state.actionLookup.get(completedActionId);
        if (completedActionInfo == null) {
            return List.of();
        }

        if (completedActionInfo.action().status() != ActionStatus.ACTIVE) {
            return List.of();
        }

        // Move action from active to completed
        state.setActionStatus(completedActionId, ActionStatus.COMPLETED);

        // Produce ActionCompleted side effect
        return List.of(new ActionCompleted(
                completedActionId,
                workQueueId,
                completedActionInfo.taktName(),
                completedActionInfo.action().description(),
                currentTime
        ));

        // All cascading (auto-complete gated actions, activate eligible actions,
        // takt completion, cross-schedule truck reallocation) is handled by reactivateAllSchedules()
    }

    @Override
    public Object captureState() {
        Map<String, Object> state = new HashMap<>();

        Map<Long, ScheduleState> statesCopy = new HashMap<>();
        for (Map.Entry<Long, ScheduleState> entry : scheduleStates.entrySet()) {
            statesCopy.put(entry.getKey(), entry.getValue().copy());
        }
        state.put("scheduleStates", statesCopy);
        state.put("workInstructionEstimatedMoveTime", new HashMap<>(workInstructionEstimatedMoveTime));
        state.put("currentTime", currentTime);

        return state;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restoreState(Object state) {
        if (!(state instanceof Map)) {
            throw new IllegalArgumentException("Invalid state type for ScheduleRunnerProcessor");
        }

        Map<String, Object> stateMap = (Map<String, Object>) state;

        scheduleStates.clear();
        Object schedulesState = stateMap.get("scheduleStates");
        if (schedulesState instanceof Map) {
            Map<Long, ScheduleState> schedulesMap = (Map<Long, ScheduleState>) schedulesState;
            for (Map.Entry<Long, ScheduleState> entry : schedulesMap.entrySet()) {
                scheduleStates.put(entry.getKey(), entry.getValue().copy());
            }
        }

        workInstructionEstimatedMoveTime.clear();
        Object estimatedMoveTimeState = stateMap.get("workInstructionEstimatedMoveTime");
        if (estimatedMoveTimeState instanceof Map) {
            workInstructionEstimatedMoveTime.putAll((Map<Long, Instant>) estimatedMoveTimeState);
        }

        Object currentTimeState = stateMap.get("currentTime");
        if (currentTimeState instanceof Instant instant) {
            this.currentTime = instant;
        }
    }
}
