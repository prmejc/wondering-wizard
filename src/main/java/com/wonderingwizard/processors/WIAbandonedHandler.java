package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionStatus;
import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.CompletionReason;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.EventType;
import com.wonderingwizard.events.WorkInstructionEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Handles WI Abandoned events within the schedule context.
 * <p>
 * When a work instruction is abandoned, the behavior depends on whether the
 * TT handover from QC has started for the affected container:
 * <ul>
 *   <li><b>Before TT handover from QC is activated:</b> All TT actions for the affected container
 *       are reset to pending (truck unassigned), and the system attempts to allocate a new truck.</li>
 *   <li><b>After TT handover from QC is activated or completed:</b> All remaining actions for the
 *       affected container and its twin container are completed with reason
 *       {@link CompletionReason#WI_ABANDONED}.</li>
 * </ul>
 */
public class WIAbandonedHandler implements ScheduleSubProcessor {

    private static final Logger logger = Logger.getLogger(WIAbandonedHandler.class.getName());

    @Override
    public List<SideEffect> process(Event event, ScheduleContext context) {
        if (!(event instanceof WorkInstructionEvent wiEvent)) {
            return List.of();
        }
        if (!EventType.WI_ABANDONED.equals(wiEvent.eventType())) {
            return List.of();
        }

        long workInstructionId = wiEvent.workInstructionId();
        logger.info("Processing WI Abandoned for workInstructionId: " + workInstructionId);

        List<SideEffect> sideEffects = new ArrayList<>();

        for (long workQueueId : context.getScheduleWorkQueueIds()) {
            sideEffects.addAll(handleWIAbandonedForSchedule(workQueueId, workInstructionId, context));
        }

        return sideEffects;
    }

    private List<SideEffect> handleWIAbandonedForSchedule(long workQueueId, long workInstructionId,
                                                           ScheduleContext context) {
        Map<UUID, Action> actions = context.getActions(workQueueId);

        // Find the container index affected by matching workInstructionId against actions' work instructions
        Integer affectedContainerIndex = findAffectedContainerIndex(actions, workInstructionId);
        if (affectedContainerIndex == null) {
            return List.of();
        }

        boolean handoverStarted = isTTHandoverFromQCActivatedOrCompleted(
                workQueueId, affectedContainerIndex, actions, context);

        List<SideEffect> sideEffects = new ArrayList<>();

        if (handoverStarted) {
            sideEffects.addAll(completeRemainingActions(
                    workQueueId, affectedContainerIndex, actions, context));
            sideEffects.addAll(context.cascadeTaktCompletion(workQueueId));
        } else {
            sideEffects.addAll(resetTTActionsForContainer(
                    workQueueId, affectedContainerIndex, actions, context));
            if (!sideEffects.isEmpty()) {
                sideEffects.addAll(context.tryActivateEligibleActions(workQueueId));
            }
        }

        return sideEffects;
    }

    /**
     * Finds the container index for the action whose work instructions contain the given workInstructionId.
     */
    private Integer findAffectedContainerIndex(Map<UUID, Action> actions, long workInstructionId) {
        for (Action action : actions.values()) {
            for (WorkInstructionEvent wi : action.workInstructions()) {
                if (wi.workInstructionId() == workInstructionId) {
                    return action.containerIndex();
                }
            }
        }
        return null;
    }

    /**
     * Checks if TT_HANDOVER_FROM_QC for the given container is ACTIVE or COMPLETED.
     */
    private boolean isTTHandoverFromQCActivatedOrCompleted(long workQueueId, int containerIndex,
                                                            Map<UUID, Action> actions,
                                                            ScheduleContext context) {
        for (Map.Entry<UUID, Action> entry : actions.entrySet()) {
            Action action = entry.getValue();
            if (action.actionType() == ActionType.TT_HANDOVER_FROM_QC
                    && action.containerIndex() == containerIndex) {
                ActionStatus status = context.getActionStatus(workQueueId, entry.getKey());
                return status == ActionStatus.ACTIVE
                        || status == ActionStatus.COMPLETED;
            }
        }
        return false;
    }

    /**
     * Completes all remaining (non-completed) actions for the affected container
     * and its twin companion.
     */
    private List<SideEffect> completeRemainingActions(long workQueueId, int affectedContainerIndex,
                                                       Map<UUID, Action> actions,
                                                       ScheduleContext context) {
        Set<Integer> containerIndices = new HashSet<>();
        containerIndices.add(affectedContainerIndex);
        findTwinContainerIndex(actions, affectedContainerIndex).ifPresent(containerIndices::add);

        List<SideEffect> sideEffects = new ArrayList<>();

        // Complete all remaining actions for these containers (all device types)
        for (Map.Entry<UUID, Action> entry : actions.entrySet()) {
            Action action = entry.getValue();
            if (!containerIndices.contains(action.containerIndex())) {
                continue;
            }
            ActionStatus status = context.getActionStatus(workQueueId, entry.getKey());
            if (status != ActionStatus.COMPLETED) {
                sideEffects.addAll(context.completeActionWithReason(
                        workQueueId, entry.getKey(), CompletionReason.WI_ABANDONED));
            }
        }

        logger.info("Completed " + sideEffects.size() + " actions with reason WI_ABANDONED"
                + " for containers " + containerIndices + " in workQueue " + workQueueId);
        return sideEffects;
    }

    /**
     * Resets all TT actions for the given container index by clearing truck assignment
     * and moving them back to pending.
     */
    private List<SideEffect> resetTTActionsForContainer(long workQueueId, int containerIndex,
                                                         Map<UUID, Action> actions,
                                                         ScheduleContext context) {
        List<SideEffect> sideEffects = new ArrayList<>();

        for (Map.Entry<UUID, Action> entry : actions.entrySet()) {
            Action action = entry.getValue();
            if (action.deviceType() == DeviceType.TT
                    && action.containerIndex() == containerIndex
                    && action.cheShortName() != null) {
                sideEffects.addAll(context.resetTTAction(workQueueId, entry.getKey()));
            }
        }

        logger.info("Reset " + sideEffects.size() + " TT actions for container " + containerIndex
                + " in workQueue " + workQueueId);
        return sideEffects;
    }

    /**
     * Finds the twin container index. First tries twinCompanionWorkInstruction,
     * then falls back to shared cheShortName on TT actions with adjacent containerIndex.
     */
    private Optional<Integer> findTwinContainerIndex(Map<UUID, Action> actions, int containerIndex) {
        // Try twinCompanionWorkInstruction first
        Set<Long> twinWiIds = new HashSet<>();
        for (Action action : actions.values()) {
            if (action.containerIndex() == containerIndex) {
                for (WorkInstructionEvent wi : action.workInstructions()) {
                    if (wi.twinCompanionWorkInstruction() != 0) {
                        twinWiIds.add(wi.twinCompanionWorkInstruction());
                    }
                }
            }
        }
        if (!twinWiIds.isEmpty()) {
            for (Action action : actions.values()) {
                if (action.containerIndex() != containerIndex) {
                    for (WorkInstructionEvent wi : action.workInstructions()) {
                        if (twinWiIds.contains(wi.workInstructionId())) {
                            return Optional.of(action.containerIndex());
                        }
                    }
                }
            }
        }
        // Fallback: adjacent container with same cheShortName (twin in same takt)
        String cheShortName = null;
        for (Action action : actions.values()) {
            if (action.containerIndex() == containerIndex && action.cheShortName() != null) {
                cheShortName = action.cheShortName();
                break;
            }
        }
        if (cheShortName != null) {
            for (Action action : actions.values()) {
                if (action.containerIndex() != containerIndex
                        && Math.abs(action.containerIndex() - containerIndex) == 1
                        && cheShortName.equals(action.cheShortName())) {
                    return Optional.of(action.containerIndex());
                }
            }
        }
        return Optional.empty();
    }
}
