package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

import java.util.UUID;

/**
 * Event indicating that an action has been completed.
 * <p>
 * This event is sent when an external system confirms that an action
 * has been successfully completed. The actionId must match the ID of
 * the currently active action for the event to be processed.
 *
 * @param actionId the UUID of the action that was completed
 * @param workQueueId the work queue this action belongs to
 */
public record ActionCompletedEvent(
        UUID actionId,
        String workQueueId
) implements Event {

    @Override
    public String toString() {
        return "ActionCompletedEvent[actionId=" + actionId +
                ", workQueueId=" + workQueueId + "]";
    }
}
