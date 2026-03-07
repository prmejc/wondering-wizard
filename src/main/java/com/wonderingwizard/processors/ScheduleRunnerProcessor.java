package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.EventProcessor;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.ActionCompletedEvent;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.sideeffects.ActionActivated;
import com.wonderingwizard.sideeffects.ActionCompleted;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import com.wonderingwizard.sideeffects.TaktActivated;
import com.wonderingwizard.sideeffects.TaktCompleted;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Processor that handles schedule execution with takt and action state machines.
 * <p>
 * Takt state machine: Waiting → Active → Completed
 * <ul>
 *   <li>A takt transitions to Active when the previous takt is Completed (or it is the first)
 *       AND the current time >= the takt's start time</li>
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

        ScheduleState(Instant estimatedMoveTime, List<Takt> takts) {
            this.estimatedMoveTime = estimatedMoveTime;
            this.takts = takts;
            this.activeActionIds = new HashSet<>();
            this.completedActionIds = new HashSet<>();
            this.actionLookup = new HashMap<>();
            this.taktStates = new HashMap<>();

            // Build action lookup and initialize takt states
            for (Takt takt : takts) {
                taktStates.put(takt.name(), TaktState.WAITING);
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
                Set<UUID> dependencies = info.action().dependsOn();
                if (dependencies == null || dependencies.isEmpty() || completedActionIds.containsAll(dependencies)) {
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
            return copy;
        }
    }

    private record ActionInfo(String taktName, Action action) {}

    private final Map<String, ScheduleState> scheduleStates = new HashMap<>();
    private final Map<String, Instant> workInstructionEstimatedMoveTime = new HashMap<>();
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
        if (event instanceof TimeEvent timeEvent) {
            return handleTimeEvent(timeEvent);
        }
        if (event instanceof ActionCompletedEvent completed) {
            return handleActionCompleted(completed);
        }
        return List.of();
    }

    private List<SideEffect> handleScheduleCreated(ScheduleCreated scheduleCreated) {
        String workQueueId = scheduleCreated.workQueueId();
        List<Takt> takts = scheduleCreated.takts();
        Instant estimatedMoveTime = scheduleCreated.estimatedMoveTime();

        ScheduleState state = new ScheduleState(estimatedMoveTime, takts);
        scheduleStates.put(workQueueId, state);

        return List.of();
    }

    private List<SideEffect> handleWorkInstructionEvent(WorkInstructionEvent event) {
        if (event.estimatedMoveTime() != null) {
            workInstructionEstimatedMoveTime.put(event.workInstructionId(), event.estimatedMoveTime());
        }
        return List.of();
    }

    private List<SideEffect> handleWorkQueueMessage(WorkQueueMessage message) {
        String workQueueId = message.workQueueId();

        return switch (message.status()) {
            case ACTIVE -> handleScheduleActivation(workQueueId);
            case INACTIVE -> handleScheduleDeactivation(workQueueId);
            case null -> List.of();
        };
    }

    private List<SideEffect> handleScheduleActivation(String workQueueId) {
        if (scheduleStates.containsKey(workQueueId)) {
            return List.of();
        }
        scheduleStates.put(workQueueId, new ScheduleState(null, List.of()));
        return List.of();
    }

    private List<SideEffect> handleScheduleDeactivation(String workQueueId) {
        scheduleStates.remove(workQueueId);
        return List.of();
    }

    private List<SideEffect> handleTimeEvent(TimeEvent timeEvent) {
        List<SideEffect> sideEffects = new ArrayList<>();
        this.currentTime = timeEvent.timestamp();

        for (Map.Entry<String, ScheduleState> entry : scheduleStates.entrySet()) {
            String workQueueId = entry.getKey();
            ScheduleState state = entry.getValue();

            sideEffects.addAll(tryActivateTakts(workQueueId, state));
        }

        return sideEffects;
    }

    /**
     * Tries to activate takts whose conditions are met (previous takt completed + time >= startTime).
     * When a takt becomes Active, eligible actions within it are also activated.
     */
    private List<SideEffect> tryActivateTakts(String workQueueId, ScheduleState state) {
        List<SideEffect> sideEffects = new ArrayList<>();

        for (int i = 0; i < state.takts.size(); i++) {
            Takt takt = state.takts.get(i);
            TaktState taktState = state.taktStates.get(takt.name());

            if (taktState != TaktState.WAITING) {
                continue;
            }

            // Check condition 1: previous takt must be Completed (or this is the first takt)
            if (i > 0) {
                Takt previousTakt = state.takts.get(i - 1);
                TaktState previousState = state.taktStates.get(previousTakt.name());
                if (previousState != TaktState.COMPLETED) {
                    continue;
                }
            }

            // Check condition 2: current time >= takt's startTime
            Instant startTime = takt.startTime();
            if (startTime != null && this.currentTime.isBefore(startTime)) {
                continue;
            }

            // Activate this takt
            state.taktStates.put(takt.name(), TaktState.ACTIVE);
            sideEffects.add(new TaktActivated(workQueueId, takt.name(), this.currentTime));

            // Activate eligible actions in this takt
            sideEffects.addAll(activateEligibleActions(workQueueId, state, takt.name()));
        }

        return sideEffects;
    }

    /**
     * Activates all actions in the given takt that have their dependencies satisfied.
     */
    private List<SideEffect> activateEligibleActions(String workQueueId, ScheduleState state, String taktName) {
        List<SideEffect> sideEffects = new ArrayList<>();

        List<UUID> actionsToActivate = state.getActivatableActionsInTakt(taktName);
        for (UUID actionId : actionsToActivate) {
            state.activeActionIds.add(actionId);
            ActionInfo actionInfo = state.actionLookup.get(actionId);

            sideEffects.add(new ActionActivated(
                    actionId,
                    workQueueId,
                    actionInfo.taktName(),
                    actionInfo.action().description(),
                    this.currentTime
            ));
        }

        return sideEffects;
    }

    private List<SideEffect> handleActionCompleted(ActionCompletedEvent event) {
        String workQueueId = event.workQueueId();
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

        // Activate any actions in the same takt whose dependencies are now satisfied
        String taktName = completedActionInfo.taktName();
        TaktState taktState = state.taktStates.get(taktName);
        if (taktState == TaktState.ACTIVE) {
            sideEffects.addAll(activateEligibleActions(workQueueId, state, taktName));
        }

        // Check if the takt is now fully completed
        if (state.isTaktFullyCompleted(taktName)) {
            state.taktStates.put(taktName, TaktState.COMPLETED);
            sideEffects.add(new TaktCompleted(workQueueId, taktName, currentTime));

            // Try to activate the next takt(s)
            sideEffects.addAll(tryActivateTakts(workQueueId, state));
        }

        return sideEffects;
    }

    @Override
    public Object captureState() {
        Map<String, Object> state = new HashMap<>();

        Map<String, ScheduleState> statesCopy = new HashMap<>();
        for (Map.Entry<String, ScheduleState> entry : scheduleStates.entrySet()) {
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
            Map<String, ScheduleState> schedulesMap = (Map<String, ScheduleState>) schedulesState;
            for (Map.Entry<String, ScheduleState> entry : schedulesMap.entrySet()) {
                scheduleStates.put(entry.getKey(), entry.getValue().copy());
            }
        }

        workInstructionEstimatedMoveTime.clear();
        Object estimatedMoveTimeState = stateMap.get("workInstructionEstimatedMoveTime");
        if (estimatedMoveTimeState instanceof Map) {
            workInstructionEstimatedMoveTime.putAll((Map<String, Instant>) estimatedMoveTimeState);
        }

        Object currentTimeState = stateMap.get("currentTime");
        if (currentTimeState instanceof Instant instant) {
            this.currentTime = instant;
        }
    }
}
