package com.wonderingwizard.sideeffects;

import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.SideEffect;

import java.time.Instant;

/**
 * Side effect and event produced when a takt transitions from Waiting to Active.
 * <p>
 * A takt becomes active when:
 * - The previous takt is completed (or this is the first takt)
 * - The current time is at or past the takt's start time
 * <p>
 * Implements Event so that downstream processors (e.g., DelayProcessor) can
 * react to takt activations via the EventPropagatingEngine's BFS mechanism.
 *
 * @param workQueueId the work queue this takt belongs to
 * @param taktName the name of the takt (e.g., "TAKT100")
 * @param activatedAt the timestamp when the takt was activated
 */
public record TaktActivated(
        String workQueueId,
        String taktName,
        Instant activatedAt
) implements SideEffect, Event {

    @Override
    public String toString() {
        return "TaktActivated[workQueueId=" + workQueueId +
                ", taktName=" + taktName +
                ", activatedAt=" + activatedAt + "]";
    }
}
