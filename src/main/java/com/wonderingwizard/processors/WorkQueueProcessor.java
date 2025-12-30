package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ContainerWorkflow;
import com.wonderingwizard.domain.takt.DeviceActionTemplate;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.EventProcessor;
import com.wonderingwizard.engine.SideEffect;
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
import java.util.UUID;

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
        if (event instanceof WorkQueueMessage message) {
            return handleWorkQueueMessage(message);
        }
        if (event instanceof WorkInstructionEvent instruction) {
            return handleWorkInstructionEvent(instruction);
        }
        return List.of();
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

        // Find earliest estimated move time from work instructions
        var estimatedMoveTime = instructions.stream()
                .map(WorkInstruction::estimatedMoveTime)
                .filter(t -> t != null)
                .min(java.time.Instant::compareTo)
                .orElse(null);

        return List.of(new ScheduleCreated(workQueueId, takts, estimatedMoveTime));
    }

    private List<Takt> createTaktsFromWorkInstructions(List<WorkInstruction> instructions) {
        if (instructions.isEmpty()) {
            return List.of();
        }

        List<DeviceActionTemplate> templates = ContainerWorkflow.ACTION_TEMPLATES;
        int minOffset = ContainerWorkflow.getMinTaktOffset();
        int adjustment = -minOffset; // Makes earliest takt index 0

        // Calculate total number of takts needed
        int numContainers = instructions.size();
        int maxTaktIndex = (numContainers - 1) + adjustment; // Last container's QC takt
        int totalTakts = maxTaktIndex + 1;

        // Initialize takt action lists
        Map<Integer, List<Action>> actionsByTakt = new HashMap<>();
        for (int i = 0; i < totalTakts; i++) {
            actionsByTakt.put(i, new ArrayList<>());
        }

        // Create actions for each container (work instruction)
        for (int containerIndex = 0; containerIndex < numContainers; containerIndex++) {
            int baseTaktIndex = containerIndex + adjustment;
            Action previousAction = null;

            // Create actions in workflow order (RTG -> TT -> QC)
            for (DeviceActionTemplate template : templates) {
                int targetTaktIndex = baseTaktIndex + ContainerWorkflow.getTaktOffset(template);

                // Create action with dependencies
                Action action = Action.create(template.deviceType(), template.description());

                Set<UUID> dependencies;
                if (previousAction == null) {
                    // First action of workflow: no dependencies
                    dependencies = Set.of();
                } else {
                    // Depends on previous action in the workflow
                    dependencies = Set.of(previousAction.id());
                }

                Action actionWithDeps = action.withDependencies(dependencies);
                actionsByTakt.get(targetTaktIndex).add(actionWithDeps);
                previousAction = actionWithDeps;
            }
        }

        // Convert to Takt objects
        List<Takt> takts = new ArrayList<>();
        for (int i = 0; i < totalTakts; i++) {
            String taktName = Takt.createTaktName(i);
            takts.add(new Takt(taktName, actionsByTakt.get(i)));
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
