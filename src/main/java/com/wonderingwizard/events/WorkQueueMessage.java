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
 * @param qcMudaSeconds QC muda (waste) time in seconds, added to each takt's duration
 * @param loadMode the load mode determining which scheduling template to use (LOAD or DSCH)
 */
public record WorkQueueMessage(String workQueueId, WorkQueueStatus status, int qcMudaSeconds, LoadMode loadMode) implements Event {

    @Override
    public String toString() {
        return "WorkQueueMessage[workQueueId=" + workQueueId + ", status=" + status + ", qcMudaSeconds=" + qcMudaSeconds + ", loadMode=" + loadMode + "]";
    }
}
