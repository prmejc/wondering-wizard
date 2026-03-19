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
 * @param workQueueSequence the sequence number of this work queue (nullable)
 * @param pointOfWorkName the point of work name, e.g. "QCZ9" (nullable)
 * @param bollardPosition the bollard position (nullable)
 * @param workQueueManaged whether the work queue is managed (nullable)
 */
public record WorkQueueMessage(long workQueueId, WorkQueueStatus status, int qcMudaSeconds, LoadMode loadMode,
                                String workQueueSequence, String pointOfWorkName,
                                String bollardPosition, String workQueueManaged) implements Event {

    /** Backwards-compatible constructor without the new fields. Defaults workQueueManaged to "FES4". */
    public WorkQueueMessage(long workQueueId, WorkQueueStatus status, int qcMudaSeconds, LoadMode loadMode) {
        this(workQueueId, status, qcMudaSeconds, loadMode, null, null, null, "FES4");
    }

    @Override
    public String toString() {
        return "WorkQueueMessage[workQueueId=" + workQueueId + ", status=" + status
                + ", qcMudaSeconds=" + qcMudaSeconds + ", loadMode=" + loadMode
                + ", workQueueSequence=" + workQueueSequence + ", pointOfWorkName=" + pointOfWorkName
                + ", bollardPosition=" + bollardPosition + ", workQueueManaged=" + workQueueManaged + "]";
    }
}
