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
 *       Initially equal to the planned start time. Updated at runtime as delays accumulate.</li>
 *   <li><b>Actual start time:</b> The system time (last TimeEvent) when the takt was activated.
 *       Tracked at runtime by the ScheduleRunnerProcessor.</li>
 * </ul>
 */
public final class Takt {

    private static final int STARTING_TAKT_NUMBER = 100;

    private final int sequence;
    private final List<Action> actions;
    private final Instant plannedStartTime;
    private volatile Instant estimatedStartTime;
    private final int durationSeconds;

    public Takt(int sequence, List<Action> actions, Instant plannedStartTime, Instant estimatedStartTime, int durationSeconds) {
        this.sequence = sequence;
        this.actions = actions;
        this.plannedStartTime = plannedStartTime;
        this.estimatedStartTime = estimatedStartTime;
        this.durationSeconds = durationSeconds;
    }

    public int sequence() { return sequence; }
    public List<Action> actions() { return actions; }
    public Instant plannedStartTime() { return plannedStartTime; }
    public Instant estimatedStartTime() { return estimatedStartTime; }
    public int durationSeconds() { return durationSeconds; }

    public void setEstimatedStartTime(Instant estimatedStartTime) {
        this.estimatedStartTime = estimatedStartTime;
    }

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

    public String name() {
        if (sequence < 0) {
            return "PULSE" + (STARTING_TAKT_NUMBER + sequence);
        }
        return "TAKT" + (STARTING_TAKT_NUMBER + sequence);
    }
}
