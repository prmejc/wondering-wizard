package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.EventProcessor;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.ActionCompletedEvent;
import com.wonderingwizard.events.SetTimeAlarm;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.events.WorkQueueStatus;
import com.wonderingwizard.sideeffects.ActionActivated;
import com.wonderingwizard.sideeffects.ActionCompleted;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Processor that handles schedule execution and action state transitions.
 * <p>
 * This processor tracks active schedules and manages the lifecycle of actions within takts:
 * <ul>
 *   <li>When a work queue becomes ACTIVE and current time >= estimatedMoveTime,
 *       the first action is activated</li>
 *   <li>When an ActionCompletedEvent is received with matching action ID,
 *       the action is marked complete and the next action is activated</li>
 *   <li>When all actions in a takt are complete, the first action of the next takt is activated</li>
 * </ul>
 */
public class ScheduleRunnerProcessor implements EventProcessor {

    /**
     * Tracks the schedule state for each work queue.
     */
    private static class ScheduleState {
        Instant estimatedMoveTime;
        List<Takt> takts;
        UUID currentActiveActionId;
        Map<UUID, ActionInfo> actionLookup;
        boolean started;

        ScheduleState(Instant estimatedMoveTime, List<Takt> takts) {
            this.estimatedMoveTime = estimatedMoveTime;
            this.takts = takts;
            this.currentActiveActionId = null;
            this.actionLookup = new HashMap<>();
            this.started = false;

            // Build action lookup for quick access
            for (Takt takt : takts) {
                for (Action action : takt.actions()) {
                    actionLookup.put(action.id(), new ActionInfo(takt.name(), action));
                }
            }
        }

        UUID getFirstActionId() {
            if (takts.isEmpty() || takts.get(0).actions().isEmpty()) {
                return null;
            }
            return takts.get(0).actions().get(0).id();
        }

        ScheduleState copy() {
            ScheduleState copy = new ScheduleState(this.estimatedMoveTime, new ArrayList<>(this.takts));
            copy.currentActiveActionId = this.currentActiveActionId;
            copy.started = this.started;
            return copy;
        }
    }

    private record ActionInfo(String taktName, Action action) {}

    private final Map<String, ScheduleState> scheduleStates = new HashMap<>();
    private final Map<String, Instant> workInstructionEstimatedMoveTime = new HashMap<>();

    @Override
    public List<SideEffect> process(Event event) {
        return switch (event) {
            case WorkQueueMessage message -> handleWorkQueueMessage(message);
            case WorkInstructionEvent instruction -> handleWorkInstructionEvent(instruction);
            case TimeEvent timeEvent -> handleTimeEvent(timeEvent);
            case ActionCompletedEvent completed -> handleActionCompleted(completed);
            case SetTimeAlarm ignored -> List.of();
        };
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
                UUID firstActionId = state.getFirstActionId();
                if (firstActionId != null) {
                    state.started = true;
                    state.currentActiveActionId = firstActionId;
                    ActionInfo actionInfo = state.actionLookup.get(firstActionId);

                    sideEffects.add(new ActionActivated(
                            firstActionId,
                            workQueueId,
                            actionInfo.taktName(),
                            actionInfo.action().description(),
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

        // Verify the completed action matches the currently active action
        if (state.currentActiveActionId == null || !state.currentActiveActionId.equals(completedActionId)) {
            // Action ID doesn't match - ignore
            return List.of();
        }

        ActionInfo completedActionInfo = state.actionLookup.get(completedActionId);
        if (completedActionInfo == null) {
            return List.of();
        }

        List<SideEffect> sideEffects = new ArrayList<>();
        Instant now = Instant.now();

        // Produce ActionCompleted side effect
        sideEffects.add(new ActionCompleted(
                completedActionId,
                workQueueId,
                completedActionInfo.taktName(),
                completedActionInfo.action().description(),
                now
        ));

        // Get next action from the linked reference
        UUID nextActionId = completedActionInfo.action().nextActionId();

        if (nextActionId != null) {
            ActionInfo nextActionInfo = state.actionLookup.get(nextActionId);
            if (nextActionInfo != null) {
                state.currentActiveActionId = nextActionId;

                // Produce ActionActivated side effect for next action
                sideEffects.add(new ActionActivated(
                        nextActionId,
                        workQueueId,
                        nextActionInfo.taktName(),
                        nextActionInfo.action().description(),
                        now
                ));
            }
        } else {
            // No more actions - schedule complete
            state.currentActiveActionId = null;
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
