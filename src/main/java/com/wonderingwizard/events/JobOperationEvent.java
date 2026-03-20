package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

/**
 * Event representing a job operation from a CHE operator.
 * Action "A" means the operator accepted/activated the job.
 *
 * @param action the operation action (e.g., "A" for accepted)
 * @param cheId the CHE identifier (e.g., "RTZ03")
 * @param cheType the CHE type (e.g., "RTG")
 * @param workInstructionId the work instruction ID as string
 * @param containerId the container identifier
 */
public record JobOperationEvent(
        String action,
        String cheId,
        String cheType,
        String workInstructionId,
        String containerId
) implements Event {

    @Override
    public String toString() {
        return "JobOperationEvent[action=" + action +
                ", cheId=" + cheId +
                ", cheType=" + cheType +
                ", workInstructionId=" + workInstructionId +
                ", containerId=" + containerId + "]";
    }
}
