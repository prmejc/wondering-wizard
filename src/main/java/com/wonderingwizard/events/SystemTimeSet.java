package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

import java.time.Instant;

/**
 * Event that sets the system clock to an absolute time.
 * <p>
 * Sent once at system startup with the current computer time,
 * and during import to restore the initial time baseline.
 *
 * @param timestamp the absolute time to set the system clock to
 */
public record SystemTimeSet(Instant timestamp) implements Event {

    @Override
    public String toString() {
        return "SystemTimeSet[timestamp=" + timestamp + "]";
    }
}
