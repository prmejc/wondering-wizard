package com.wonderingwizard.sideeffects;

import com.wonderingwizard.engine.SideEffect;

import java.util.UUID;

/**
 * Side effect indicating that a terminal truck has been unassigned from a TT action
 * due to the truck becoming unavailable before the TT under QC action was activated.
 *
 * @param actionId the UUID of the action the truck was unassigned from
 * @param workQueueId the work queue this action belongs to
 * @param cheShortName the short name of the unassigned truck
 */
public record TruckUnassigned(
        UUID actionId,
        long workQueueId,
        String cheShortName
) implements SideEffect {

    @Override
    public String toString() {
        return "TruckUnassigned[actionId=" + actionId
                + ", workQueueId=" + workQueueId
                + ", cheShortName=" + cheShortName + "]";
    }
}
