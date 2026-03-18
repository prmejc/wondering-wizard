package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionStatus;
import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.CompletionReason;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.EventType;
import com.wonderingwizard.events.WorkInstructionEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Handles WI Reverted events within the schedule context.
 * <p>
 * When a work instruction is reverted, the behavior depends on whether
 * TT_DRIVE_UNDER_QC has been completed for the affected container:
 * <ul>
 *   <li><b>Before TT_DRIVE_UNDER_QC is completed:</b> All remaining actions for the
 *       affected container and its twin are completed with reason
 *       {@link CompletionReason#WI_REVERTED}.</li>
 *   <li><b>After TT_DRIVE_UNDER_QC is completed:</b> The event is ignored.</li>
 * </ul>
 */
public class WIRevertHandler implements ScheduleSubProcessor {

    private static final Logger logger = Logger.getLogger(WIRevertHandler.class.getName());

    @Override
    public List<SideEffect> process(Event event, ScheduleContext context) {
        if (!(event instanceof WorkInstructionEvent wiEvent)) {
            return List.of();
        }
        if (!EventType.WI_REVERTED.equals(wiEvent.eventType())) {
            return List.of();
        }

        long workInstructionId = wiEvent.workInstructionId();
        logger.info("Processing WI Reverted for workInstructionId: " + workInstructionId);

        List<SideEffect> sideEffects = new ArrayList<>();

        for (long workQueueId : context.getScheduleWorkQueueIds()) {
            sideEffects.addAll(handleWIRevertForSchedule(workQueueId, workInstructionId, context));
        }

        return sideEffects;
    }

    private List<SideEffect> handleWIRevertForSchedule(long workQueueId, long workInstructionId,
                                                        ScheduleContext context) {
        Map<UUID, Action> actions = context.getActions(workQueueId);

        Integer affectedContainerIndex = findAffectedContainerIndex(actions, workInstructionId);
        if (affectedContainerIndex == null) {
            return List.of();
        }

        if (isTTDriveUnderQCCompleted(workQueueId, affectedContainerIndex, actions, context)) {
            logger.info("TT_DRIVE_UNDER_QC already completed for container " + affectedContainerIndex
                    + " in workQueue " + workQueueId + ", ignoring WI Reverted");
            return List.of();
        }

        List<SideEffect> sideEffects = new ArrayList<>();
        sideEffects.addAll(completeRemainingActions(workQueueId, affectedContainerIndex, actions, context));
        sideEffects.addAll(context.cascadeTaktCompletion(workQueueId));
        return sideEffects;
    }

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
     * Checks if TT_DRIVE_UNDER_QC for the given container is COMPLETED.
     */
    private boolean isTTDriveUnderQCCompleted(long workQueueId, int containerIndex,
                                               Map<UUID, Action> actions,
                                               ScheduleContext context) {
        for (Map.Entry<UUID, Action> entry : actions.entrySet()) {
            Action action = entry.getValue();
            if (action.actionType() == ActionType.TT_DRIVE_UNDER_QC
                    && action.containerIndex() == containerIndex) {
                ActionStatus status = context.getActionStatus(workQueueId, entry.getKey());
                return status == ActionStatus.COMPLETED;
            }
        }
        return false;
    }

    private List<SideEffect> completeRemainingActions(long workQueueId, int affectedContainerIndex,
                                                       Map<UUID, Action> actions,
                                                       ScheduleContext context) {
        String cheShortName = null;
        for (Action action : actions.values()) {
            if (action.containerIndex() == affectedContainerIndex
                    && action.cheShortName() != null) {
                cheShortName = action.cheShortName();
                break;
            }
        }

        Set<Integer> containerIndices = new HashSet<>();
        containerIndices.add(affectedContainerIndex);
        if (cheShortName != null) {
            for (Action action : actions.values()) {
                if (cheShortName.equals(action.cheShortName())) {
                    containerIndices.add(action.containerIndex());
                }
            }
        }

        List<SideEffect> sideEffects = new ArrayList<>();

        for (Map.Entry<UUID, Action> entry : actions.entrySet()) {
            Action action = entry.getValue();
            if (!containerIndices.contains(action.containerIndex())) {
                continue;
            }
            ActionStatus status = context.getActionStatus(workQueueId, entry.getKey());
            if (status != ActionStatus.COMPLETED) {
                sideEffects.addAll(context.completeActionWithReason(
                        workQueueId, entry.getKey(), CompletionReason.WI_REVERTED));
            }
        }

        logger.info("Completed " + sideEffects.size() + " actions with reason WI_REVERTED"
                + " for containers " + containerIndices + " in workQueue " + workQueueId);
        return sideEffects;
    }
}
