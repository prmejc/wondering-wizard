package com.wonderingwizard.domain.takt;

import java.util.Set;
import java.util.UUID;

/**
 * Context for evaluating {@link ActionCondition}s.
 *
 * @param taktActive whether the action's parent takt is currently active
 * @param completedActionIds the set of action IDs that have been completed
 * @param satisfiedEventGateIds the set of satisfied event gate condition IDs for the action being evaluated
 * @param occupiedPositions the set of occupied position keys (e.g., "QC:QCZ1:STANDBY", "RTG:RTZ01:UNDER")
 */
public record ActionConditionContext(boolean taktActive, Set<UUID> completedActionIds,
                                      Set<String> satisfiedEventGateIds,
                                      Set<String> occupiedPositions) {

    public ActionConditionContext(boolean taktActive, Set<UUID> completedActionIds,
                                  Set<String> satisfiedEventGateIds) {
        this(taktActive, completedActionIds, satisfiedEventGateIds, Set.of());
    }

    public ActionConditionContext(boolean taktActive, Set<UUID> completedActionIds) {
        this(taktActive, completedActionIds, Set.of(), Set.of());
    }

    /**
     * Returns whether a specific equipment position is currently occupied.
     */
    public boolean isPositionOccupied(DeviceType deviceType, String equipmentName, EquipmentPosition position) {
        return occupiedPositions.contains(positionKey(deviceType, equipmentName, position));
    }

    /**
     * Creates a position key for the occupancy set.
     */
    public static String positionKey(DeviceType deviceType, String equipmentName, EquipmentPosition position) {
        return deviceType.name() + ":" + equipmentName + ":" + position.name();
    }
}
