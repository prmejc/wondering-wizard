package com.wonderingwizard.domain.takt;

/**
 * Type-safe enumeration of all action types in the container workflow.
 * <p>
 * Used for compile-time checked mapping of actions to side effects (e.g., Kafka messages).
 * Each constant has a display name used in the UI and logs.
 *
 * <h3>Load workflow (yard → vessel):</h3>
 * <ul>
 *   <li>RTG: drive → fetch → handover to TT</li>
 *   <li>TT: drive to RTG pull → standby → under → handover from RTG → drive to QC pull → standby → under QC → handover to QC → buffer</li>
 *   <li>QC: lift → place</li>
 * </ul>
 *
 * <h3>Discharge workflow (vessel → yard):</h3>
 * <ul>
 *   <li>QC: lift → place</li>
 *   <li>TT: handover from QC → drive to RTG pull → standby → under → handover to RTG → buffer</li>
 *   <li>RTG: drive → lift from TT → place on yard</li>
 * </ul>
 */
public enum ActionType {

    // ── RTG actions ─────────────────────────────────────────────────────
    RTG_DRIVE("drive"),
    RTG_WAIT_FOR_TRUCK("wait for tt"),
    RTG_FETCH("fetch"),
    RTG_HANDOVER_TO_TT("handover to tt"),
    RTG_LIFT_FROM_TT("lift from tt"),
    RTG_PLACE_ON_YARD("place on yard"),

    // ── TT actions ──────────────────────────────────────────────────────
    TT_DRIVE_TO_RTG_PULL("drive to RTG pull"),
    TT_DRIVE_TO_RTG_STANDBY("drive to RTG standby"),
    TT_DRIVE_TO_RTG_UNDER("drive to RTG under"),
    TT_HANDOVER_FROM_RTG("handover from RTG"),
    TT_DRIVE_TO_QC_PULL("drive to QC pull"),
    TT_DRIVE_TO_QC_STANDBY("drive to QC standby"),
    TT_DRIVE_UNDER_QC("drive under QC"),
    TT_HANDOVER_TO_QC("handover to QC"),
    TT_HANDOVER_FROM_QC("handover from QC"),
    TT_HANDOVER_TO_RTG("handover to RTG"),
    TT_DRIVE_TO_BUFFER("drive to buffer"),

    // ── QC actions ──────────────────────────────────────────────────────
    QC_LIFT("QC Lift"),
    QC_PLACE("QC Place");

    private final String displayName;

    ActionType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the human-readable name for display in the UI and logs.
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Returns a display name with an optional container suffix (e.g., "QC Lift1" for twin operations).
     *
     * @param containerSuffix the 1-based container suffix to append, or 0 for no suffix
     */
    public String displayName(int containerSuffix) {
        if (containerSuffix <= 0) {
            return displayName;
        }
        return displayName + containerSuffix;
    }
}
