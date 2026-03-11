package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ConditionContext;
import com.wonderingwizard.domain.takt.DependencyCondition;
import com.wonderingwizard.domain.takt.TaktCondition;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.domain.takt.TimeCondition;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.EventProcessor;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.ActionCompletedEvent;
import com.wonderingwizard.events.OverrideActionConditionEvent;
import com.wonderingwizard.events.OverrideConditionEvent;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.sideeffects.ActionActivated;
import com.wonderingwizard.sideeffects.ActionCompleted;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import com.wonderingwizard.sideeffects.ScheduleModified;
import com.wonderingwizard.sideeffects.TaktActivated;
import com.wonderingwizard.sideeffects.TaktCompleted;

import java.time.Instant;
import java.util.ArrayList;
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
        Set<UUID> activeActionIds;
        Set<UUID> completedActionIds;
        Map<UUID, ActionInfo> actionLookup;
        Map<String, TaktState> taktStates;
        Map<String, Instant> actualStartTimes;
        /** Conditions per takt, keyed by takt name. */
        Map<String, List<TaktCondition>> taktConditions;
        /** Overridden condition IDs per takt, keyed by takt name. */
        Map<String, Set<String>> overriddenConditions;
        /** Overridden action condition IDs per action, keyed by action UUID. */
        Map<UUID, Set<String>> overriddenActionConditions;

        ScheduleState(Instant estimatedMoveTime, List<Takt> takts) {
            this.estimatedMoveTime = estimatedMoveTime;
            this.takts = takts;
            this.activeActionIds = new HashSet<>();
            this.completedActionIds = new HashSet<>();
            this.actionLookup = new HashMap<>();
            this.taktStates = new HashMap<>();
            this.actualStartTimes = new HashMap<>();
            this.taktConditions = new LinkedHashMap<>();
            this.overriddenConditions = new HashMap<>();
            this.overriddenActionConditions = new HashMap<>();

            // Build action lookup and initialize takt states
            for (Takt takt : takts) {
                taktStates.put(takt.name(), TaktState.WAITING);
                overriddenConditions.put(takt.name(), new HashSet<>());
                for (Action action : takt.actions()) {
                    actionLookup.put(action.id(), new ActionInfo(takt.name(), action));
                }
            }
        }

        /**
         * Gets all actions in a given takt that are eligible for activation.
         * An action is eligible when it is not yet active or completed,
         * and all its dependencies are completed.
         */
        List<UUID> getActivatableActionsInTakt(String taktName) {
            List<UUID> result = new ArrayList<>();
            for (ActionInfo info : actionLookup.values()) {
                if (!info.taktName().equals(taktName)) {
                    continue;
                }
                UUID actionId = info.action().id();
                if (activeActionIds.contains(actionId) || completedActionIds.contains(actionId)) {
                    continue;
                }
                Set<String> actionOverrides = overriddenActionConditions.getOrDefault(actionId, Set.of());
                boolean depsOverridden = actionOverrides.contains("action-dependencies");
                Set<UUID> dependencies = info.action().dependsOn();
                if (depsOverridden || dependencies == null || dependencies.isEmpty() || completedActionIds.containsAll(dependencies)) {
                    result.add(actionId);
                }
            }
            return result;
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
            for (ActionInfo info : actionLookup.values()) {
                if (info.taktName().equals(taktName)) {
                    if (!completedActionIds.contains(info.action().id())) {
                        return false;
                    }
                }
            }
            return true;
        }

        ScheduleState copy() {
            ScheduleState copy = new ScheduleState(this.estimatedMoveTime, new ArrayList<>(this.takts));
            copy.activeActionIds = new HashSet<>(this.activeActionIds);
            copy.completedActionIds = new HashSet<>(this.completedActionIds);
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
            return copy;
        }
    }

    private record ActionInfo(String taktName, Action action) {}

    private final Map<Long, ScheduleState> scheduleStates = new HashMap<>();
    private final Map<Long, Instant> workInstructionEstimatedMoveTime = new HashMap<>();
    private Instant currentTime = Instant.EPOCH;

    @Override
    public List<SideEffect> process(Event event) {
        if (event instanceof WorkQueueMessage message) {
            return handleWorkQueueMessage(message);
        }
        if (event instanceof WorkInstructionEvent instruction) {
            return handleWorkInstructionEvent(instruction);
        }
        if (event instanceof ScheduleCreated scheduleCreated) {
            return handleScheduleCreated(scheduleCreated);
        }
        if (event instanceof ScheduleModified scheduleModified) {
            return handleScheduleModified(scheduleModified);
        }
        if (event instanceof TimeEvent timeEvent) {
            return handleTimeEvent(timeEvent);
        }
        if (event instanceof ActionCompletedEvent completed) {
            return handleActionCompleted(completed);
        }
        if (event instanceof OverrideConditionEvent override) {
            return handleOverrideCondition(override);
        }
        if (event instanceof OverrideActionConditionEvent override) {
            return handleOverrideActionCondition(override);
        }
        return List.of();
    }

    private List<SideEffect> handleScheduleCreated(ScheduleCreated scheduleCreated) {
        long workQueueId = scheduleCreated.workQueueId();
        List<Takt> takts = scheduleCreated.takts();
        Instant estimatedMoveTime = scheduleCreated.estimatedMoveTime();

        ScheduleState state = new ScheduleState(estimatedMoveTime, takts);
        buildConditions(state);
        scheduleStates.put(workQueueId, state);

        return List.of();
    }

    /**
     * Handles a schedule modification by replacing all WAITING takts at or after the
     * {@code firstNewTaktSequence} with the rebuilt takts from the ScheduleModified event.
     * ACTIVE and COMPLETED takts are preserved.
     */
    private List<SideEffect> handleScheduleModified(ScheduleModified modified) {
        long workQueueId = modified.workQueueId();
        ScheduleState state = scheduleStates.get(workQueueId);
        if (state == null) {
            return List.of();
        }

        int firstNewSeq = modified.firstNewTaktSequence();

        // Remove all WAITING takts at or after firstNewTaktSequence
        List<Takt> taktsToRemove = new ArrayList<>();
        for (Takt takt : state.takts) {
            if (takt.sequence() >= firstNewSeq
                    && state.taktStates.get(takt.name()) == TaktState.WAITING) {
                taktsToRemove.add(takt);
            }
        }

        for (Takt takt : taktsToRemove) {
            state.takts.remove(takt);
            state.taktStates.remove(takt.name());
            state.taktConditions.remove(takt.name());
            state.overriddenConditions.remove(takt.name());
            // Remove actions from lookup
            for (Action action : takt.actions()) {
                state.actionLookup.remove(action.id());
                state.activeActionIds.remove(action.id());
            }
        }

        // Add the rebuilt takts
        for (Takt newTakt : modified.newTakts()) {
            state.takts.add(newTakt);
            state.taktStates.put(newTakt.name(), TaktState.WAITING);
            state.overriddenConditions.put(newTakt.name(), new HashSet<>());
            for (Action action : newTakt.actions()) {
                state.actionLookup.put(action.id(), new ActionInfo(newTakt.name(), action));
            }
        }

        // Sort takts by sequence for consistent ordering
        state.takts.sort((a, b) -> Integer.compare(a.sequence(), b.sequence()));

        // Rebuild conditions for new takts
        for (Takt newTakt : modified.newTakts()) {
            List<TaktCondition> conditions = new ArrayList<>();
            if (newTakt.estimatedStartTime() != null) {
                conditions.add(new TimeCondition(newTakt.estimatedStartTime()));
            }
            DependencyCondition depCondition = buildDependencyCondition(newTakt, state);
            if (depCondition != null) {
                conditions.add(depCondition);
            }
            state.taktConditions.put(newTakt.name(), conditions);
        }

        // Try to activate any of the new takts that are ready
        return tryActivateTakts(workQueueId, state);
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
        List<SideEffect> sideEffects = new ArrayList<>();
        this.currentTime = timeEvent.timestamp();

        for (Map.Entry<Long, ScheduleState> entry : scheduleStates.entrySet()) {
            long workQueueId = entry.getKey();
            ScheduleState state = entry.getValue();

            sideEffects.addAll(tryActivateTakts(workQueueId, state));
        }

        return sideEffects;
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

        // Try to activate takts now that a condition was overridden
        return tryActivateTakts(event.workQueueId(), state);
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

        // Try to activate takts first (in case this unblocks takt activation),
        // then try to activate actions in the action's takt if it's active
        List<SideEffect> sideEffects = new ArrayList<>(tryActivateTakts(event.workQueueId(), state));

        String taktName = actionInfo.taktName();
        if (state.taktStates.get(taktName) == TaktState.ACTIVE) {
            sideEffects.addAll(activateEligibleActions(event.workQueueId(), state, taktName));
        } else if (state.taktStates.get(taktName) == TaktState.WAITING) {
            // If all action conditions are satisfied/overridden, activate the action
            // even though its takt is still WAITING
            sideEffects.addAll(tryForceActivateAction(event.workQueueId(), state, event.actionId(), actionInfo));
        }

        return sideEffects;
    }

    /**
     * Checks if all conditions for an action are satisfied or overridden,
     * and activates the action even if its takt is still WAITING.
     */
    private List<SideEffect> tryForceActivateAction(long workQueueId, ScheduleState state,
                                                      UUID actionId, ActionInfo actionInfo) {
        if (state.activeActionIds.contains(actionId) || state.completedActionIds.contains(actionId)) {
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
                    || state.completedActionIds.containsAll(action.dependsOn());
            if (!depsComplete && !overrides.contains("action-dependencies")) {
                return List.of();
            }
        }

        // All conditions met — activate the action
        state.activeActionIds.add(actionId);
        return List.of(new ActionActivated(
                actionId, workQueueId, taktName,
                action.actionType(), action.description(), this.currentTime,
                action.deviceType(), action.workInstructions()
        ));
    }

    /**
     * Tries to activate takts whose conditions are all satisfied (or overridden).
     * When a takt becomes Active, eligible actions within it are also activated.
     */
    private List<SideEffect> tryActivateTakts(long workQueueId, ScheduleState state) {
        List<SideEffect> sideEffects = new ArrayList<>();

        ConditionContext context = new ConditionContext(this.currentTime, state.completedActionIds);

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
                continue;
            }

            // Activate eligible actions in this takt
            sideEffects.addAll(activateEligibleActions(workQueueId, state, takt.name()));
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
     * Activates all actions in the given takt that have their dependencies satisfied.
     */
    private List<SideEffect> activateEligibleActions(long workQueueId, ScheduleState state, String taktName) {
        List<SideEffect> sideEffects = new ArrayList<>();

        List<UUID> actionsToActivate = state.getActivatableActionsInTakt(taktName);
        for (UUID actionId : actionsToActivate) {
            state.activeActionIds.add(actionId);
            ActionInfo actionInfo = state.actionLookup.get(actionId);

            sideEffects.add(new ActionActivated(
                    actionId,
                    workQueueId,
                    actionInfo.taktName(),
                    actionInfo.action().actionType(),
                    actionInfo.action().description(),
                    this.currentTime,
                    actionInfo.action().deviceType(),
                    actionInfo.action().workInstructions()
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

        if (!state.activeActionIds.contains(completedActionId)) {
            return List.of();
        }

        ActionInfo completedActionInfo = state.actionLookup.get(completedActionId);
        if (completedActionInfo == null) {
            return List.of();
        }

        List<SideEffect> sideEffects = new ArrayList<>();

        // Move action from active to completed
        state.activeActionIds.remove(completedActionId);
        state.completedActionIds.add(completedActionId);

        // Produce ActionCompleted side effect
        sideEffects.add(new ActionCompleted(
                completedActionId,
                workQueueId,
                completedActionInfo.taktName(),
                completedActionInfo.action().description(),
                currentTime
        ));

        // Activate any actions in all active takts whose dependencies are now satisfied.
        // With cross-container dependencies, an action in a later takt may unblock
        // actions in earlier (already active) takts.
        for (Takt t : state.takts) {
            if (state.taktStates.get(t.name()) == TaktState.ACTIVE) {
                sideEffects.addAll(activateEligibleActions(workQueueId, state, t.name()));
            }
        }

        // Also check actions in WAITING takts that have all conditions overridden/satisfied
        for (ActionInfo info : state.actionLookup.values()) {
            if (state.taktStates.get(info.taktName()) == TaktState.WAITING) {
                UUID actionId = info.action().id();
                if (!state.activeActionIds.contains(actionId) && !state.completedActionIds.contains(actionId)) {
                    sideEffects.addAll(tryForceActivateAction(workQueueId, state, actionId, info));
                }
            }
        }

        // Check if the completed action's takt is now fully completed
        String completedTaktName = completedActionInfo.taktName();
        if (state.isTaktFullyCompleted(completedTaktName)
                && isPreviousTaktCompleted(state, completedTaktName)) {
            state.taktStates.put(completedTaktName, TaktState.COMPLETED);
            sideEffects.add(new TaktCompleted(workQueueId, completedTaktName, currentTime));

            // Cascade: completing this takt may unblock subsequent takts
            sideEffects.addAll(tryCompletePendingTakts(workQueueId, state));
        }

        // Try to activate takts whose first action dependencies are now met
        sideEffects.addAll(tryActivateTakts(workQueueId, state));

        return sideEffects;
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
