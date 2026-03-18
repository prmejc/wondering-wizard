package com.wonderingwizard.sideeffects;

import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.ContainerHandlingEquipmentEvent;

/**
 * Side effect indicating that a terminal truck's state has been updated.
 *
 * @param cheShortName the short name of the truck that was updated
 * @param event the full CHE event that caused the update
 */
public record TTStateUpdated(
        String cheShortName,
        ContainerHandlingEquipmentEvent event
) implements SideEffect {

    @Override
    public String toString() {
        return "TTStateUpdated[cheShortName=" + cheShortName
                + ", cheStatus=" + event.cheStatus()
                + ", cheJobStepState=" + event.cheJobStepState() + "]";
    }
}
