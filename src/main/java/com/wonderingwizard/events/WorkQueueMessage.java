package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

/**
 * Event representing a work queue message that can trigger schedule creation or abortion.
 * <p>
 * When a WorkQueueMessage with status ACTIVE is processed, a schedule is created.
 * When a subsequent message with the same workQueueId has status INACTIVE, the schedule is aborted.
 * Duplicate ACTIVE messages for the same workQueueId are idempotent (no side effects).
 *
 * @param workQueueId unique identifier for the work queue
 * @param status the status of the work queue
 */
public record WorkQueueMessage(String workQueueId, WorkQueueStatus status) implements Event {

    @Override
    public String toString() {
        return "WorkQueueMessage[workQueueId=" + workQueueId + ", status=" + status + "]";
    }
}
