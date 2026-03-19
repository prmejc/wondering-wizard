package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionStatus;
import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.CompletionReason;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.ContainerMoveStateEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Handles ContainerMoveState STOPPED/ERROR events within the schedule context.
 * <p>
 * When a container move is stopped with an error while a TT is assigned, the TT
 * must be un-assigned. The behavior follows the same pivot logic as
 * {@link TTUnavailableHandler}:
 * <ul>
 *   <li><b>Before TT handover from QC:</b> Reset TT actions to pending and
 *       attempt to allocate a new truck.</li>
 *   <li><b>After TT handover from QC:</b> Ignore the event (the container
 *       is already committed to this truck).</li>
 * </ul>
 * <p>
 * Only processes events matching: containerMoveAction=STOPPED,
 * containerMoveStateRequestStatus=ERROR, responseContainerMoveState=TT_ASSIGNED.
 */
public class ContainerMoveStoppedHandler implements ScheduleSubProcessor {

    private static final Logger logger = Logger.getLogger(ContainerMoveStoppedHandler.class.getName());

    private static final String ACTION_STOPPED = "STOPPED";
    private static final String STATUS_ERROR = "ERROR";
    private static final String STATE_TT_ASSIGNED = "TT_ASSIGNED";

    @Override
    public List<SideEffect> process(Event event, ScheduleContext context) {
        if (!(event instanceof ContainerMoveStateEvent cms)) {
            return List.of();
        }
        if (!ACTION_STOPPED.equals(cms.containerMoveAction())
                || !STATUS_ERROR.equals(cms.containerMoveStateRequestStatus())
                || !STATE_TT_ASSIGNED.equals(cms.responseContainerMoveState())) {
            return List.of();
        }

        String cheShortName = cms.carryCHEName();
        long workInstructionId = cms.workInstructionId();
        if (cheShortName == null || cheShortName.isBlank()) {
            return List.of();
        }

        logger.info("Processing ContainerMoveState STOPPED/ERROR for truck: " + cheShortName
                + ", workInstructionId: " + workInstructionId);

        List<SideEffect> sideEffects = new ArrayList<>();

        for (long workQueueId : context.getScheduleWorkQueueIds()) {
            sideEffects.addAll(handleForSchedule(workQueueId, cheShortName, workInstructionId, context));
        }

        return sideEffects;
    }

    private List<SideEffect> handleForSchedule(long workQueueId, String cheShortName,
                                                long workInstructionId, ScheduleContext context) {
        Map<UUID, Action> actions = context.getActions(workQueueId);

        // Find the action that matches both the truck and the work instruction
        Set<Integer> affectedContainerIndices = new HashSet<>();
        for (Action action : actions.values()) {
            if (!cheShortName.equals(action.cheShortName())) {
                continue;
            }
            boolean hasMatchingWI = action.workInstructions().stream()
                    .anyMatch(wi -> wi.workInstructionId() == workInstructionId);
            if (hasMatchingWI) {
                affectedContainerIndices.add(action.containerIndex());
            }
        }

        if (affectedContainerIndices.isEmpty()) {
            return List.of();
        }

        List<SideEffect> sideEffects = new ArrayList<>();

        for (int containerIndex : affectedContainerIndices) {
            boolean handoverStarted = isTTHandoverFromQCActivatedOrCompleted(
                    workQueueId, containerIndex, actions, context);

            if (handoverStarted) {
                // After pivot: ignore — truck is committed
                logger.info("Ignoring ContainerMoveState STOPPED for container " + containerIndex
                        + " in workQueue " + workQueueId + " — past TT handover pivot");
            } else {
                // Before pivot: reset TT actions and try to allocate a new truck
                sideEffects.addAll(resetTTActionsForContainer(
                        workQueueId, containerIndex, actions, context));
            }
        }

        // After resetting, try to activate eligible actions (allocate new truck)
        if (!sideEffects.isEmpty()) {
            sideEffects.addAll(context.tryActivateEligibleActions(workQueueId));
        }

        return sideEffects;
    }

    private boolean isTTHandoverFromQCActivatedOrCompleted(long workQueueId, int containerIndex,
                                                            Map<UUID, Action> actions,
                                                            ScheduleContext context) {
        for (Map.Entry<UUID, Action> entry : actions.entrySet()) {
            Action action = entry.getValue();
            if (action.actionType() == ActionType.TT_HANDOVER_FROM_QC
                    && action.containerIndex() == containerIndex) {
                ActionStatus status = context.getActionStatus(workQueueId, entry.getKey());
                return status == ActionStatus.ACTIVE || status == ActionStatus.COMPLETED;
            }
        }
        return false;
    }

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
                + " in workQueue " + workQueueId + " due to ContainerMoveState STOPPED");
        return sideEffects;
    }
}
