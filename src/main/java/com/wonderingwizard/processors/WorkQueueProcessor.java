package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ContainerWorkflow;
import com.wonderingwizard.domain.takt.DeviceActionTemplate;
import com.wonderingwizard.domain.takt.DeviceType;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
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
    private final Map<String, Integer> qcMudaByQueue = new HashMap<>();

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
                event.estimatedMoveTime(),
                event.estimatedCycleTimeSeconds()
        );

        workInstructions
                .computeIfAbsent(workQueueId, k -> new ArrayList<>())
                .add(instruction);

        return List.of();
    }

    private List<SideEffect> handleWorkQueueMessage(WorkQueueMessage message) {
        String workQueueId = message.workQueueId();
        WorkQueueStatus status = message.status();
        qcMudaByQueue.put(workQueueId, message.qcMudaSeconds());

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
        // Find earliest estimated move time from work instructions
        var estimatedMoveTime = instructions.stream()
                .map(WorkInstruction::estimatedMoveTime)
                .filter(t -> t != null)
                .min(java.time.Instant::compareTo)
                .orElse(null);

        int qcMuda = qcMudaByQueue.getOrDefault(workQueueId, 0);
        List<Takt> takts = createTaktsFromWorkInstructions(instructions, estimatedMoveTime, qcMuda);

        return List.of(new ScheduleCreated(workQueueId, takts, estimatedMoveTime));
    }

    private List<Takt> createTaktsFromWorkInstructions(List<WorkInstruction> instructions, java.time.Instant estimatedMoveTime, int qcMudaSeconds) {
        if (instructions.isEmpty()) {
            return List.of();
        }

        List<DeviceActionTemplate> templates = ContainerWorkflow.ACTION_TEMPLATES;
        int minOffset = ContainerWorkflow.getMinTaktOffset();
        int adjustment = -minOffset; // Makes earliest takt index 0

        // Calculate total number of takts needed
        int numContainers = instructions.size();
        int maxTaktIndex = (numContainers - 1) + adjustment; // Last container's base takt
        int totalTakts = maxTaktIndex + 1;

        // Initialize takt action lists
        Map<Integer, List<Action>> actionsByTakt = new HashMap<>();
        for (int i = 0; i < totalTakts; i++) {
            actionsByTakt.put(i, new ArrayList<>());
        }

        // Track last action for shared devices (RTG, QC) across containers so that
        // container N's first action on a shared device depends on container N-1's last
        // action on that device. TT is per-container (each container gets its own truck).
        Map<DeviceType, Action> lastSharedDeviceAction = new EnumMap<>(DeviceType.class);

        // Create actions for each container (work instruction)
        for (int containerIndex = 0; containerIndex < numContainers; containerIndex++) {
            int baseTaktIndex = containerIndex + adjustment;

            // Per-container tracking for within-container same-device and cross-device deps
            Map<DeviceType, Action> lastActionByDevice = new EnumMap<>(DeviceType.class);

            for (DeviceActionTemplate template : templates) {
                int targetTaktIndex = baseTaktIndex + ContainerWorkflow.getTaktOffset(template);

                Action action = Action.create(template.deviceType(), template.description(), containerIndex, template.durationSeconds());

                // Build dependencies: previous action of same device + optional cross-device dependency
                Set<UUID> dependencies = new HashSet<>();

                // Depend on previous action of the same device type (within this container)
                Action prevSameDevice = lastActionByDevice.get(template.deviceType());
                if (prevSameDevice != null) {
                    dependencies.add(prevSameDevice.id());
                } else if (template.deviceType() != DeviceType.TT) {
                    // First action on a shared device: depend on previous container's last action
                    Action prevShared = lastSharedDeviceAction.get(template.deviceType());
                    if (prevShared != null) {
                        dependencies.add(prevShared.id());
                    }
                }

                // Cross-device dependency (handover synchronization)
                if (template.crossDeviceDependency() != null) {
                    Action prevOtherDevice = lastActionByDevice.get(template.crossDeviceDependency());
                    if (prevOtherDevice != null) {
                        dependencies.add(prevOtherDevice.id());
                    }
                }

                Action actionWithDeps = action.withDependencies(
                        dependencies.isEmpty() ? Set.of() : Set.copyOf(dependencies));
                actionsByTakt.get(targetTaktIndex).add(actionWithDeps);
                lastActionByDevice.put(template.deviceType(), actionWithDeps);
            }

            // Update shared device tracking for RTG and QC
            for (var entry : lastActionByDevice.entrySet()) {
                if (entry.getKey() != DeviceType.TT) {
                    lastSharedDeviceAction.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // Convert to Takt objects
        // The first QC takt is at index 'adjustment' (the offset that normalizes takt D to index 0)
        // Takt duration = estimatedCycleTime of the WI whose QC works in that takt + qcMuda
        // Planned start time is computed sequentially: next takt = prev takt start + prev takt duration
        int firstQcTaktIndex = adjustment;

        // Pre-compute duration for each takt based on the QC container's estimated cycle time
        // PULSE takts use the average takt duration rounded up to the nearest 10 seconds
        int[] durations = new int[totalTakts];
        int qcTaktCount = 0;
        int qcTaktDurationSum = 0;
        for (int i = 0; i < totalTakts; i++) {
            int containerIndex = i - firstQcTaktIndex;
            if (containerIndex >= 0 && containerIndex < instructions.size()) {
                durations[i] = instructions.get(containerIndex).estimatedCycleTimeSeconds() + qcMudaSeconds;
                qcTaktDurationSum += durations[i];
                qcTaktCount++;
            }
        }
        // PULSE duration: average of QC takt durations, rounded up to nearest 10
        int pulseDuration = 0;
        if (qcTaktCount > 0) {
            int avg = (qcTaktDurationSum + qcTaktCount - 1) / qcTaktCount; // ceiling division
            pulseDuration = ((avg + 9) / 10) * 10; // round up to nearest 10
        }
        for (int i = 0; i < totalTakts; i++) {
            int containerIndex = i - firstQcTaktIndex;
            if (containerIndex < 0 || containerIndex >= instructions.size()) {
                durations[i] = pulseDuration;
            }
        }

        // Compute planned start times sequentially
        // TAKT100 (first QC takt) starts at the first WI's estimatedMoveTime
        java.time.Instant firstQcTime = instructions.get(0).estimatedMoveTime();
        if (firstQcTime == null) {
            firstQcTime = java.time.Instant.EPOCH;
        }

        // Work backwards from firstQcTaktIndex for PULSE takts, forwards for QC takts
        java.time.Instant[] plannedTimes = new java.time.Instant[totalTakts];
        plannedTimes[firstQcTaktIndex] = firstQcTime;
        // Backward for PULSE takts
        for (int i = firstQcTaktIndex - 1; i >= 0; i--) {
            plannedTimes[i] = plannedTimes[i + 1].minusSeconds(durations[i]);
        }
        // Forward for subsequent QC takts
        for (int i = firstQcTaktIndex + 1; i < totalTakts; i++) {
            plannedTimes[i] = plannedTimes[i - 1].plusSeconds(durations[i - 1]);
        }

        List<Takt> takts = new ArrayList<>();
        for (int i = 0; i < totalTakts; i++) {
            String taktName = Takt.createTaktName(i, firstQcTaktIndex);
            takts.add(new Takt(taktName, actionsByTakt.get(i), plannedTimes[i], plannedTimes[i], durations[i]));
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
        state.put("qcMudaByQueue", new HashMap<>(qcMudaByQueue));

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

        qcMudaByQueue.clear();
        Object qcMudaState = stateMap.get("qcMudaByQueue");
        if (qcMudaState instanceof Map) {
            qcMudaByQueue.putAll((Map<String, Integer>) qcMudaState);
        }
    }
}
