package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.ActionStatus;
import com.wonderingwizard.domain.takt.CompletionReason;
import com.wonderingwizard.engine.SideEffect;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Provides controlled access to schedule state for {@link ScheduleSubProcessor} implementations.
 * <p>
 * This interface exposes only the operations that sub-processors need, keeping the internal
 * schedule state encapsulated within {@link ScheduleRunnerProcessor}.
 */
public interface ScheduleContext {

    /**
     * Returns the IDs of all active work queues that have schedules.
     */
    Set<Long> getScheduleWorkQueueIds();

    /**
     * Returns all actions in a schedule, keyed by action ID.
     */
    Map<UUID, Action> getActions(long workQueueId);

    /**
     * Returns the takt name for a given action.
     */
    String getTaktName(long workQueueId, UUID actionId);

    /**
     * Returns the current status of an action.
     */
    ActionStatus getActionStatus(long workQueueId, UUID actionId);

    /**
     * Marks an action as completed with a reason, updating the action record and schedule state.
     * Returns the side effects produced (ActionCompleted).
     */
    List<SideEffect> completeActionWithReason(long workQueueId, UUID actionId, CompletionReason reason);

    /**
     * Resets a TT action to pending by clearing its truck assignment and removing it from active state.
     * Returns the side effects produced (TruckUnassigned).
     */
    List<SideEffect> resetTTAction(long workQueueId, UUID actionId);

    /**
     * Triggers takt completion cascade and takt activation for a schedule.
     * Should be called after completing actions to propagate state changes.
     */
    List<SideEffect> cascadeTaktCompletion(long workQueueId);

    /**
     * Triggers re-evaluation of eligible actions in all active takts.
     * Should be called after resetting actions to attempt new truck allocation.
     */
    List<SideEffect> tryActivateEligibleActions(long workQueueId);

    /**
     * Returns the current time from the latest TimeEvent.
     */
    Instant getCurrentTime();
}
