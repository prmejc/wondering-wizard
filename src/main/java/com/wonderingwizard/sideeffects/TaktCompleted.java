package com.wonderingwizard.sideeffects;

import com.wonderingwizard.engine.SideEffect;

import java.time.Instant;

/**
 * Side effect produced when a takt transitions from Active to Completed.
 * <p>
 * A takt becomes completed when all actions within it have been completed.
 *
 * @param workQueueId the work queue this takt belongs to
 * @param taktName the name of the takt (e.g., "TAKT100")
 * @param completedAt the timestamp when the takt was completed
 */
public record TaktCompleted(
        String workQueueId,
        String taktName,
        Instant completedAt
) implements SideEffect {

    @Override
    public String toString() {
        return "TaktCompleted[workQueueId=" + workQueueId +
                ", taktName=" + taktName +
                ", completedAt=" + completedAt + "]";
    }
}
