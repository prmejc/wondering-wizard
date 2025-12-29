package com.wonderingwizard.domain.takt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.wonderingwizard.domain.takt.DeviceType.*;

/**
 * Defines the standard workflow for moving a container from yard to vessel.
 * The workflow involves multiple devices (RTG, TT, QC) with actions distributed
 * across different takts relative to the container's base takt.
 *
 * <p>Actions form a linked sequence. The {@code isFirstInTaktForDevice} flag marks
 * takt boundaries - when true, this action starts a new takt and all previous
 * actions must be in earlier takts.
 *
 * <p>Takt boundaries (where isFirstInTaktForDevice=true):
 * <ul>
 *   <li>RTG "lift container from yard" - starts first takt</li>
 *   <li>TT "drive under RTG" - starts second takt</li>
 *   <li>TT "drive under QC" - starts third takt</li>
 *   <li>QC "container lifted from truck" - starts fourth takt (base takt)</li>
 * </ul>
 */
public final class ContainerWorkflow {

    private ContainerWorkflow() {
        // Utility class
    }

    /**
     * The ordered list of action templates for the container workflow.
     * Actions form a linked sequence - use {@link #getPrevious} and {@link #getNext}
     * to navigate between templates.
     */
    public static final List<DeviceActionTemplate> ACTION_TEMPLATES = List.of(
            // RTG actions - first takt boundary
            DeviceActionTemplate.of(RTG, "lift container from yard", true),
            DeviceActionTemplate.of(RTG, "place container on truck", false),

            // TT actions at RTG - second takt boundary
            DeviceActionTemplate.of(TT, "drive under RTG", true),
            DeviceActionTemplate.of(TT, "handover from RTG", false),

            // TT actions at QC - third takt boundary
            DeviceActionTemplate.of(TT, "drive under QC", true),
            DeviceActionTemplate.of(TT, "handover to QC", false),

            // QC actions - fourth takt boundary (base takt)
            DeviceActionTemplate.of(QC, "container lifted from truck", true),
            DeviceActionTemplate.of(QC, "container placed on vessel", false)
    );

    /**
     * Precomputed takt offsets for each template based on isFirstInTaktForDevice flags.
     */
    private static final Map<DeviceActionTemplate, Integer> TAKT_OFFSETS = computeTaktOffsets();

    private static Map<DeviceActionTemplate, Integer> computeTaktOffsets() {
        Map<DeviceActionTemplate, Integer> offsets = new HashMap<>();
        int currentTakt = 0;

        for (DeviceActionTemplate template : ACTION_TEMPLATES) {
            if (template.isFirstInTaktForDevice()) {
                currentTakt++;
            }
            offsets.put(template, currentTakt);
        }

        // Normalize so that the last takt boundary (QC) is at offset 0
        int baseTaktOffset = offsets.get(ACTION_TEMPLATES.get(ACTION_TEMPLATES.size() - 1));
        for (DeviceActionTemplate template : ACTION_TEMPLATES) {
            offsets.put(template, offsets.get(template) - baseTaktOffset);
        }

        return offsets;
    }

    /**
     * Gets the previous template in the workflow sequence.
     *
     * @param template the current template
     * @return the previous template, or empty if this is the first
     */
    public static Optional<DeviceActionTemplate> getPrevious(DeviceActionTemplate template) {
        int index = ACTION_TEMPLATES.indexOf(template);
        if (index <= 0) {
            return Optional.empty();
        }
        return Optional.of(ACTION_TEMPLATES.get(index - 1));
    }

    /**
     * Gets the next template in the workflow sequence.
     *
     * @param template the current template
     * @return the next template, or empty if this is the last
     */
    public static Optional<DeviceActionTemplate> getNext(DeviceActionTemplate template) {
        int index = ACTION_TEMPLATES.indexOf(template);
        if (index < 0 || index >= ACTION_TEMPLATES.size() - 1) {
            return Optional.empty();
        }
        return Optional.of(ACTION_TEMPLATES.get(index + 1));
    }

    /**
     * Gets the computed takt offset for a template.
     * Offset 0 is the base takt (QC actions), negative values are earlier takts.
     *
     * @param template the template
     * @return the takt offset
     */
    public static int getTaktOffset(DeviceActionTemplate template) {
        return TAKT_OFFSETS.getOrDefault(template, 0);
    }

    /**
     * Returns the minimum takt offset used in the workflow.
     * This determines how many takts before the base takt actions can occur.
     *
     * @return the minimum (most negative) takt offset
     */
    public static int getMinTaktOffset() {
        return TAKT_OFFSETS.values().stream()
                .mapToInt(Integer::intValue)
                .min()
                .orElse(0);
    }
}
