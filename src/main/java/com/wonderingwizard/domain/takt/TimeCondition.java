package com.wonderingwizard.domain.takt;

import java.time.Instant;

/**
 * Condition that requires the current time to be at or past the takt's estimated start time.
 *
 * @param estimatedStartTime the time that must be reached
 */
public record TimeCondition(String id, Instant estimatedStartTime) implements TaktCondition {

    public TimeCondition(Instant estimatedStartTime) {
        this("time", estimatedStartTime);
    }

    @Override
    public boolean evaluate(ConditionContext context) {
        if (estimatedStartTime == null) {
            return true;
        }
        return !context.currentTime().isBefore(estimatedStartTime);
    }

    @Override
    public String explanation(ConditionContext context) {
        if (estimatedStartTime == null) {
            return "No start time constraint";
        }
        return "Waiting for time to reach " + estimatedStartTime
                + " (current: " + context.currentTime() + ")";
    }

    @Override
    public String type() {
        return "TIME";
    }
}
