package com.wonderingwizard.domain.takt;

import java.util.List;

/**
 * Represents a Takt in a schedule.
 * Takts are named sequentially starting from TAKT100 (TAKT100, TAKT101, etc.).
 * Each Takt contains a list of actions to be performed.
 *
 * @param name the takt identifier (e.g., "TAKT100", "TAKT101")
 * @param actions the list of actions in this takt
 */
public record Takt(String name, List<Action> actions) {

    private static final int STARTING_TAKT_NUMBER = 100;

    /**
     * Creates a Takt name for the given zero-based index.
     *
     * @param index zero-based index (0 -> TAKT100, 1 -> TAKT101, etc.)
     * @return the takt name
     */
    public static String createTaktName(int index) {
        return "TAKT" + (STARTING_TAKT_NUMBER + index);
    }
}
