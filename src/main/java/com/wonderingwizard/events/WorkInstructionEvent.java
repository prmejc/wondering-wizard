package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

import java.time.Instant;

/**
 * Event representing a work instruction that is associated with a work queue.
 * <p>
 * Work instructions are registered with the system and are included in the
 * ScheduleCreated side effect when their associated work queue is activated.
 *
 * @param eventType the Kafka event type that triggered this event (e.g. "WI MoveStage Changed")
 * @param workInstructionId unique identifier for the work instruction
 * @param workQueueId identifier of the work queue this instruction belongs to
 * @param fetchChe the CHE (Container Handling Equipment) identifier for fetching
 * @param workInstructionMoveStage the move stage from Kafka (e.g. "Planned", "Carry Underway", "Complete")
 * @param estimatedMoveTime the estimated time when this work instruction should start
 * @param estimatedCycleTimeSeconds the estimated cycle time for this work instruction in seconds
 * @param estimatedRtgCycleTimeSeconds the estimated RTG cycle time in seconds (default 60)
 * @param putChe the CHE (Container Handling Equipment) identifier for putting
 * @param isTwinFetch whether this is a twin fetch operation
 * @param isTwinPut whether this is a twin put operation
 * @param isTwinCarry whether this is a twin carry operation
 * @param twinCompanionWorkInstruction the twin companion work instruction ID
 * @param toPosition the target position for this work instruction
 * @param containerId the container identifier (e.g. MAEU1234567)
 */
public record WorkInstructionEvent(
        String eventType,
        long workInstructionId,
        long workQueueId,
        String fetchChe,
        String workInstructionMoveStage,
        Instant estimatedMoveTime,
        int estimatedCycleTimeSeconds,
        int estimatedRtgCycleTimeSeconds,
        String putChe,
        boolean isTwinFetch,
        boolean isTwinPut,
        boolean isTwinCarry,
        long twinCompanionWorkInstruction,
        String toPosition,
        String containerId
) implements Event {

    private static final int DEFAULT_RTG_CYCLE_TIME_SECONDS = 60;

    public WorkInstructionEvent(
            long workInstructionId,
            long workQueueId,
            String fetchChe,
            String workInstructionMoveStage,
            Instant estimatedMoveTime,
            int estimatedCycleTimeSeconds,
            int estimatedRtgCycleTimeSeconds,
            String putChe,
            boolean isTwinFetch,
            boolean isTwinPut,
            boolean isTwinCarry) {
        this("", workInstructionId, workQueueId, fetchChe, workInstructionMoveStage, estimatedMoveTime,
                estimatedCycleTimeSeconds, estimatedRtgCycleTimeSeconds,
                putChe, isTwinFetch, isTwinPut, isTwinCarry, 0, "", "");
    }

    public WorkInstructionEvent(
            long workInstructionId,
            long workQueueId,
            String fetchChe,
            String workInstructionMoveStage,
            Instant estimatedMoveTime,
            int estimatedCycleTimeSeconds,
            int estimatedRtgCycleTimeSeconds,
            String putChe,
            boolean isTwinFetch,
            boolean isTwinPut,
            boolean isTwinCarry,
            long twinCompanionWorkInstruction,
            String toPosition) {
        this("", workInstructionId, workQueueId, fetchChe, workInstructionMoveStage, estimatedMoveTime,
                estimatedCycleTimeSeconds, estimatedRtgCycleTimeSeconds,
                putChe, isTwinFetch, isTwinPut, isTwinCarry, twinCompanionWorkInstruction,
                toPosition, "");
    }

    public WorkInstructionEvent(
            long workInstructionId,
            long workQueueId,
            String fetchChe,
            String workInstructionMoveStage,
            Instant estimatedMoveTime,
            int estimatedCycleTimeSeconds,
            int estimatedRtgCycleTimeSeconds,
            String putChe,
            boolean isTwinFetch,
            boolean isTwinPut,
            boolean isTwinCarry,
            long twinCompanionWorkInstruction,
            String toPosition,
            String containerId) {
        this("", workInstructionId, workQueueId, fetchChe, workInstructionMoveStage, estimatedMoveTime,
                estimatedCycleTimeSeconds, estimatedRtgCycleTimeSeconds,
                putChe, isTwinFetch, isTwinPut, isTwinCarry, twinCompanionWorkInstruction,
                toPosition, containerId);
    }

    public WorkInstructionEvent(
            long workInstructionId,
            long workQueueId,
            String fetchChe,
            String workInstructionMoveStage,
            Instant estimatedMoveTime,
            int estimatedCycleTimeSeconds,
            int estimatedRtgCycleTimeSeconds) {
        this("", workInstructionId, workQueueId, fetchChe, workInstructionMoveStage, estimatedMoveTime,
                estimatedCycleTimeSeconds, estimatedRtgCycleTimeSeconds,
                "", false, false, false, 0, "", "");
    }

    public WorkInstructionEvent(
            long workInstructionId,
            long workQueueId,
            String fetchChe,
            String workInstructionMoveStage,
            Instant estimatedMoveTime,
            int estimatedCycleTimeSeconds) {
        this("", workInstructionId, workQueueId, fetchChe, workInstructionMoveStage, estimatedMoveTime,
                estimatedCycleTimeSeconds, DEFAULT_RTG_CYCLE_TIME_SECONDS,
                "", false, false, false, 0, "", "");
    }

    @Override
    public String toString() {
        return "WorkInstructionEvent[eventType=" + eventType +
                ", workInstructionId=" + workInstructionId +
                ", workQueueId=" + workQueueId +
                ", fetchChe=" + fetchChe +
                ", putChe=" + putChe +
                ", workInstructionMoveStage=" + workInstructionMoveStage +
                ", estimatedMoveTime=" + estimatedMoveTime +
                ", estimatedCycleTimeSeconds=" + estimatedCycleTimeSeconds +
                ", estimatedRtgCycleTimeSeconds=" + estimatedRtgCycleTimeSeconds +
                ", isTwinFetch=" + isTwinFetch +
                ", isTwinPut=" + isTwinPut +
                ", isTwinCarry=" + isTwinCarry +
                ", twinCompanionWorkInstruction=" + twinCompanionWorkInstruction +
                ", toPosition=" + toPosition +
                ", containerId=" + containerId + "]";
    }
}
