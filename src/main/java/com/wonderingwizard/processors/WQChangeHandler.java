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
 * Handles WQ Change events within the schedule context.
 * <p>
 * When a work queue change event arrives, all remaining actions for the affected
 * container and its twin are completed with reason {@link CompletionReason#WQ_CHANGE}.
 * There is no boundary — cancellation always applies regardless of workflow progress.
 */
public class WQChangeHandler implements ScheduleSubProcessor {

    private static final Logger logger = Logger.getLogger(WQChangeHandler.class.getName());

    @Override
    public List<SideEffect> process(Event event, ScheduleContext context) {
        if (!(event instanceof WorkInstructionEvent wiEvent)) {
            return List.of();
        }
        if (!EventType.WQ_CHANGE.equals(wiEvent.eventType())) {
            return List.of();
        }

        long workInstructionId = wiEvent.workInstructionId();
        logger.info("Processing WQ Change for workInstructionId: " + workInstructionId);

        List<SideEffect> sideEffects = new ArrayList<>();

        for (long workQueueId : context.getScheduleWorkQueueIds()) {
            sideEffects.addAll(handleWQChangeForSchedule(workQueueId, workInstructionId, context));
        }

        return sideEffects;
    }

    private List<SideEffect> handleWQChangeForSchedule(long workQueueId, long workInstructionId,
                                                        ScheduleContext context) {
        Map<UUID, Action> actions = context.getActions(workQueueId);

        Integer affectedContainerIndex = findAffectedContainerIndex(actions, workInstructionId);
        if (affectedContainerIndex == null) {
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
                        workQueueId, entry.getKey(), CompletionReason.WQ_CHANGE));
            }
        }

        logger.info("Completed " + sideEffects.size() + " actions with reason WQ_CHANGE"
                + " for containers " + containerIndices + " in workQueue " + workQueueId);
        return sideEffects;
    }
}
