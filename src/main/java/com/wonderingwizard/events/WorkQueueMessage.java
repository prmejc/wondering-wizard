package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

/**
 * Event representing a work queue message that can trigger schedule creation or abortion.
 * <p>
 * When a WorkQueueMessage with status "Active" is processed, a schedule is created.
 * When a subsequent message with the same workQueueId has status "Inactive", the schedule is aborted.
 * Duplicate "Active" messages for the same workQueueId are idempotent (no side effects).
 *
 * @param workQueueId unique identifier for the work queue
 * @param status the status of the work queue ("Active" or "Inactive")
 */
public record WorkQueueMessage(String workQueueId, String status) implements Event {

    @Override
    public String toString() {
        return "WorkQueueMessage[workQueueId=" + workQueueId + ", status=" + status + "]";
    }
}
