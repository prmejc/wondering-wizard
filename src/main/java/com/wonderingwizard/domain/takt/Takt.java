package com.wonderingwizard.domain.takt;

import java.time.Instant;
import java.util.List;

/**
 * Represents a Takt in a schedule.
 * Takts with QC actions are named TAKT100, TAKT101, etc.
 * Takts before the first QC takt are named PULSE99, PULSE98, etc. (counting down).
 *
 * <h3>Time attributes:</h3>
 * <ul>
 *   <li><b>Planned start time:</b> Derived from the WorkInstruction's estimated move time.
 *       This is the originally scheduled time and does not change.</li>
 *   <li><b>Estimated start time:</b> The current best estimate for when this takt will start.
 *       Initially equal to the planned start time. Used for activation scheduling.</li>
 *   <li><b>Actual start time:</b> The system time (last TimeEvent) when the takt was activated.
 *       Tracked at runtime by the ScheduleRunnerProcessor, not stored on this record.</li>
 * </ul>
 *
 * @param name the takt identifier (e.g., "PULSE97", "TAKT100")
 * @param actions the list of actions in this takt
 * @param plannedStartTime the originally scheduled start time from the work instruction
 * @param estimatedStartTime the current estimated start time (initially equal to plannedStartTime)
 * @param durationSeconds the duration of this takt in seconds (typically 115-135)
 */
public record Takt(String name, List<Action> actions, Instant plannedStartTime, Instant estimatedStartTime, int durationSeconds) {

    private static final int STARTING_TAKT_NUMBER = 100;

    /**
     * Creates a Takt name for the given zero-based index.
     * Takts at or after {@code firstQcTaktIndex} are named TAKT100, TAKT101, etc.
     * Takts before {@code firstQcTaktIndex} are named PULSE99, PULSE98, etc.
     *
     * @param index zero-based takt index
     * @param firstQcTaktIndex the index of the first takt that contains QC actions
     * @return the takt name
     */
    public static String createTaktName(int index, int firstQcTaktIndex) {
        if (index >= firstQcTaktIndex) {
            return "TAKT" + (STARTING_TAKT_NUMBER + index - firstQcTaktIndex);
        }
        return "PULSE" + (STARTING_TAKT_NUMBER - 1 - (firstQcTaktIndex - 1 - index));
    }
}
