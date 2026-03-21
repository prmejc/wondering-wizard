package com.wonderingwizard.domain.takt;

import java.time.Instant;

/**
 * Condition that requires the current time to be at or past a given time.
 * Used for takt activation based on planned start time.
 *
 * @param id the condition identifier
 * @param time the time that must be reached
 */
public record TimeCondition(String id, Instant time) implements TaktCondition {

    public TimeCondition(Instant time) {
        this("time", time);
    }

    @Override
    public boolean evaluate(ConditionContext context) {
        if (time == null) {
            return true;
        }
        return !context.currentTime().isBefore(time);
    }

    @Override
    public String explanation(ConditionContext context) {
        if (time == null) {
            return "No start time constraint";
        }
        return "Waiting for time to reach " + time
                + " (current: " + context.currentTime() + ")";
    }

    @Override
    public String type() {
        return "TIME";
    }
}
