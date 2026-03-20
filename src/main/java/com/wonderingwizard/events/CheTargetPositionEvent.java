package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

/**
 * Event representing a CHE target position confirmation.
 * Sent by a truck to confirm it has arrived at the instructed destination.
 *
 * @param equipmentInstructionId the UUID of the equipment instruction being confirmed
 * @param cheShortName the short name of the CHE confirming position
 * @param confirmedMapNodeName the confirmed map node name
 * @param terminalCode the terminal code
 */
public record CheTargetPositionEvent(
        String equipmentInstructionId,
        String cheShortName,
        String confirmedMapNodeName,
        String terminalCode
) implements Event {

    @Override
    public String toString() {
        return "CheTargetPositionEvent[equipmentInstructionId=" + equipmentInstructionId +
                ", cheShortName=" + cheShortName +
                ", confirmedMapNodeName=" + confirmedMapNodeName + "]";
    }
}
