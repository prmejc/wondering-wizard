package com.wonderingwizard.sideeffects;

import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.WorkInstructionEvent;

import java.util.List;
import java.util.UUID;

/**
 * Side effect indicating that a terminal truck has been assigned to a TT action.
 *
 * @param actionId the UUID of the action the truck was assigned to
 * @param workQueueId the work queue this action belongs to
 * @param cheShortName the short name of the assigned truck
 * @param cheId the CHE identifier of the assigned truck (nullable)
 * @param workInstructions the work instructions associated with this action
 */
public record TruckAssigned(
        UUID actionId,
        long workQueueId,
        String cheShortName,
        Long cheId,
        List<WorkInstructionEvent> workInstructions
) implements SideEffect {

    /**
     * Backward-compatible constructor without workInstructions.
     */
    public TruckAssigned(UUID actionId, long workQueueId, String cheShortName, Long cheId) {
        this(actionId, workQueueId, cheShortName, cheId, List.of());
    }

    @Override
    public String toString() {
        return "TruckAssigned[actionId=" + actionId
                + ", workQueueId=" + workQueueId
                + ", cheShortName=" + cheShortName
                + ", cheId=" + cheId + "]";
    }
}
