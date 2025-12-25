package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

import java.time.Instant;

/**
 * Event representing a point in time.
 * TimeEvents are sent periodically (every 5 seconds) with UTC timestamp.
 *
 * @param timestamp the UTC timestamp of this time event
 */
public record TimeEvent(Instant timestamp) implements Event {

    /**
     * Creates a TimeEvent with the current time.
     * Note: This constructor is provided for convenience but in production
     * code, time should come from external sources (not System time).
     */
    public TimeEvent() {
        this(Instant.now());
    }

    @Override
    public String toString() {
        return "TimeEvent[timestamp=" + timestamp + "]";
    }
}
