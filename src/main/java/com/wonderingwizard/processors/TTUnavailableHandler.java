package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionStatus;
import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.CompletionReason;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.CheStatus;
import com.wonderingwizard.events.ContainerHandlingEquipmentEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Handles TT (terminal truck) unavailability events within the schedule context.
 * <p>
 * When a truck becomes unavailable, the behavior depends on whether the truck
 * has already gone under the QC (quay crane):
 * <ul>
 *   <li><b>Before TT under QC is activated:</b> All TT actions for the affected container
 *       are reset to pending (truck unassigned), and the system attempts to allocate a new truck.</li>
 *   <li><b>After TT under QC is activated or completed:</b> All remaining actions for the
 *       affected container and its twin container are completed with reason
 *       {@link CompletionReason#TT_UNAVAILABLE}.</li>
 * </ul>
 */
public class TTUnavailableHandler implements ScheduleSubProcessor {

    private static final Logger logger = Logger.getLogger(TTUnavailableHandler.class.getName());
    private static final String CHE_KIND_TT = "TT";

    @Override
    public List<SideEffect> process(Event event, ScheduleContext context) {
        if (!(event instanceof ContainerHandlingEquipmentEvent cheEvent)) {
            return List.of();
        }
        if (!CHE_KIND_TT.equals(cheEvent.cheKind()) || cheEvent.cheStatus() != CheStatus.UNAVAILABLE) {
            return List.of();
        }

        String cheShortName = cheEvent.cheShortName();
        if (cheShortName == null || cheShortName.isBlank()) {
            return List.of();
        }

        logger.info("Processing TT unavailable for truck: " + cheShortName);

        List<SideEffect> sideEffects = new ArrayList<>();

        for (long workQueueId : context.getScheduleWorkQueueIds()) {
            sideEffects.addAll(handleTTUnavailableForSchedule(workQueueId, cheShortName, context));
        }

        return sideEffects;
    }

    private List<SideEffect> handleTTUnavailableForSchedule(long workQueueId, String cheShortName,
                                                             ScheduleContext context) {
        Map<UUID, Action> actions = context.getActions(workQueueId);

        // Find all container indices that have this truck assigned
        Set<Integer> affectedContainerIndices = new HashSet<>();
        for (Action action : actions.values()) {
            if (cheShortName.equals(action.cheShortName())) {
                affectedContainerIndices.add(action.containerIndex());
            }
        }

        if (affectedContainerIndices.isEmpty()) {
            return List.of();
        }

        List<SideEffect> sideEffects = new ArrayList<>();

        for (int containerIndex : affectedContainerIndices) {
            boolean ttUnderQCActivated = isTTUnderQCActivatedOrCompleted(
                    workQueueId, containerIndex, actions, context);

            if (ttUnderQCActivated) {
                sideEffects.addAll(completeRemainingActions(
                        workQueueId, cheShortName, actions, context));
                // After completing actions for all affected containers, cascade once
                sideEffects.addAll(context.cascadeTaktCompletion(workQueueId));
                return sideEffects;
            } else {
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

    /**
     * Checks if the TT_DRIVE_UNDER_QC action for the given container is ACTIVE or COMPLETED.
     */
    private boolean isTTUnderQCActivatedOrCompleted(long workQueueId, int containerIndex,
                                                     Map<UUID, Action> actions,
                                                     ScheduleContext context) {
        for (Map.Entry<UUID, Action> entry : actions.entrySet()) {
            Action action = entry.getValue();
            if (action.actionType() == ActionType.TT_DRIVE_UNDER_QC
                    && action.containerIndex() == containerIndex) {
                ActionStatus status =
                        context.getActionStatus(workQueueId, entry.getKey());
                return status == ActionStatus.ACTIVE
                        || status == ActionStatus.COMPLETED;
            }
        }
        return false;
    }

    /**
     * Completes all remaining (non-completed) actions for all containers sharing
     * the given truck assignment (handles both the container and its twin).
     */
    private List<SideEffect> completeRemainingActions(long workQueueId, String cheShortName,
                                                       Map<UUID, Action> actions,
                                                       ScheduleContext context) {
        List<SideEffect> sideEffects = new ArrayList<>();

        // Find all container indices sharing this truck (container + twin)
        Set<Integer> containerIndices = new HashSet<>();
        for (Action action : actions.values()) {
            if (cheShortName.equals(action.cheShortName())) {
                containerIndices.add(action.containerIndex());
            }
        }

        // Complete all remaining actions for these containers (all device types)
        for (Map.Entry<UUID, Action> entry : actions.entrySet()) {
            Action action = entry.getValue();
            if (!containerIndices.contains(action.containerIndex())) {
                continue;
            }
            ActionStatus status =
                    context.getActionStatus(workQueueId, entry.getKey());
            if (status != ActionStatus.COMPLETED) {
                sideEffects.addAll(context.completeActionWithReason(
                        workQueueId, entry.getKey(), CompletionReason.TT_UNAVAILABLE));
            }
        }

        logger.info("Completed " + sideEffects.size() + " actions with reason TT_UNAVAILABLE"
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
}
