package com.wonderingwizard.domain.takt;

import java.time.Instant;
import java.util.List;

/**
 * Represents a Takt in a schedule.
 * Takts with QC actions are named TAKT100, TAKT101, etc.
 * Takts before the first QC takt are named PULSE99, PULSE98, etc. (counting down).
 * Each Takt contains a list of actions to be performed and a start time
 * that determines when the takt can become active.
 *
 * @param name the takt identifier (e.g., "PULSE97", "TAKT100")
 * @param actions the list of actions in this takt
 * @param startTime the earliest time at which this takt can become active
 */
public record Takt(String name, List<Action> actions, Instant startTime) {

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
