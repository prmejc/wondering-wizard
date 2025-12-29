package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionState;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Processor that handles schedule execution and action state transitions.
 * <p>
 * This processor tracks active schedules and manages the lifecycle of actions within takts.
 * Actions can depend on multiple other actions, and will only be activated when all their
 * dependencies have been completed.
 * <ul>
 *   <li>When a work queue becomes ACTIVE and current time >= estimatedMoveTime,
 *       all actions with no dependencies are activated</li>
 *   <li>When an ActionCompletedEvent is received with matching action ID,
 *       the action is marked complete and any actions whose dependencies are now
 *       all satisfied are activated</li>
 * </ul>
 */
public class ScheduleRunnerProcessor implements EventProcessor {

    /**
     * Tracks the schedule state for each work queue.
     */
    private static class ScheduleState {
        Instant estimatedMoveTime;
        List<Takt> takts;
        Map<UUID, ActionInfo> actionLookup;
        boolean started;

        ScheduleState(Instant estimatedMoveTime, List<Takt> takts) {
            this.estimatedMoveTime = estimatedMoveTime;
            this.takts = takts;
            this.actionLookup = new HashMap<>();
            this.started = false;

            // Build action lookup with initial PENDING state
            for (Takt takt : takts) {
                for (Action action : takt.actions()) {
                    actionLookup.put(action.id(), new ActionInfo(takt.name(), action, ActionState.PENDING));
                }
            }
        }

        /**
         * Gets all actions that have no dependencies (can start immediately).
         */
        List<ActionInfo> getActionsWithNoDependencies() {
            List<ActionInfo> result = new ArrayList<>();
            for (ActionInfo info : actionLookup.values()) {
                if (info.action.hasNoDependencies()) {
                    result.add(info);
                }
            }
            return result;
        }

        /**
         * Checks if all given action IDs are in COMPLETED state.
         */
        boolean areAllCompleted(Set<UUID> actionIds) {
            if (actionIds == null || actionIds.isEmpty()) {
                return true;
            }
            for (UUID actionId : actionIds) {
                ActionInfo info = actionLookup.get(actionId);
                if (info == null || info.state != ActionState.COMPLETED) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Gets all pending actions whose dependencies are now all satisfied.
         */
        List<ActionInfo> getNewlyActivatableActions() {
            List<ActionInfo> result = new ArrayList<>();
            for (ActionInfo info : actionLookup.values()) {
                // Skip if not pending
                if (info.state != ActionState.PENDING) {
                    continue;
                }
                // Check if all dependencies are completed
                Set<UUID> dependencies = info.action.dependsOn();
                if (dependencies != null && !dependencies.isEmpty()) {
                    if (areAllCompleted(dependencies)) {
                        result.add(info);
                    }
                }
            }
            return result;
        }

        ScheduleState copy() {
            ScheduleState copy = new ScheduleState(this.estimatedMoveTime, new ArrayList<>(this.takts));
            // Deep copy action infos with their states
            copy.actionLookup = new HashMap<>();
            for (Map.Entry<UUID, ActionInfo> entry : this.actionLookup.entrySet()) {
                ActionInfo original = entry.getValue();
                copy.actionLookup.put(entry.getKey(), new ActionInfo(original.taktName, original.action, original.state));
            }
            copy.started = this.started;
            return copy;
        }
    }

    /**
     * Tracks an action along with its current execution state.
     */
    private static class ActionInfo {
        final String taktName;
        final Action action;
        ActionState state;

        ActionInfo(String taktName, Action action, ActionState state) {
            this.taktName = taktName;
            this.action = action;
            this.state = state;
        }
    }

    private final Map<String, ScheduleState> scheduleStates = new HashMap<>();
    private final Map<String, Instant> workInstructionEstimatedMoveTime = new HashMap<>();

    @Override
    public List<SideEffect> process(Event event) {
        if (event instanceof WorkQueueMessage message) {
            return handleWorkQueueMessage(message);
        }
        if (event instanceof WorkInstructionEvent instruction) {
            return handleWorkInstructionEvent(instruction);
        }
        if (event instanceof TimeEvent timeEvent) {
            return handleTimeEvent(timeEvent);
        }
        if (event instanceof ActionCompletedEvent completed) {
            return handleActionCompleted(completed);
        }
        return List.of();
    }

    private List<SideEffect> handleWorkInstructionEvent(WorkInstructionEvent event) {
        // Track the estimatedMoveTime for this work instruction
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
            // Already tracking this schedule
            return List.of();
        }

        // Find the earliest estimatedMoveTime from work instructions for this queue
        // For now, we'll create an empty schedule state that will be populated
        // when we receive the ScheduleCreated information via takts
        // Since we're using Option 1 (independent tracking), we need to build takts ourselves

        // We'll use a placeholder - the actual takts will be built when actions are needed
        scheduleStates.put(workQueueId, new ScheduleState(null, List.of()));

        return List.of();
    }

