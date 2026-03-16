package com.wonderingwizard.sideeffects;

import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.WorkInstructionEvent;

/**
 * Side effect and event indicating that a work instruction has been reassigned
 * from one work queue to another during a cross-queue FETCH_COMPLETE swap.
 * <p>
 * When a FETCH_COMPLETE arrives for a WI that moved from another queue,
 * the displaced expected WI is reassigned to the source queue.
 * This record implements both SideEffect (produced by WorkQueueProcessor) and Event
 * (re-processed as a new step so the reassignment is visible in the event log).
 *
 * @param workInstruction the reassigned work instruction with its new workQueueId
 */
public record WorkInstructionReassigned(
        WorkInstructionEvent workInstruction
) implements SideEffect, Event {
}
