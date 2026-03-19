package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

/**
 * Event representing a quay crane mapping update from the QuayCraneFlowPosition topic.
 *
 * @param quayCraneShortName the short name of the quay crane (e.g., "QCZ9")
 * @param vesselName the vessel name (nullable)
 * @param craneMode the crane mode (nullable)
 * @param lane the lane assignment (nullable)
 * @param standbyPositionName the standby position name (nullable)
 * @param standbyNodeName the standby node name (nullable)
 * @param standbyTrafficDirection the standby traffic direction (nullable)
 * @param loadPinningPositionName the load pinning position name (nullable)
 * @param dischargePinningPositionName the discharge pinning position name (nullable)
 * @param terminalCode the terminal code
 * @param timestampMs the event timestamp in milliseconds
 */
public record QuayCraneMappingEvent(
        String quayCraneShortName,
        String vesselName,
        String craneMode,
        String lane,
        String standbyPositionName,
        String standbyNodeName,
        String standbyTrafficDirection,
        String loadPinningPositionName,
        String dischargePinningPositionName,
        String terminalCode,
        long timestampMs
) implements Event {}
