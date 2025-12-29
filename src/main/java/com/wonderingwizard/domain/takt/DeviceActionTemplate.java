package com.wonderingwizard.domain.takt;

/**
 * Template for a device action that defines the action properties before instantiation.
 * Used to specify the sequence and timing of actions for multi-device scheduling.
 *
 * @param deviceType the type of device performing this action
 * @param description the action description
 * @param taktOffset the offset from the container's base takt (0 = same takt, -1 = one takt earlier, etc.)
 * @param isLastInTaktForDevice true if this is the last action for this device in this takt
 */
public record DeviceActionTemplate(
        DeviceType deviceType,
        String description,
        int taktOffset,
        boolean isLastInTaktForDevice
) {
    /**
     * Creates a new template for an action.
     *
     * @param deviceType the device type
     * @param description the action description
     * @param taktOffset the takt offset from container's base takt
     * @param isLastInTaktForDevice whether this is the last action for the device in this takt
     * @return a new DeviceActionTemplate
     */
    public static DeviceActionTemplate of(
            DeviceType deviceType,
            String description,
            int taktOffset,
            boolean isLastInTaktForDevice
    ) {
        return new DeviceActionTemplate(deviceType, description, taktOffset, isLastInTaktForDevice);
    }
}
