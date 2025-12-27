package com.wonderingwizard.sideeffects;

import com.wonderingwizard.engine.SideEffect;

import java.time.Instant;
import java.util.UUID;

/**
 * Side effect produced when an action is activated and ready to be executed.
 * <p>
 * This side effect is generated when:
 * - A schedule starts and the current time is past the estimatedMoveTime of the first work instruction
 * - A previous action is completed and the next action in the sequence is activated
 *
 * @param actionId the UUID of the activated action
 * @param workQueueId the work queue this action belongs to
 * @param taktName the name of the takt containing this action (e.g., "TAKT100")
 * @param actionDescription the description of the action (e.g., "QC lift container from truck")
 * @param activatedAt the timestamp when the action was activated
 */
public record ActionActivated(
        UUID actionId,
        String workQueueId,
        String taktName,
        String actionDescription,
        Instant activatedAt
) implements SideEffect {

    @Override
    public String toString() {
        return "ActionActivated[actionId=" + actionId +
                ", workQueueId=" + workQueueId +
                ", taktName=" + taktName +
                ", actionDescription=" + actionDescription +
                ", activatedAt=" + activatedAt + "]";
    }
}
