package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

/**
 * Event representing a container move state response.
 * Consumed from the {@code apmt.terminaloperations.containermovestate} topic.
 *
 * @param containerMoveAction the move action (e.g. "STOPPED")
 * @param containerMoveStateRequestStatus the request status (e.g. "ERROR")
 * @param responseContainerMoveState the response state (e.g. "TT_ASSIGNED")
 * @param carryCHEName the carry CHE name (truck short name)
 * @param workInstructionId the work instruction ID
 * @param moveKind the move kind
 * @param containerId the container ID
 * @param terminalCode the terminal code
 * @param errorMessage the error message
 * @param sourceTsMs the source timestamp in milliseconds
 */
public record ContainerMoveStateEvent(
        String containerMoveAction,
        String containerMoveStateRequestStatus,
        String responseContainerMoveState,
        String carryCHEName,
        long workInstructionId,
        String moveKind,
        String containerId,
        String terminalCode,
        String errorMessage,
        long sourceTsMs
) implements Event {}
