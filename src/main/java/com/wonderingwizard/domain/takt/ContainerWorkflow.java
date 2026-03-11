package com.wonderingwizard.domain.takt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.wonderingwizard.domain.takt.ActionType.*;
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
 * <h3>Takt structure:</h3>
 * <p>The base structure uses 3 takts per container (defined by template flags).
 * When TT sequential actions in a pulse takt exceed the pulse duration,
 * extra pulses are dynamically introduced by the processor to split them.</p>
 *
 * <h3>Cross-device synchronization:</h3>
 * <ul>
 *   <li>"rtg handover to TT" (RTG) depends on "drive to RTG under" (TT) completing first.
 *       "handover from RTG" (TT) depends on its own previous TT action.</li>
 *   <li>"handover from TT" (QC) and "handover to QC" (TT) both depend on "drive under QC" (TT)
 *       completing first. "handover from TT" has a cross-device dependency on TT.</li>
 * </ul>
 *
 * <p>Early takts have no QC actions because TT has not reached QC position yet.
 * QC only participates in the final takt.
 */
public final class ContainerWorkflow {

    private ContainerWorkflow() {
        // Utility class
    }

    /**
     * The ordered list of action templates for the container workflow.
     * Actions are grouped by takt, with consecutive {@code isFirstInTaktForDevice=true} flags
     * forming a single takt boundary.
     *
     * <p>Within each takt, actions from different devices can run in parallel.
     * Dependencies are per-device (each action depends on the previous action of the same device)
     * plus explicit cross-device dependencies for handover synchronization.
     */
    public static final List<DeviceActionTemplate> ACTION_TEMPLATES = List.of(
            // Takt A: RTG prep + TT approach to RTG (no QC)
            DeviceActionTemplate.of(RTG, RTG_DRIVE, false),
            DeviceActionTemplate.of(RTG, RTG_FETCH, false),
            DeviceActionTemplate.of(TT, TT_DRIVE_TO_RTG_PULL, false),
            DeviceActionTemplate.of(TT, TT_DRIVE_TO_RTG_STANDBY, false),

            // Takt B: RTG-TT handover (no QC)
            DeviceActionTemplate.of(TT, TT_DRIVE_TO_RTG_UNDER, true),
            DeviceActionTemplate.of(RTG, RTG_HANDOVER_TO_TT, true, TT),
            DeviceActionTemplate.of(TT, TT_HANDOVER_FROM_RTG, false),

            // Takt C: TT transit to QC (no RTG, no QC)
            DeviceActionTemplate.of(TT, TT_DRIVE_TO_QC_PULL, false),
            DeviceActionTemplate.of(TT, TT_DRIVE_TO_QC_STANDBY, false),

            // Takt D: TT approach to QC (no RTG, no QC)
            DeviceActionTemplate.of(TT, TT_DRIVE_UNDER_QC, false),

            // Takt E: TT-QC handover + QC operations
            // QC_LIFT must come before TT_HANDOVER_TO_QC in template order
            // so both resolve their cross-device/same-device dep to TT_DRIVE_UNDER_QC
            DeviceActionTemplate.of(QC, QC_LIFT, true, TT),
            DeviceActionTemplate.of(TT, TT_HANDOVER_TO_QC, true),
            DeviceActionTemplate.of(QC, QC_PLACE, false),
            DeviceActionTemplate.of(TT, TT_DRIVE_TO_BUFFER, false)
    );

    /**
     * Precomputed base takt offsets from template isFirstInTaktForDevice flags.
     * These define the minimum takt structure (3 takts per container).
     * Additional splits may be applied dynamically by the processor.
     */
    private static final Map<DeviceActionTemplate, Integer> TAKT_OFFSETS = computeTaktOffsets();

    private static Map<DeviceActionTemplate, Integer> computeTaktOffsets() {
        Map<DeviceActionTemplate, Integer> offsets = new HashMap<>();
        int currentTakt = 0;
        boolean prevWasFirst = false;

        for (DeviceActionTemplate template : ACTION_TEMPLATES) {
            if (template.isFirstInTaktForDevice()) {
                if (!prevWasFirst) {
                    currentTakt++;
                }
                prevWasFirst = true;
            } else {
                prevWasFirst = false;
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
     * Gets the base takt offset for a template (from template-defined boundaries only).
     * Offset 0 is the QC/TT handover takt, negative values are earlier takts.
     *
     * @param template the template
     * @return the base takt offset
     */
    public static int getTaktOffset(DeviceActionTemplate template) {
        return TAKT_OFFSETS.getOrDefault(template, 0);
    }

    /**
     * Returns the minimum base takt offset used in the workflow.
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
