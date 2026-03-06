package com.wonderingwizard.domain.takt;

/**
 * Template for a device action that defines the action properties before instantiation.
 * Used to specify the sequence and timing of actions for multi-device scheduling.
 *
 * <p>Actions form a linked sequence where {@code isFirstInTaktForDevice} marks takt boundaries.
 * When true, this action starts a new takt, and all previous actions must be in earlier takts.
 *
 * <p>The optional {@code crossDeviceDependency} field specifies that this action additionally
 * depends on the most recent action of the specified device type. This is used to model
 * handover synchronization points between devices (e.g., TT "handover from RTG" depends on
 * the last RTG action "rtg handover to TT").
 *
 * @param deviceType the type of device performing this action
 * @param description the action description
 * @param isFirstInTaktForDevice true if this action must be the first for this device in a takt
 *                                (all previous actions go in earlier takts)
 * @param crossDeviceDependency optional device type whose most recent action this action depends on
 * @param durationSeconds the duration of this action in seconds (default 30)
 */
public record DeviceActionTemplate(
        DeviceType deviceType,
        String description,
        boolean isFirstInTaktForDevice,
        DeviceType crossDeviceDependency,
        int durationSeconds
) {
    public static final int DEFAULT_DURATION_SECONDS = 30;

    /**
     * Creates a new template for an action without cross-device dependency.
     */
    public static DeviceActionTemplate of(
            DeviceType deviceType,
            String description,
            boolean isFirstInTaktForDevice
    ) {
        return new DeviceActionTemplate(deviceType, description, isFirstInTaktForDevice, null, DEFAULT_DURATION_SECONDS);
    }

    /**
     * Creates a new template for an action with an optional cross-device dependency.
     */
    public static DeviceActionTemplate of(
            DeviceType deviceType,
            String description,
            boolean isFirstInTaktForDevice,
            DeviceType crossDeviceDependency
    ) {
        return new DeviceActionTemplate(deviceType, description, isFirstInTaktForDevice, crossDeviceDependency, DEFAULT_DURATION_SECONDS);
    }

    /**
     * Creates a new template with a custom duration.
     */
    public static DeviceActionTemplate of(
            DeviceType deviceType,
            String description,
            boolean isFirstInTaktForDevice,
            int durationSeconds
    ) {
        return new DeviceActionTemplate(deviceType, description, isFirstInTaktForDevice, null, durationSeconds);
    }
}
