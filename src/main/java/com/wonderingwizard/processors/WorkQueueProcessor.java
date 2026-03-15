package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.ContainerWorkflow;
import com.wonderingwizard.domain.takt.DeviceActionTemplate;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.EventProcessor;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.LoadMode;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.WorkInstructionStatus;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.events.WorkQueueStatus;
import com.wonderingwizard.sideeffects.ScheduleAborted;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import jdk.jfr.Timespan;

import java.security.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.wonderingwizard.domain.takt.DeviceType.*;
import static com.wonderingwizard.domain.takt.DeviceType.QC;
import static com.wonderingwizard.domain.takt.DeviceType.TT;

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

    private static final int DRIVE_TIME_MIN_SECONDS = 30;
    private static final int DRIVE_TIME_MAX_SECONDS = 300;
    private static final int QC_DRIVE_TIME_OFFSET_RANGE = 30;

    private final Map<Long, Boolean> activeSchedules = new HashMap<>();
    private final Map<Long, List<WorkInstructionEvent>> workInstructions = new HashMap<>();
    private final Map<Long, Integer> qcMudaByQueue = new HashMap<>();
    private final Map<Long, LoadMode> loadModeByQueue = new HashMap<>();
    private final IntSupplier driveTimeSupplier;
    private final IntSupplier qcDriveTimeOffsetSupplier;
    private final boolean useGraphScheduleBuilder;

    public WorkQueueProcessor() {
        this(
                () -> ThreadLocalRandom.current().nextInt(DRIVE_TIME_MIN_SECONDS, DRIVE_TIME_MAX_SECONDS + 1),
                () -> ThreadLocalRandom.current().nextInt(-QC_DRIVE_TIME_OFFSET_RANGE, QC_DRIVE_TIME_OFFSET_RANGE + 1),
                true
        );
    }

    public WorkQueueProcessor(IntSupplier driveTimeSupplier) {
        this(driveTimeSupplier, () -> 0, false);
    }

    public WorkQueueProcessor(IntSupplier driveTimeSupplier, IntSupplier qcDriveTimeOffsetSupplier) {
        this(driveTimeSupplier, qcDriveTimeOffsetSupplier, false);
    }

    public WorkQueueProcessor(IntSupplier driveTimeSupplier, IntSupplier qcDriveTimeOffsetSupplier, boolean useGraphScheduleBuilder) {
        this.driveTimeSupplier = driveTimeSupplier;
        this.qcDriveTimeOffsetSupplier = qcDriveTimeOffsetSupplier;
        this.useGraphScheduleBuilder = useGraphScheduleBuilder;
    }

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
        long workInstructionId = event.workInstructionId();
        long workQueueId = event.workQueueId();

        // Find the old instruction before removing it (for mismatch detection)
        WorkInstructionEvent oldInstruction = findWorkInstruction(workInstructionId);

        // Determine expected next WI BEFORE updating the map (so current event's
        // FETCH_COMPLETE status doesn't affect the lookup)
        WorkInstructionEvent expectedWi = null;
        if (event.status() == WorkInstructionStatus.FETCH_COMPLETE
                && activeSchedules.containsKey(workQueueId)) {
            expectedWi = findExpectedNextWi(workQueueId);
        }

        // Remove existing instruction with same ID from all queues (handles moves and updates)
        for (List<WorkInstructionEvent> instructions : workInstructions.values()) {
            instructions.removeIf(wi -> wi.workInstructionId() == workInstructionId);
        }

        // Add the instruction to the target queue
        workInstructions
                .computeIfAbsent(workQueueId, k -> new ArrayList<>())
                .add(event);

        if (expectedWi == null) {
            return List.of();
        }

        boolean needsReschedule = false;

        // Check 1: Was the right WI fetched? If not, swap positions with expected WI
        if (expectedWi.workInstructionId() != workInstructionId) {
            swapEstimatedMoveTime(workQueueId, event, expectedWi);
            needsReschedule = true;
        }

        // Check 2: Did twin flags change?
        if (oldInstruction != null && hasTwinFlagsMismatch(oldInstruction, event)) {
            needsReschedule = true;
        }

        if (needsReschedule) {
            return handleReschedule(workQueueId);
        }

        return List.of();
    }

    /**
     * Finds a work instruction by ID across all queues.
     */
    private WorkInstructionEvent findWorkInstruction(long workInstructionId) {
        for (List<WorkInstructionEvent> instructions : workInstructions.values()) {
            for (WorkInstructionEvent wi : instructions) {
                if (wi.workInstructionId() == workInstructionId) {
                    return wi;
                }
            }
        }
        return null;
    }

    /**
     * Finds the next expected WI to be fetched: the first non-FETCH_COMPLETE WI in schedule order.
     */
    private WorkInstructionEvent findExpectedNextWi(long workQueueId) {
        List<WorkInstructionEvent> allInstructions = workInstructions.getOrDefault(workQueueId, List.of());

        return allInstructions.stream()
                .sorted(Comparator.comparing(WorkInstructionEvent::estimatedMoveTime))
                .filter(wi -> wi.status() != WorkInstructionStatus.FETCH_COMPLETE)
                .findFirst()
                .orElse(null);
    }

    /**
     * Swaps the estimatedMoveTime between the fetched WI and the expected WI in the work
     * instructions map, so that the fetched WI takes the expected position and vice versa.
     */
    private void swapEstimatedMoveTime(long workQueueId, WorkInstructionEvent fetchedWi, WorkInstructionEvent expectedWi) {
        List<WorkInstructionEvent> instructions = workInstructions.get(workQueueId);
        if (instructions == null) {
            return;
        }

        Instant fetchedTime = fetchedWi.estimatedMoveTime();
        Instant expectedTime = expectedWi.estimatedMoveTime();

        instructions.replaceAll(wi -> {
            if (wi.workInstructionId() == fetchedWi.workInstructionId()) {
                return new WorkInstructionEvent(
                        wi.workInstructionId(), wi.workQueueId(), wi.fetchChe(),
                        wi.status(), expectedTime, wi.estimatedCycleTimeSeconds(),
                        wi.estimatedRtgCycleTimeSeconds(), wi.putChe(),
                        wi.isTwinFetch(), wi.isTwinPut(), wi.isTwinCarry(),
                        wi.twinCompanionWorkInstruction(), wi.toPosition(), wi.containerId());
            }
            if (wi.workInstructionId() == expectedWi.workInstructionId()) {
                return new WorkInstructionEvent(
                        wi.workInstructionId(), wi.workQueueId(), wi.fetchChe(),
                        wi.status(), fetchedTime, wi.estimatedCycleTimeSeconds(),
                        wi.estimatedRtgCycleTimeSeconds(), wi.putChe(),
                        wi.isTwinFetch(), wi.isTwinPut(), wi.isTwinCarry(),
                        wi.twinCompanionWorkInstruction(), wi.toPosition(), wi.containerId());
            }
            return wi;
        });
    }

    /**
     * Checks whether the twin flags (isTwinFetch, isTwinPut, isTwinCarry) have changed
     * between the old and new work instruction.
     */
    private boolean hasTwinFlagsMismatch(WorkInstructionEvent oldWi, WorkInstructionEvent newWi) {
        return oldWi.isTwinFetch() != newWi.isTwinFetch()
                || oldWi.isTwinPut() != newWi.isTwinPut()
                || oldWi.isTwinCarry() != newWi.isTwinCarry();
    }

    /**
     * Rebuilds the entire schedule from scratch when a FETCH_COMPLETE event reveals
     * that the actual container configuration or order differs from the plan.
     */
    private List<SideEffect> handleReschedule(long workQueueId) {
        List<WorkInstructionEvent> allInstructions = workInstructions.getOrDefault(workQueueId, List.of());
        if (allInstructions.isEmpty()) {
            return List.of();
        }

        var estimatedMoveTime = allInstructions.stream()
                .map(WorkInstructionEvent::estimatedMoveTime)
                .filter(t -> t != null)
                .min(Instant::compareTo)
                .orElse(null);

        int qcMuda = qcMudaByQueue.getOrDefault(workQueueId, 0);
        LoadMode loadMode = loadModeByQueue.getOrDefault(workQueueId, LoadMode.DSCH);

        List<Takt> takts = new GraphScheduleBuilder(driveTimeSupplier, qcDriveTimeOffsetSupplier)
                .createTakts(allInstructions, estimatedMoveTime, qcMuda, loadMode);

        var sortedTakts = takts.stream()
                .sorted((a, b) -> a.sequence() - b.sequence())
                .toList();

        return List.of(new ScheduleCreated(workQueueId, sortedTakts, estimatedMoveTime));
    }

    private List<SideEffect> handleWorkQueueMessage(WorkQueueMessage message) {
        long workQueueId = message.workQueueId();
        WorkQueueStatus status = message.status();
        qcMudaByQueue.put(workQueueId, message.qcMudaSeconds());
        if (message.loadMode() != null) {
            loadModeByQueue.put(workQueueId, message.loadMode());
        }

        return switch (status) {
            case ACTIVE -> handleActiveStatus(workQueueId);
            case INACTIVE -> handleInactiveStatus(workQueueId);
            case null -> List.of();
        };
    }

    private List<SideEffect> handleActiveStatus(long workQueueId) {
        if (activeSchedules.containsKey(workQueueId)) {
            // Schedule already exists, idempotent - no side effect
            return List.of();
        }

        // Create new schedule with takts generated from work instructions
        activeSchedules.put(workQueueId, true);
        List<WorkInstructionEvent> instructions = workInstructions.getOrDefault(workQueueId, List.of());
        // Find earliest estimated move time from work instructions
        var estimatedMoveTime = instructions.stream()
                .map(WorkInstructionEvent::estimatedMoveTime)
                .filter(t -> t != null)
                .min(java.time.Instant::compareTo)
                .orElse(null);

        int qcMuda = qcMudaByQueue.getOrDefault(workQueueId, 0);
        LoadMode loadMode = loadModeByQueue.getOrDefault(workQueueId, LoadMode.DSCH);
        List<Takt> takts = useGraphScheduleBuilder
                ? new GraphScheduleBuilder(driveTimeSupplier, qcDriveTimeOffsetSupplier)
                        .createTakts(instructions, estimatedMoveTime, qcMuda, loadMode)
                : createTaktsFromWorkInstructionsPrimvs(instructions, estimatedMoveTime, qcMuda);

        return List.of(new ScheduleCreated(workQueueId, takts.stream().sorted( (a, b) -> a.sequence() - b.sequence()).toList(), estimatedMoveTime));
    }

    public List<Takt> createTaktsFromWorkInstructionsPrimvs(List<WorkInstructionEvent> instructions, java.time.Instant estimatedMoveTime, int qcMudaSeconds) {
        var taktsHashMap = new HashMap<Integer, Takt>();

        for (int i = 0;  i < instructions.size(); i++) {
            createTaktsForWorinstructionQc(instructions.get(i), qcMudaSeconds, i, taktsHashMap);
        }

        for (int i = instructions.size()-1; i >= 0; i--) {
            createTaktsForWorinstructionTT(instructions.get(i), qcMudaSeconds, i, taktsHashMap);
        }

        for (int i = instructions.size()-1; i >= 0; i--) {
            createTaktsForWorinstructionRTG(instructions.get(i), qcMudaSeconds, i, taktsHashMap);
        }

        return taktsHashMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
    }

    private void createTaktsForWorinstructionQc(WorkInstructionEvent workInstruction, int qcMudaSeconds, int containerIndex, HashMap<Integer, Takt> taktsHashMap) {

        var qcLiftDuration = 20;
        var qcActions = List.of(new ResourceAction(ActionType.QC_LIFT, qcLiftDuration, true, ActionType.QC_LIFT.displayName()), new ResourceAction(ActionType.QC_PLACE, workInstruction.estimatedCycleTimeSeconds() - qcLiftDuration, false, ActionType.QC_LIFT.displayName()));


        // Find previous container's last QC action for cross-container chaining
        Action previousAction = null;
        if (containerIndex > 0) {
            var prevTakt = taktsHashMap.get(containerIndex - 1);
            if (prevTakt != null) {
                previousAction = prevTakt.actions().stream()
                        .filter(a -> a.deviceType() == QC && a.containerIndex() == containerIndex - 1)
                        .reduce((first, second) -> second)
                        .orElse(null);
            }
        }
        for (ResourceAction qcAction : qcActions) {
            if(!taktsHashMap.containsKey(containerIndex)) {
                var startTime = workInstruction.estimatedMoveTime();

                var previousTakt = taktsHashMap.get(containerIndex-1);
                if(previousTakt != null) {
                    startTime = previousTakt.plannedStartTime().plusSeconds(previousTakt.durationSeconds());
                }

                taktsHashMap.put(containerIndex, new Takt(containerIndex, new ArrayList<>(), startTime, startTime, workInstruction.estimatedCycleTimeSeconds() + qcMudaSeconds));
            }
            var dependsOn = new HashSet<UUID>();
            if(previousAction != null) {
                dependsOn.add(previousAction.id());
            }
            var action = new Action(UUID.randomUUID(), QC, qcAction.actionType(), qcAction.actionName(), dependsOn, containerIndex, qcAction.duration());
            taktsHashMap.get(containerIndex).actions().add(action);
            previousAction = action;
        }
    }

    private void createTaktsForWorinstructionTT(WorkInstructionEvent workInstruction, int qcMudaSeconds, int containerIndex, HashMap<Integer, Takt> taktsHashMap) {

        var qcLiftDuration = 20;
        var rtgPlaceDuration = 20;
        var driveToUnderRtg = 30;
        var averageTaktDuration = 120;
        var driveToRtgPull = driveTimeSupplier.getAsInt();
        var driveToQcPull = Math.clamp(driveToRtgPull + qcDriveTimeOffsetSupplier.getAsInt(), DRIVE_TIME_MIN_SECONDS, DRIVE_TIME_MAX_SECONDS);
        var ttActions = List.of(
                new ResourceAction(ActionType.TT_DRIVE_TO_RTG_PULL, driveToRtgPull, false),
                new ResourceAction(ActionType.TT_DRIVE_TO_RTG_STANDBY, 30, false),
                new ResourceAction(ActionType.TT_DRIVE_TO_RTG_UNDER, driveToUnderRtg, true),
                new ResourceAction(ActionType.TT_HANDOVER_FROM_RTG, rtgPlaceDuration, false, null, true),
                new ResourceAction(ActionType.TT_DRIVE_TO_QC_PULL, driveToQcPull, false),
                new ResourceAction(ActionType.TT_DRIVE_TO_QC_STANDBY, 30, false),
                new ResourceAction(ActionType.TT_DRIVE_UNDER_QC, 30, false),
                new ResourceAction(ActionType.TT_HANDOVER_TO_QC, qcLiftDuration, true, ActionType.QC_LIFT.displayName()),
                new ResourceAction(ActionType.TT_DRIVE_TO_BUFFER, 30, false)
        );

        var currentActions = new LinkedList<Action>();
        var onlyOnePerTaktNames = new HashSet<String>();
        var currentTaktOffest = 0;
        Action previousAction = null;
        for (int i = 0; i < ttActions.size(); i++) {
            var ttActionTemplate = ttActions.reversed().get(i);
            var action = new Action(UUID.randomUUID(), TT, ttActionTemplate.actionType(), ttActionTemplate.actionName(), new HashSet<>(), containerIndex, ttActionTemplate.duration());

            if(previousAction != null) {
                previousAction.dependsOn().add(action.id());
            }
            previousAction = action;

            //add action to a list
            currentActions.addFirst(action);
            if(ttActionTemplate.onlyOnePerTakt()) {
                onlyOnePerTaktNames.add(ttActionTemplate.actionName());
            }
            //if action is first in takt, add all actions to the takt and decrease offset by 1
            var taktIndex = (containerIndex + currentTaktOffest);
            if(ttActionTemplate.firstInTakt()){
                if(!taktsHashMap.containsKey(taktIndex)) {
                    if(taktIndex < 100) {
                        var startTime = taktsHashMap.get(taktIndex+1).plannedStartTime().plusSeconds(-averageTaktDuration);
                        taktsHashMap.put(taktIndex, new Takt(taktIndex, new ArrayList<>(), startTime, startTime, averageTaktDuration));

                    }
                }
                var takt = taktsHashMap.get(containerIndex + currentTaktOffest);
                var taktEndTime = takt.plannedStartTime().plusSeconds(takt.durationSeconds());

                var allActionDuration = currentActions.stream().map(Action::durationSeconds).reduce(Integer::sum).orElse(0);
                while(taktEndTime.getEpochSecond() < takt.plannedStartTime().plusSeconds(allActionDuration).getEpochSecond()
                        || hasOnlyOnePerTaktConflict(takt, onlyOnePerTaktNames)){
                    currentTaktOffest--;
                    taktIndex = (containerIndex + currentTaktOffest);
                    if(!taktsHashMap.containsKey(taktIndex)) {
                        var startTime = taktsHashMap.get(taktIndex+1).plannedStartTime().plusSeconds(-averageTaktDuration);
                        taktsHashMap.put(taktIndex, new Takt(taktIndex, new ArrayList<>(), startTime, startTime, averageTaktDuration));
                    }
                    takt = taktsHashMap.get(taktIndex);
                }
                takt.actions().addAll(currentActions);
                currentActions.clear();
                onlyOnePerTaktNames.clear();
                currentTaktOffest--;
            }
        }
        var taktIndex = (containerIndex + currentTaktOffest);
        if(currentActions.size() > 0) {
            if(!taktsHashMap.containsKey(taktIndex)) {
                var startTime = taktsHashMap.get(taktIndex+1).plannedStartTime().plusSeconds(-averageTaktDuration);
                taktsHashMap.put(taktIndex, new Takt(taktIndex, new ArrayList<>(),startTime,startTime, averageTaktDuration));
            }
            var takt = taktsHashMap.get(containerIndex + currentTaktOffest);
            var taktEndTime = takt.plannedStartTime().plusSeconds(takt.durationSeconds());

            var allActionDuration = currentActions.stream().map(Action::durationSeconds).reduce(Integer::sum).orElse(0);
            while(taktEndTime.getEpochSecond() < takt.plannedStartTime().plusSeconds(allActionDuration).getEpochSecond()
                    || hasOnlyOnePerTaktConflict(takt, onlyOnePerTaktNames)){
                currentTaktOffest--;
                var nextTaktIndex = containerIndex + currentTaktOffest;
                if(!taktsHashMap.containsKey(nextTaktIndex)) {
                    var newStartTime = takt.plannedStartTime().plusSeconds(-averageTaktDuration);
                    taktsHashMap.put(nextTaktIndex, new Takt(nextTaktIndex, new ArrayList<>(), newStartTime, newStartTime, averageTaktDuration));
                }
                takt = taktsHashMap.get(nextTaktIndex);
            }
            taktsHashMap.get(containerIndex + currentTaktOffest).actions().addAll(currentActions);
            currentActions.clear();
        }
    }

    private boolean hasOnlyOnePerTaktConflict(Takt takt, Set<String> onlyOnePerTaktNames) {
        return takt.actions().stream()
                .anyMatch(a -> onlyOnePerTaktNames.contains(a.description()));
    }

    private void createTaktsForWorinstructionRTG(WorkInstructionEvent workInstruction, int qcMudaSeconds, int containerIndex, HashMap<Integer, Takt> taktsHashMap) {
        var rtgPlaceDuration = 20;
        var driveToUnderRtg = 30;
        var rtgActions = List.of(
                new ResourceAction(ActionType.RTG_DRIVE, 1, false),
                new ResourceAction(ActionType.RTG_FETCH, (workInstruction.estimatedRtgCycleTimeSeconds() - rtgPlaceDuration) + driveToUnderRtg, false),
                new ResourceAction(ActionType.RTG_HANDOVER_TO_TT, driveToUnderRtg + rtgPlaceDuration, true)
        );

        //find takt with TT action "handover from RTG" for this container index, build backwards from there
        var foundTaktIndex = taktsHashMap.entrySet().stream().filter((entry) -> entry.getValue().actions().stream().anyMatch(b -> b.actionType() == ActionType.TT_HANDOVER_FROM_RTG && b.containerIndex() == containerIndex)).map(integerTaktEntry -> integerTaktEntry.getKey()).findFirst().orElse(0);


        var currentActions = new LinkedList<Action>();
        var currentOffest = 0;
        Action previousAction = getRtgPreviousAction(foundTaktIndex, taktsHashMap);
        for (int i = 0; i < rtgActions.size(); i++) {
            var rtgActionTemplate = rtgActions.reversed().get(i);
            var action = new Action(UUID.randomUUID(), RTG, rtgActionTemplate.actionType(), rtgActionTemplate.actionName(), new HashSet<>(), containerIndex, rtgActionTemplate.duration());
            if(previousAction != null) {
                previousAction.dependsOn().add(action.id());
            }
            previousAction = action;
            currentActions.addFirst(action);

            if(rtgActionTemplate.firstInTakt()){
                var taktIndex = foundTaktIndex + currentOffest;
                currentOffest--;
                if(!taktsHashMap.containsKey(taktIndex)) {
                    taktsHashMap.put(taktIndex, new Takt(taktIndex, new ArrayList<>(), workInstruction.estimatedMoveTime(), workInstruction.estimatedMoveTime(), workInstruction.estimatedCycleTimeSeconds() + qcMudaSeconds));
                }
                taktsHashMap.get(taktIndex).actions().addAll(0, currentActions);
                currentActions.clear();
            }
        }

        var taktIndex = (foundTaktIndex + currentOffest);
        if(currentActions.size() > 0) {
            if(!taktsHashMap.containsKey(taktIndex)) {
                taktsHashMap.put(taktIndex, new Takt(taktIndex, new ArrayList<>(), workInstruction.estimatedMoveTime(), workInstruction.estimatedMoveTime(), workInstruction.estimatedCycleTimeSeconds() + qcMudaSeconds));
            }
            var takt = taktsHashMap.get(taktIndex);
            var taktEndTime = takt.plannedStartTime().plusSeconds(takt.durationSeconds());

            var allActionDuration = currentActions.stream().map(Action::durationSeconds).reduce(Integer::sum).orElse(0);
            while(taktEndTime.getEpochSecond() < takt.plannedStartTime().plusSeconds(allActionDuration).getEpochSecond()){
                currentOffest--;
                var nextTaktIndex = containerIndex + currentOffest;
                if(!taktsHashMap.containsKey(nextTaktIndex)) {
                    var newStartTime = takt.plannedStartTime().plusSeconds(-takt.durationSeconds());
                    taktsHashMap.put(nextTaktIndex, new Takt(nextTaktIndex, new ArrayList<>(), newStartTime, newStartTime, workInstruction.estimatedCycleTimeSeconds() + qcMudaSeconds));
                }
                takt = taktsHashMap.get(nextTaktIndex);
            }
            taktsHashMap.get(taktIndex).actions().addAll(0, currentActions);
            currentActions.clear();
        }

    }

    private Action getRtgPreviousAction(Integer foundTaktIndex, HashMap<Integer, Takt> taktsHashMap) {
        if(taktsHashMap.containsKey(foundTaktIndex)) {
            var thisTakt = taktsHashMap.get(foundTaktIndex);
            var previousAction = thisTakt.actions().stream().filter(a -> a.deviceType() == RTG).findFirst();
            if(previousAction.isPresent()){
                return previousAction.get();
            }
        }

        var nextTakt = foundTaktIndex + 1;
        if(taktsHashMap.containsKey(nextTakt)) {
            var thisTakt = taktsHashMap.get(nextTakt);
            var previousAction = thisTakt.actions().stream().filter(a -> a.deviceType() == RTG).findFirst();
            if(previousAction.isPresent()){
                return previousAction.get();
            }
        }
        return null;
    }

    private List<SideEffect> handleInactiveStatus(long workQueueId) {
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
        Map<Long, List<WorkInstructionEvent>> instructionsCopy = new HashMap<>();
        for (Map.Entry<Long, List<WorkInstructionEvent>> entry : workInstructions.entrySet()) {
            instructionsCopy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        state.put("workInstructions", instructionsCopy);
        state.put("qcMudaByQueue", new HashMap<>(qcMudaByQueue));
        state.put("loadModeByQueue", new HashMap<>(loadModeByQueue));

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
            activeSchedules.putAll((Map<Long, Boolean>) activeSchedulesState);
        }

        workInstructions.clear();
        Object instructionsState = stateMap.get("workInstructions");
        if (instructionsState instanceof Map) {
            Map<Long, List<WorkInstructionEvent>> instructionsMap = (Map<Long, List<WorkInstructionEvent>>) instructionsState;
            for (Map.Entry<Long, List<WorkInstructionEvent>> entry : instructionsMap.entrySet()) {
                workInstructions.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }

        qcMudaByQueue.clear();
        Object qcMudaState = stateMap.get("qcMudaByQueue");
        if (qcMudaState instanceof Map) {
            qcMudaByQueue.putAll((Map<Long, Integer>) qcMudaState);
        }

        loadModeByQueue.clear();
        Object loadModeState = stateMap.get("loadModeByQueue");
        if (loadModeState instanceof Map) {
            loadModeByQueue.putAll((Map<Long, LoadMode>) loadModeState);
        }
    }
}
