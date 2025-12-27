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
import com.wonderingwizard.sideeffects.ScheduleAborted;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import com.wonderingwizard.sideeffects.WorkInstruction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Processor that handles work queue messages and manages schedule creation.
 * <p>
 * When a WorkQueueMessage with status ACTIVE is processed:
 * - If no schedule exists for the workQueueId, a new schedule is created (ScheduleCreated side effect)
 * - The ScheduleCreated includes all work instructions registered for that workQueueId
 * - If a schedule already exists for the workQueueId, no side effect is produced (idempotent)
 * <p>
 * When a WorkQueueMessage with status INACTIVE is processed:
 * - If a schedule exists for the workQueueId, it is aborted (ScheduleAborted side effect)
 * - If no schedule exists, no side effect is produced
 * - Work instructions remain stored for potential reactivation
 * <p>
 * When a WorkInstructionEvent is processed:
 * - The work instruction is stored associated with its workQueueId
 * - If a work instruction with the same ID exists in a different queue, it is moved
 * - If a work instruction with the same ID exists in the same queue, it is updated
 * - No side effect is produced
 */
public class WorkQueueProcessor implements EventProcessor {

    private final Map<String, Boolean> activeSchedules = new HashMap<>();
    private final Map<String, List<WorkInstruction>> workInstructions = new HashMap<>();

    @Override
    public List<SideEffect> process(Event event) {
        return switch (event) {
            case WorkQueueMessage message -> handleWorkQueueMessage(message);
            case WorkInstructionEvent instruction -> handleWorkInstructionEvent(instruction);
            case TimeEvent ignored -> List.of();
            case SetTimeAlarm ignored -> List.of();
            case ActionCompletedEvent ignored -> List.of();
        };
    }

    private List<SideEffect> handleWorkInstructionEvent(WorkInstructionEvent event) {
        String workInstructionId = event.workInstructionId();
        String workQueueId = event.workQueueId();

        // Remove existing instruction with same ID from all queues (handles moves and updates)
        for (List<WorkInstruction> instructions : workInstructions.values()) {
            instructions.removeIf(wi -> wi.workInstructionId().equals(workInstructionId));
        }

        // Add the instruction to the target queue
        WorkInstruction instruction = new WorkInstruction(
                workInstructionId,
                workQueueId,
                event.fetchChe(),
                event.status(),
                event.estimatedMoveTime()
        );

        workInstructions
                .computeIfAbsent(workQueueId, k -> new ArrayList<>())
                .add(instruction);

        return List.of();
    }

    private List<SideEffect> handleWorkQueueMessage(WorkQueueMessage message) {
        String workQueueId = message.workQueueId();
        WorkQueueStatus status = message.status();

        return switch (status) {
            case ACTIVE -> handleActiveStatus(workQueueId);
            case INACTIVE -> handleInactiveStatus(workQueueId);
            case null -> List.of();
        };
    }

    private List<SideEffect> handleActiveStatus(String workQueueId) {
        if (activeSchedules.containsKey(workQueueId)) {
            // Schedule already exists, idempotent - no side effect
            return List.of();
        }

        // Create new schedule with takts generated from work instructions
        activeSchedules.put(workQueueId, true);
        List<WorkInstruction> instructions = workInstructions.getOrDefault(workQueueId, List.of());
        List<Takt> takts = createTaktsFromWorkInstructions(instructions);
        return List.of(new ScheduleCreated(workQueueId, takts));
    }

    private List<Takt> createTaktsFromWorkInstructions(List<WorkInstruction> instructions) {
        List<Takt> takts = new ArrayList<>();

        // First pass: create all actions (without dependencies)
        List<List<Action>> allActions = new ArrayList<>();
        for (int i = 0; i < instructions.size(); i++) {
            Action action1 = Action.create("QC lift container from truck");
            Action action2 = Action.create("QC place container on vessel");
            allActions.add(List.of(action1, action2));
        }

        // Second pass: set up dependencies
        // Each action depends on the previous action in the sequence
        for (int taktIndex = 0; taktIndex < allActions.size(); taktIndex++) {
            List<Action> actions = allActions.get(taktIndex);
            List<Action> actionsWithDeps = new ArrayList<>();

            for (int actionIndex = 0; actionIndex < actions.size(); actionIndex++) {
                Action action = actions.get(actionIndex);
                Set<java.util.UUID> dependencies;

                if (actionIndex == 0 && taktIndex == 0) {
                    // First action of first takt: no dependencies
                    dependencies = Set.of();
                } else if (actionIndex == 0) {
                    // First action of subsequent takts: depends on last action of previous takt
                    Action lastActionOfPrevTakt = allActions.get(taktIndex - 1).get(1);
                    dependencies = Set.of(lastActionOfPrevTakt.id());
                } else {
                    // Other actions: depend on previous action in same takt
                    Action prevAction = actions.get(actionIndex - 1);
                    dependencies = Set.of(prevAction.id());
                }

                actionsWithDeps.add(action.withDependencies(dependencies));
            }

            String taktName = Takt.createTaktName(taktIndex);
            takts.add(new Takt(taktName, actionsWithDeps));
        }

        return takts;
    }

    private List<SideEffect> handleInactiveStatus(String workQueueId) {
        if (!activeSchedules.containsKey(workQueueId)) {
            // No schedule exists, nothing to abort
            return List.of();
        }

        // Abort the schedule (work instructions remain stored)
        activeSchedules.remove(workQueueId);
        return List.of(new ScheduleAborted(workQueueId));
    }

    @Override
    public Object captureState() {
        Map<String, Object> state = new HashMap<>();
        state.put("activeSchedules", new HashMap<>(activeSchedules));

        // Deep copy of work instructions
        Map<String, List<WorkInstruction>> instructionsCopy = new HashMap<>();
        for (Map.Entry<String, List<WorkInstruction>> entry : workInstructions.entrySet()) {
            instructionsCopy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        state.put("workInstructions", instructionsCopy);

        return state;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restoreState(Object state) {
        if (!(state instanceof Map)) {
            throw new IllegalArgumentException("Invalid state type for WorkQueueProcessor");
        }

        Map<String, Object> stateMap = (Map<String, Object>) state;

        activeSchedules.clear();
        Object activeSchedulesState = stateMap.get("activeSchedules");
        if (activeSchedulesState instanceof Map) {
            activeSchedules.putAll((Map<String, Boolean>) activeSchedulesState);
        }

        workInstructions.clear();
        Object instructionsState = stateMap.get("workInstructions");
        if (instructionsState instanceof Map) {
            Map<String, List<WorkInstruction>> instructionsMap = (Map<String, List<WorkInstruction>>) instructionsState;
            for (Map.Entry<String, List<WorkInstruction>> entry : instructionsMap.entrySet()) {
                workInstructions.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
    }
}
