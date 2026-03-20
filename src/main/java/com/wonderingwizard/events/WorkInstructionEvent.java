package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

import java.time.Instant;

/**
 * Event representing a work instruction that is associated with a work queue.
 *
 * @param eventType the Kafka event type that triggered this event
 * @param workInstructionId unique identifier for the work instruction
 * @param workQueueId identifier of the work queue this instruction belongs to
 * @param fetchChe the CHE identifier for fetching (e.g. "QCZ1")
 * @param workInstructionMoveStage the move stage (e.g. "Planned", "Complete")
 * @param estimatedMoveTime the estimated time when this work instruction should start
 * @param estimatedCycleTimeSeconds the estimated cycle time in seconds
 * @param estimatedRtgCycleTimeSeconds the estimated RTG cycle time in seconds
 * @param putChe the CHE identifier for putting (e.g. "RTZ01")
 * @param isTwinFetch whether this is a twin fetch operation
 * @param isTwinPut whether this is a twin put operation
 * @param isTwinCarry whether this is a twin carry operation
 * @param twinCompanionWorkInstruction the twin companion work instruction ID
 * @param fromPosition the source position (e.g. "V-IF3606W-661390")
 * @param toPosition the target position (e.g. "Y-PTM-1L20E4")
 * @param containerId the container identifier
 * @param moveKind the move kind (e.g. "DSCH", "Load")
 * @param jobPosition the job position (e.g. "FWD", "AFT")
 * @param isoType the ISO container type code (e.g. "22G1", "45G1")
 * @param freightKind the freight kind (e.g. "MTY", "FCL", "LCL")
 * @param pinning the pinning instruction (e.g. "GO_PINNING" or empty)
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
        String fromPosition,
        String toPosition,
        String containerId,
        String moveKind,
        String jobPosition,
        String isoType,
        String freightKind,
        String pinning
) implements Event {

    private static final int DEFAULT_RTG_CYCLE_TIME_SECONDS = 60;

    public WorkInstructionEvent(
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
            String containerId) {
        this(eventType, workInstructionId, workQueueId, fetchChe, workInstructionMoveStage,
                estimatedMoveTime, estimatedCycleTimeSeconds, estimatedRtgCycleTimeSeconds,
                putChe, isTwinFetch, isTwinPut, isTwinCarry, twinCompanionWorkInstruction,
                "", toPosition, containerId, "", "", "", "", "");
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
            boolean isTwinCarry) {
        this("", workInstructionId, workQueueId, fetchChe, workInstructionMoveStage, estimatedMoveTime,
                estimatedCycleTimeSeconds, estimatedRtgCycleTimeSeconds,
                putChe, isTwinFetch, isTwinPut, isTwinCarry, 0, "", "", "", "", "", "", "", "");
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
                "", toPosition, "", "", "", "", "", "");
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
                "", toPosition, containerId, "", "", "", "", "");
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
                "", false, false, false, 0, "", "", "", "", "", "", "", "");
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
                "", false, false, false, 0, "", "", "", "", "", "", "", "");
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
                ", fromPosition=" + fromPosition +
                ", toPosition=" + toPosition +
                ", containerId=" + containerId +
                ", moveKind=" + moveKind + "]";
    }
}
