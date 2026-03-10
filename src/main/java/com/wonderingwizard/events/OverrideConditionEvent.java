package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

/**
 * Event to manually override (satisfy) a specific condition on a takt,
 * allowing the takt to start even if that condition is not naturally met.
 *
 * @param workQueueId the work queue the takt belongs to
 * @param taktName the name of the takt (e.g., "TAKT100")
 * @param conditionId the condition identifier to override (e.g., "time" or "dependencies")
 */
public record OverrideConditionEvent(
        long workQueueId,
        String taktName,
        String conditionId
) implements Event {

    @Override
    public String toString() {
        return "OverrideConditionEvent[workQueueId=" + workQueueId +
                ", taktName=" + taktName +
                ", conditionId=" + conditionId + "]";
    }
}
