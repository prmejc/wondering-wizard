package com.wonderingwizard.domain.takt;

/**
 * Condition based on equipment position occupancy.
 * <p>
 * Two modes:
 * <ul>
 *   <li>{@link ConditionMode#ACTIVATE}: position must be free for the action to activate
 *       (blocks entry when occupied, e.g., "don't drive to standby if standby is occupied")</li>
 *   <li>{@link ConditionMode#SKIP}: action should be skipped when position is free
 *       (skip ahead, e.g., "skip pull if standby is free")</li>
 * </ul>
 *
 * @param deviceType QC or RTG
 * @param position the position to check
 * @param conditionMode ACTIVATE (block if occupied) or SKIP (skip if free)
 */
public record LocationFreeCondition(
        DeviceType deviceType,
        EquipmentPosition position,
        ConditionMode conditionMode
) implements ActionCondition {

    /** Convenience: SKIP mode — skip this action when the target position is free. */
    public static LocationFreeCondition skipIfFree(DeviceType deviceType, EquipmentPosition position) {
        return new LocationFreeCondition(deviceType, position, ConditionMode.SKIP);
    }

    /** Convenience: ACTIVATE mode — only activate when the target position is free. */
    public static LocationFreeCondition blockIfOccupied(DeviceType deviceType, EquipmentPosition position) {
        return new LocationFreeCondition(deviceType, position, ConditionMode.ACTIVATE);
    }

    @Override
    public String id() {
        String prefix = conditionMode == ConditionMode.SKIP ? "location-skip:" : "location-block:";
        return prefix + deviceType.name() + ":" + position.name();
    }

    /**
     * For ACTIVATE mode: returns true when the position is FREE (action can proceed).
     * For SKIP mode: returns true when the position is FREE (action should be skipped).
     * In both cases, "satisfied" means "the position is free".
     */
    @Override
    public boolean evaluate(ActionConditionContext context) {
        String suffix = ":" + position.name();
        String prefix = deviceType.name() + ":";
        for (String key : context.occupiedPositions()) {
            if (key.startsWith(prefix) && key.endsWith(suffix)) {
                return false; // position is occupied
            }
        }
        return true; // position is free
    }

    @Override
    public String explanation(ActionConditionContext context) {
        boolean free = evaluate(context);
        String posName = deviceType.name() + " " + position.name().toLowerCase();
        if (conditionMode == ConditionMode.ACTIVATE) {
            return free ? posName + " is free" : posName + " is occupied — waiting";
        } else {
            return free ? posName + " is free — skipping ahead" : posName + " is occupied";
        }
    }

    @Override
    public String type() {
        return conditionMode == ConditionMode.ACTIVATE ? "LOCATION_BLOCK" : "LOCATION_SKIP";
    }

    @Override
    public ConditionMode mode() {
        return conditionMode;
    }
}
