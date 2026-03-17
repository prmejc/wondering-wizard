package com.wonderingwizard.events;

import java.util.Set;

/**
 * Constants for work instruction move stage values as received from Kafka.
 */
public final class MoveStage {

    private MoveStage() {}

    public static final String PLANNED = "Planned";
    public static final String READY = "Ready";
    public static final String FETCH_UNDERWAY = "Fetch Underway";
    public static final String CARRY_UNDERWAY = "Carry Underway";
    public static final String CARRY_COMPLETE = "Carry Complete";
    public static final String PUT_UNDERWAY = "Put Underway";
    public static final String PUT_COMPLETE = "Put Complete";
    public static final String COMPLETE = "Complete";
    public static final String CANCELLED = "Cancelled";

    private static final Set<String> PRE_FETCH = Set.of(PLANNED, READY);

    /**
     * Returns true if the move stage indicates that the fetch has completed,
     * i.e. the container has been picked up and is in transit or beyond.
     */
    public static boolean isFetchComplete(String moveStage) {
        return moveStage != null && !PRE_FETCH.contains(moveStage);
    }
}
