package com.wonderingwizard.kafka.messages;

/**
 * Record representing a WorkInstruction message as received from Kafka (Avro schema).
 * <p>
 * Maps to the Avro schema:
 * {@code APMT.terminalOperations.workInstruction.topic.confidential.dedicated.v1.WorkInstruction}
 *
 * @param eventType the type of event (e.g., "INSERT", "UPDATE", "DELETE")
 * @param opType the operation type
 * @param cdhTerminalCode the CDH terminal code
 * @param messageSequenceNumber sequence number of the message
 * @param workInstructionId the work instruction identifier
 * @param workQueueId the work queue identifier
 * @param stateFetch the fetch state
 * @param workInstructionQueueSequence the queue sequence
 * @param moveKind the move kind
 * @param workInstructionMoveStage the move stage (maps to status)
 * @param fetchCHEName the fetch CHE name
 * @param carryCHEName the carry CHE name
 * @param putCHEName the put CHE name
 * @param estimatedMoveTime the estimated move time in millis since epoch
 * @param estimatedCycleTime the estimated cycle time in seconds
 * @param estimatedRTGCycleTime the estimated RTG cycle time in seconds
 * @param estimatedEHCycleTime the estimated EH cycle time in seconds
 * @param containerId the container identifier
 * @param sourceTsMs the source timestamp in milliseconds
 * @param toPosition the target position for this work instruction
 */
public record WorkInstructionKafkaMessage(
        String eventType,
        String opType,
        String cdhTerminalCode,
        Long messageSequenceNumber,
        Long workInstructionId,
        Long workQueueId,
        String stateFetch,
        Long workInstructionQueueSequence,
        String moveKind,
        String workInstructionMoveStage,
        String fetchCHEName,
        String carryCHEName,
        String putCHEName,
        Long estimatedMoveTime,
        Double estimatedCycleTime,
        Double estimatedRTGCycleTime,
        Double estimatedEHCycleTime,
        String containerId,
        Long sourceTsMs,
        Boolean isTwinFetch,
        Boolean isTwinPut,
        Boolean isTwinCarry,
        Long twinCompanionWorkInstruction,
        String fromPosition,
        String toPosition,
        String jobPosition
) {
}
