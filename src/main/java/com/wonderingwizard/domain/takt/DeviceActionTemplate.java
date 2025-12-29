package com.wonderingwizard.domain.takt;

/**
 * Template for a device action that defines the action properties before instantiation.
 * Used to specify the sequence and timing of actions for multi-device scheduling.
 *
 * <p>Actions form a linked sequence where {@code isFirstInTaktForDevice} marks takt boundaries.
 * When true, this action starts a new takt, and all previous actions must be in earlier takts.
 *
 * @param deviceType the type of device performing this action
 * @param description the action description
 * @param isFirstInTaktForDevice true if this action must be the first for this device in a takt
 *                                (all previous actions go in earlier takts)
 */
public record DeviceActionTemplate(
        DeviceType deviceType,
        String description,
        boolean isFirstInTaktForDevice
) {
    /**
     * Creates a new template for an action.
     *
     * @param deviceType the device type
     * @param description the action description
     * @param isFirstInTaktForDevice whether this action starts a new takt for the device
     * @return a new DeviceActionTemplate
     */
    public static DeviceActionTemplate of(
            DeviceType deviceType,
            String description,
            boolean isFirstInTaktForDevice
    ) {
        return new DeviceActionTemplate(deviceType, description, isFirstInTaktForDevice);
    }
}