    private List<SideEffect> handleScheduleDeactivation(String workQueueId) {
        scheduleStates.remove(workQueueId);
        return List.of();
    }

    private List<SideEffect> handleTimeEvent(TimeEvent timeEvent) {
        List<SideEffect> sideEffects = new ArrayList<>();
        Instant currentTime = timeEvent.timestamp();

        for (Map.Entry<String, ScheduleState> entry : scheduleStates.entrySet()) {
            String workQueueId = entry.getKey();
            ScheduleState state = entry.getValue();

            // Check if schedule should start
            if (!state.started && state.estimatedMoveTime != null && !currentTime.isBefore(state.estimatedMoveTime)) {
                state.started = true;

                // Activate all actions with no dependencies
                List<ActionInfo> actionsToActivate = state.getActionsWithNoDependencies();
                for (ActionInfo actionInfo : actionsToActivate) {
                    actionInfo.state = ActionState.ACTIVE;

                    sideEffects.add(new ActionActivated(
                            actionInfo.action.id(),
                            workQueueId,
                            actionInfo.taktName,
                            actionInfo.action.description(),
                            currentTime
                    ));
                }
            }
        }

        return sideEffects;
    }

    private List<SideEffect> handleActionCompleted(ActionCompletedEvent event) {
        String workQueueId = event.workQueueId();
        UUID completedActionId = event.actionId();

        ScheduleState state = scheduleStates.get(workQueueId);
        if (state == null) {
            // No schedule for this work queue
            return List.of();
        }

        ActionInfo completedActionInfo = state.actionLookup.get(completedActionId);
        if (completedActionInfo == null) {
            return List.of();
        }

        // Verify the completed action is currently active
        if (completedActionInfo.state != ActionState.ACTIVE) {
            // Action is not active - ignore
            return List.of();
        }

        List<SideEffect> sideEffects = new ArrayList<>();
        Instant now = Instant.now();

        // Transition action from ACTIVE to COMPLETED
        completedActionInfo.state = ActionState.COMPLETED;

        // Produce ActionCompleted side effect
        sideEffects.add(new ActionCompleted(
                completedActionId,
                workQueueId,
                completedActionInfo.taktName,
                completedActionInfo.action.description(),
                now
        ));

        // Find and activate any actions whose dependencies are now all satisfied
        List<ActionInfo> newlyActivatableActions = state.getNewlyActivatableActions();
        for (ActionInfo actionInfo : newlyActivatableActions) {
            actionInfo.state = ActionState.ACTIVE;

            sideEffects.add(new ActionActivated(
                    actionInfo.action.id(),
                    workQueueId,
                    actionInfo.taktName,
                    actionInfo.action.description(),
                    now
            ));
        }

        return sideEffects;
    }

    /**
     * Updates the schedule state with takts information.
     * This should be called when a schedule is created to populate the action data.
     *
     * @param workQueueId the work queue ID
     * @param takts the list of takts for this schedule
     * @param estimatedMoveTime the estimated move time for the first action
     */
    public void initializeSchedule(String workQueueId, List<Takt> takts, Instant estimatedMoveTime) {
        ScheduleState state = new ScheduleState(estimatedMoveTime, takts);
        scheduleStates.put(workQueueId, state);
    }

    @Override
    public Object captureState() {
        Map<String, Object> state = new HashMap<>();

        // Deep copy schedule states
        Map<String, ScheduleState> statesCopy = new HashMap<>();
        for (Map.Entry<String, ScheduleState> entry : scheduleStates.entrySet()) {
            statesCopy.put(entry.getKey(), entry.getValue().copy());
        }
        state.put("scheduleStates", statesCopy);

        // Copy work instruction estimated move times
        state.put("workInstructionEstimatedMoveTime", new HashMap<>(workInstructionEstimatedMoveTime));

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
    }
}
