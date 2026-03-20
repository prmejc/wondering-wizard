package com.wonderingwizard.simulator;

import org.apache.avro.generic.GenericRecord;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Tracks the latest state of each work instruction by workInstructionId.
 * Updated by consuming the WI topic. Thread-safe for concurrent access
 * from the WI consumer thread and the instruction handler thread.
 */
public class WorkInstructionStateTracker {

    private static final Logger logger = Logger.getLogger(WorkInstructionStateTracker.class.getName());

    private final ConcurrentHashMap<Long, GenericRecord> wiState = new ConcurrentHashMap<>();

    public void update(GenericRecord record) {
        Object wiIdObj = record.get("workInstructionId");
        if (wiIdObj instanceof Long wiId) {
            wiState.put(wiId, record);
            logger.fine("Updated WI state for workInstructionId=" + wiId);
        }
    }

    /**
     * Returns the latest Avro record for the given workInstructionId, or null.
     */
    public GenericRecord get(long workInstructionId) {
        return wiState.get(workInstructionId);
    }

    public int size() {
        return wiState.size();
    }
}
