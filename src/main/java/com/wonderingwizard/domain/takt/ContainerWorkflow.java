package com.wonderingwizard.domain.takt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.wonderingwizard.domain.takt.DeviceType.*;

/**
 * Defines the standard workflow for moving a container from yard to vessel.
 * The workflow involves three devices (RTG, TT, QC) operating in parallel
 * with handover synchronization points between them.
 *
 * <h3>Device workflows:</h3>
 * <ul>
 *   <li><b>RTG:</b> rtg drive → fetch → rtg handover to TT</li>
 *   <li><b>TT:</b> drive to RTG pull → drive to RTG standby → drive to RTG under →
 *       handover from RTG → drive to QC pull → drive to QC standby → drive under QC →
 *       handover to QC → drive to buffer</li>
 *   <li><b>QC:</b> handover from TT → place on vessel</li>
 * </ul>
 *
 * <h3>Takt structure (4 takts per container):</h3>
 * <ul>
 *   <li><b>Takt A:</b> RTG prep (rtg drive, fetch) + TT approach (drive to RTG pull, drive to RTG standby)</li>
 *   <li><b>Takt B:</b> RTG-TT handover (rtg handover to TT, drive to RTG under, handover from RTG)</li>
 *   <li><b>Takt C:</b> TT transit (drive to QC pull, drive to QC standby)</li>
 *   <li><b>Takt D:</b> TT-QC handover + QC ops (drive under QC, handover to QC, handover from TT,
 *       place on vessel, drive to buffer)</li>
 * </ul>
 *
 * <h3>Cross-device synchronization:</h3>
 * <ul>
 *   <li>"rtg handover to TT" (RTG) depends on "drive to RTG under" (TT) completing first.
 *       "handover from RTG" (TT) depends on its own previous TT action.</li>
 *   <li>"handover to QC" (TT) and "handover from TT" (QC) activate simultaneously
 *       in the same takt, each depending only on their own device's previous action.</li>
 * </ul>
 *
 * <p>Early takts have no QC actions because TT has not reached QC position yet.
 * QC only participates in the final takt (Takt D).
 */
public final class ContainerWorkflow {

    private ContainerWorkflow() {
        // Utility class
    }

    /**
     * The ordered list of action templates for the container workflow.
     * Actions are grouped by takt, with {@code isFirstInTaktForDevice=true} marking takt boundaries.
     *
     * <p>Within each takt, actions from different devices can run in parallel.
     * Dependencies are per-device (each action depends on the previous action of the same device)
     * plus explicit cross-device dependencies for handover synchronization.
     */
    public static final List<DeviceActionTemplate> ACTION_TEMPLATES = List.of(
            // Takt A: RTG prep + TT approach to RTG (no QC)
            DeviceActionTemplate.of(RTG, "rtg drive", true),
            DeviceActionTemplate.of(RTG, "fetch", false),
            DeviceActionTemplate.of(TT, "drive to RTG pull", false),
            DeviceActionTemplate.of(TT, "drive to RTG standby", false),

            // Takt B: RTG-TT handover (no QC)
            DeviceActionTemplate.of(TT, "drive to RTG under", true),
            DeviceActionTemplate.of(RTG, "rtg handover to TT", false, TT),
            DeviceActionTemplate.of(TT, "handover from RTG", false),

            // Takt C: TT transit to QC (no RTG, no QC)
            DeviceActionTemplate.of(TT, "drive to QC pull", true),
            DeviceActionTemplate.of(TT, "drive to QC standby", false),

            // Takt D: TT-QC handover + QC operations
            DeviceActionTemplate.of(TT, "drive under QC", true),
            DeviceActionTemplate.of(TT, "handover to QC", false),
            DeviceActionTemplate.of(QC, "handover from TT", false),
            DeviceActionTemplate.of(QC, "place on vessel", false),
            DeviceActionTemplate.of(TT, "drive to buffer", false)
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

        // Normalize so that the last takt boundary (QC handover) is at offset 0
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
     * Offset 0 is the base takt (QC/TT handover takt), negative values are earlier takts.
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
