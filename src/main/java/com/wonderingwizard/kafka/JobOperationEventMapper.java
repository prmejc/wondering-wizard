package com.wonderingwizard.kafka;

import com.wonderingwizard.events.JobOperationEvent;
import org.apache.avro.generic.GenericRecord;

import java.util.logging.Logger;

/**
 * Maps Avro JobOperation records to {@link JobOperationEvent} engine events.
 */
public class JobOperationEventMapper implements EventMapper<JobOperationEvent> {

    private static final Logger logger = Logger.getLogger(JobOperationEventMapper.class.getName());

    @Override
    public JobOperationEvent map(GenericRecord record) {
        String action = getString(record, "action");
        String cheId = getString(record, "cheId");
        String cheType = getString(record, "cheType");
        String workInstructionId = getString(record, "workInstructionId");
        String containerId = getString(record, "containerId");

        logger.fine("Mapped JobOperation: action=" + action + ", cheId=" + cheId
                + ", wiId=" + workInstructionId + ", containerId=" + containerId);

        return new JobOperationEvent(action, cheId, cheType, workInstructionId, containerId);
    }

    private static String getString(GenericRecord record, String field) {
        Object value = record.get(field);
        return value != null ? value.toString() : "";
    }
}
