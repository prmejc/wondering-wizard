package com.wonderingwizard.processors;

import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.EventProcessor;
import com.wonderingwizard.engine.SideEffect;
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
                event.status()
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

        // Create new schedule with associated work instructions
        activeSchedules.put(workQueueId, true);
        List<WorkInstruction> instructions = workInstructions.getOrDefault(workQueueId, List.of());
        return List.of(new ScheduleCreated(workQueueId, List.copyOf(instructions)));
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
