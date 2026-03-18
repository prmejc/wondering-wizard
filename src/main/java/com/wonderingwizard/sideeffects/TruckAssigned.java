package com.wonderingwizard.sideeffects;

import com.wonderingwizard.engine.SideEffect;

import java.util.UUID;

/**
 * Side effect indicating that a terminal truck has been assigned to a TT action.
 *
 * @param actionId the UUID of the action the truck was assigned to
 * @param workQueueId the work queue this action belongs to
 * @param cheShortName the short name of the assigned truck
 * @param cheId the CHE identifier of the assigned truck (nullable)
 */
public record TruckAssigned(
        UUID actionId,
        long workQueueId,
        String cheShortName,
        Long cheId
) implements SideEffect {

    @Override
    public String toString() {
        return "TruckAssigned[actionId=" + actionId
                + ", workQueueId=" + workQueueId
                + ", cheShortName=" + cheShortName
                + ", cheId=" + cheId + "]";
    }
}
