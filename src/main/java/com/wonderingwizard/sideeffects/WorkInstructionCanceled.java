package com.wonderingwizard.sideeffects;

import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.SideEffect;

/**
 * Side effect and event indicating that a work instruction's actions have been
 * force-completed (e.g., due to TT becoming unavailable).
 * <p>
 * This propagates as an Event so that {@link com.wonderingwizard.processors.WorkQueueProcessor}
 * can exclude canceled work instructions from the "expected next" calculation,
 * preventing spurious reschedules when QC Discharged Container events arrive
 * for canceled containers.
 *
 * @param workQueueId the work queue this work instruction belongs to
 * @param workInstructionId the canceled work instruction ID
 */
public record WorkInstructionCanceled(
        long workQueueId,
        long workInstructionId
) implements SideEffect, Event {
}
