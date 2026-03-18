package com.wonderingwizard.sideeffects;

import com.wonderingwizard.domain.takt.CompletionReason;
import com.wonderingwizard.engine.SideEffect;

import java.time.Instant;
import java.util.UUID;

/**
 * Side effect produced when an action has been completed.
 * <p>
 * This side effect is generated in response to an ActionCompletedEvent
 * when the action ID matches the currently active action, or when an action
 * is force-completed due to external conditions (e.g., TT becoming unavailable).
 *
 * @param actionId the UUID of the completed action
 * @param workQueueId the work queue this action belongs to
 * @param taktName the name of the takt containing this action (e.g., "TAKT100")
 * @param actionDescription the description of the action (e.g., "QC lift container from truck")
 * @param completedAt the timestamp when the action was completed
 * @param reason the reason for completion, or null if completed normally
 */
public record ActionCompleted(
        UUID actionId,
        long workQueueId,
        String taktName,
        String actionDescription,
        Instant completedAt,
        CompletionReason reason
) implements SideEffect {

    /**
     * Constructor for normal completion (no reason).
     */
    public ActionCompleted(UUID actionId, long workQueueId, String taktName,
                           String actionDescription, Instant completedAt) {
        this(actionId, workQueueId, taktName, actionDescription, completedAt, null);
    }

    @Override
    public String toString() {
        return "ActionCompleted[actionId=" + actionId +
                ", workQueueId=" + workQueueId +
                ", taktName=" + taktName +
                ", actionDescription=" + actionDescription +
                ", completedAt=" + completedAt +
                (reason != null ? ", reason=" + reason : "") + "]";
    }
}
