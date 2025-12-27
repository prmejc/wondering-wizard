package com.wonderingwizard.sideeffects;

import com.wonderingwizard.engine.SideEffect;

import java.time.Instant;
import java.util.UUID;

/**
 * Side effect produced when an action has been completed.
 * <p>
 * This side effect is generated in response to an ActionCompletedEvent
 * when the action ID matches the currently active action.
 *
 * @param actionId the UUID of the completed action
 * @param workQueueId the work queue this action belongs to
 * @param taktName the name of the takt containing this action (e.g., "TAKT100")
 * @param actionDescription the description of the action (e.g., "QC lift container from truck")
 * @param completedAt the timestamp when the action was completed
 */
public record ActionCompleted(
        UUID actionId,
        String workQueueId,
        String taktName,
        String actionDescription,
        Instant completedAt
) implements SideEffect {

    @Override
    public String toString() {
        return "ActionCompleted[actionId=" + actionId +
                ", workQueueId=" + workQueueId +
                ", taktName=" + taktName +
                ", actionDescription=" + actionDescription +
                ", completedAt=" + completedAt + "]";
    }
}
