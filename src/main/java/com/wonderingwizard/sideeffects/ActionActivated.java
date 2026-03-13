package com.wonderingwizard.sideeffects;

import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.WorkInstructionEvent;

import java.time.Instant;
import java.util.List;
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
 * @param actionType the type-safe action identifier for compile-time checked mapping
 * @param actionDescription the description of the action (e.g., "QC Lift1")
 * @param activatedAt the timestamp when the action was activated
 * @param deviceType the type of CHE device performing this action
 * @param workInstructions the work instructions associated with this action
 */
public record ActionActivated(
        UUID actionId,
        long workQueueId,
        String taktName,
        ActionType actionType,
        String actionDescription,
        Instant activatedAt,
        DeviceType deviceType,
        List<WorkInstructionEvent> workInstructions
) implements SideEffect {

    /**
     * Backward-compatible constructor without actionType, deviceType and workInstructions.
     */
    public ActionActivated(UUID actionId, long workQueueId, String taktName,
                           String actionDescription, Instant activatedAt) {
        this(actionId, workQueueId, taktName, null, actionDescription, activatedAt, null, List.of());
    }

    @Override
    public String toString() {
        return "ActionActivated[actionId=" + actionId +
                ", workQueueId=" + workQueueId +
                ", taktName=" + taktName +
                ", actionType=" + actionType +
                ", actionDescription=" + actionDescription +
                ", activatedAt=" + activatedAt +
                ", deviceType=" + deviceType +
                ", workInstructions=" + workInstructions + "]";
    }
}
