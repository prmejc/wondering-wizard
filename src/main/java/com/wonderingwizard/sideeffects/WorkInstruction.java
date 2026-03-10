package com.wonderingwizard.sideeffects;

import com.wonderingwizard.events.WorkInstructionStatus;

import java.time.Instant;

/**
 * Represents a work instruction included in a ScheduleCreated side effect.
 * <p>
 * This record captures the state of a work instruction at the time the
 * schedule was created.
 *
 * @param workInstructionId unique identifier for the work instruction
 * @param workQueueId identifier of the work queue this instruction belongs to
 * @param fetchChe the CHE (Container Handling Equipment) identifier for fetching
 * @param status the status of the work instruction
 * @param estimatedMoveTime the estimated time when this work instruction should start
 * @param estimatedCycleTimeSeconds the estimated cycle time for this work instruction in seconds
 * @param estimatedRtgCycleTimeSeconds the estimated RTG cycle time in seconds (default 60)
 * @param putChe the CHE (Container Handling Equipment) identifier for putting
 * @param isTwinFetch whether this is a twin fetch operation
 * @param isTwinPut whether this is a twin put operation
 * @param isTwinCarry whether this is a twin carry operation
 * @param twinCompanionWorkInstruction the twin companion work instruction ID
 */
public record WorkInstruction(
        long workInstructionId,
        long workQueueId,
        String fetchChe,
        WorkInstructionStatus status,
        Instant estimatedMoveTime,
        int estimatedCycleTimeSeconds,
        int estimatedRtgCycleTimeSeconds,
        String putChe,
        boolean isTwinFetch,
        boolean isTwinPut,
        boolean isTwinCarry,
        long twinCompanionWorkInstruction
) {

    @Override
    public String toString() {
        return "WorkInstruction[workInstructionId=" + workInstructionId +
                ", workQueueId=" + workQueueId +
                ", fetchChe=" + fetchChe +
                ", putChe=" + putChe +
                ", status=" + status +
                ", estimatedMoveTime=" + estimatedMoveTime +
                ", estimatedCycleTimeSeconds=" + estimatedCycleTimeSeconds +
                ", estimatedRtgCycleTimeSeconds=" + estimatedRtgCycleTimeSeconds +
                ", isTwinFetch=" + isTwinFetch +
                ", isTwinPut=" + isTwinPut +
                ", isTwinCarry=" + isTwinCarry +
                ", twinCompanionWorkInstruction=" + twinCompanionWorkInstruction + "]";
    }
}
