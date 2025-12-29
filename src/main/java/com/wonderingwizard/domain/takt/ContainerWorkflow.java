package com.wonderingwizard.domain.takt;

import java.util.List;

import static com.wonderingwizard.domain.takt.DeviceType.*;

/**
 * Defines the standard workflow for moving a container from yard to vessel.
 * The workflow involves multiple devices (RTG, TT, QC) with actions distributed
 * across different takts relative to the container's base takt.
 *
 * <p>Takt offset timing:
 * <ul>
 *   <li>RTG actions occur 3 takts before the container's base takt</li>
 *   <li>TT drive under RTG and handover from RTG occur 2 takts before</li>
 *   <li>TT drive under QC and handover to QC occur 1 takt before</li>
 *   <li>QC actions occur in the container's base takt</li>
 * </ul>
 *
 * <p>The {@code isLastInTaktForDevice} flag indicates when an action is the final
 * action for a device within a specific takt. This is used to establish dependencies
 * between takts for the same device.
 */
public final class ContainerWorkflow {

    private ContainerWorkflow() {
        // Utility class
    }

    /**
     * The ordered list of action templates for the container workflow.
     * Actions are defined in the order they should be processed within each device.
     */
    public static final List<DeviceActionTemplate> ACTION_TEMPLATES = List.of(
            // RTG actions - 3 takts before container's base takt
            DeviceActionTemplate.of(RTG, "lift container from yard", -3, false),
            DeviceActionTemplate.of(RTG, "place container on truck", -3, true),

            // TT actions at RTG - 2 takts before container's base takt
            DeviceActionTemplate.of(TT, "drive under RTG", -2, false),
            DeviceActionTemplate.of(TT, "handover from RTG", -2, true),

            // TT actions at QC - 1 takt before container's base takt
            DeviceActionTemplate.of(TT, "drive under QC", -1, false),
            DeviceActionTemplate.of(TT, "handover to QC", -1, true),

            // QC actions - at container's base takt
            DeviceActionTemplate.of(QC, "container lifted from truck", 0, false),
            DeviceActionTemplate.of(QC, "container placed on vessel", 0, true)
    );

    /**
     * Returns the minimum takt offset used in the workflow.
     * This determines how many takts before the base takt actions can occur.
     *
     * @return the minimum (most negative) takt offset
     */
    public static int getMinTaktOffset() {
        return ACTION_TEMPLATES.stream()
                .mapToInt(DeviceActionTemplate::taktOffset)
                .min()
                .orElse(0);
    }
}
