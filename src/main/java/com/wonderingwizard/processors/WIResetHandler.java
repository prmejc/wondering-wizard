package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionStatus;
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
 * Handles WI Reset events within the schedule context.
 * <p>
 * When a work instruction is reset, all remaining actions for the affected
 * container and its twin are completed with reason {@link CompletionReason#WI_RESET}.
 * Unlike WI Abandoned, there is no pivot point — the cancellation always applies
 * regardless of workflow progress.
 */
public class WIResetHandler implements ScheduleSubProcessor {

    private static final Logger logger = Logger.getLogger(WIResetHandler.class.getName());

    @Override
    public List<SideEffect> process(Event event, ScheduleContext context) {
        if (!(event instanceof WorkInstructionEvent wiEvent)) {
            return List.of();
        }
        if (!EventType.WI_RESET.equals(wiEvent.eventType())) {
            return List.of();
        }

        long workInstructionId = wiEvent.workInstructionId();
        logger.info("Processing WI Reset for workInstructionId: " + workInstructionId);

        List<SideEffect> sideEffects = new ArrayList<>();

        for (long workQueueId : context.getScheduleWorkQueueIds()) {
            sideEffects.addAll(handleWIResetForSchedule(workQueueId, workInstructionId, context));
        }

        return sideEffects;
    }

    private List<SideEffect> handleWIResetForSchedule(long workQueueId, long workInstructionId,
                                                       ScheduleContext context) {
        Map<UUID, Action> actions = context.getActions(workQueueId);

        // Find the container index affected by matching workInstructionId against actions' work instructions
        Integer affectedContainerIndex = findAffectedContainerIndex(actions, workInstructionId);
        if (affectedContainerIndex == null) {
            return List.of();
        }

        List<SideEffect> sideEffects = new ArrayList<>();
        sideEffects.addAll(completeRemainingActions(workQueueId, affectedContainerIndex, actions, context));
        sideEffects.addAll(context.cascadeTaktCompletion(workQueueId));
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
     * Completes all remaining (non-completed) actions for the affected container
     * and its twin (containers sharing the same cheShortName on TT actions).
     */
    private List<SideEffect> completeRemainingActions(long workQueueId, int affectedContainerIndex,
                                                       Map<UUID, Action> actions,
                                                       ScheduleContext context) {
        // Find the cheShortName used by TT actions for the affected container
        String cheShortName = null;
        for (Action action : actions.values()) {
            if (action.containerIndex() == affectedContainerIndex
                    && action.cheShortName() != null) {
                cheShortName = action.cheShortName();
                break;
            }
        }

        // Find all container indices sharing this truck (container + twin)
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
                        workQueueId, entry.getKey(), CompletionReason.WI_RESET));
            }
        }

        logger.info("Completed " + sideEffects.size() + " actions with reason WI_RESET"
                + " for containers " + containerIndices + " in workQueue " + workQueueId);
        return sideEffects;
    }
}
