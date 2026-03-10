package com.wonderingwizard.kafka.messages;

/**
 * Record representing a WorkQueue message as received from Kafka (Avro schema).
 * <p>
 * Maps to the Avro schema:
 * {@code APMT.terminalOperations.workQueue.topic.confidential.dedicated.v1.WorkQueue}
 *
 * @param eventType the type of event (e.g., "INSERT", "UPDATE", "DELETE")
 * @param opType the operation type
 * @param cdhTerminalCode the CDH terminal code
 * @param messageSequenceNumber sequence number of the message
 * @param workQueueId the work queue identifier
 * @param vesselVisitId the vessel visit identifier
 * @param vesselName the name of the vessel
 * @param vesselRow the vessel row
 * @param vesselDeck the vessel deck
 * @param workQueueName the name of the work queue
 * @param workQueueStatus the status of the work queue (e.g., "ACTIVE", "INACTIVE")
 * @param workQueueSequence the sequence of the work queue
 * @param workQueueType the type of work queue
 * @param pointOfWorkName the point of work name
 * @param loadMode the load mode
 * @param note any notes associated with the work queue
 * @param workQueueCycleCompanionId the cycle companion ID
 * @param workQueueDoubleCycleFromSequence the double cycle from sequence
 * @param workQueueDoubleCycleToSequence the double cycle to sequence
 * @param vesselOrientation the vessel orientation
 * @param bollardPosition the bollard position
 * @param mudaQCCycleTime the QC muda (waste) cycle time in seconds
 * @param workQueueManaged whether the work queue is managed
 * @param sourceTsMs the source timestamp in milliseconds
 */
public record WorkQueueKafkaMessage(
        String eventType,
        String opType,
        String cdhTerminalCode,
        Long messageSequenceNumber,
        Long workQueueId,
        String vesselVisitId,
        String vesselName,
        String vesselRow,
        String vesselDeck,
        String workQueueName,
        String workQueueStatus,
        String workQueueSequence,
        String workQueueType,
        String pointOfWorkName,
        String loadMode,
        String note,
        Long workQueueCycleCompanionId,
        Long workQueueDoubleCycleFromSequence,
        Long workQueueDoubleCycleToSequence,
        String vesselOrientation,
        String bollardPosition,
        Long mudaQCCycleTime,
        String workQueueManaged,
        Long sourceTsMs
) {
}
