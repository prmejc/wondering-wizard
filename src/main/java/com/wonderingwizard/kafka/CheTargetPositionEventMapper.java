package com.wonderingwizard.kafka;

import com.wonderingwizard.events.CheTargetPositionEvent;
import org.apache.avro.generic.GenericRecord;

import java.util.logging.Logger;

/**
 * Maps Avro CheTargetPositionConfirmation records to {@link CheTargetPositionEvent} engine events.
 */
public class CheTargetPositionEventMapper implements EventMapper<CheTargetPositionEvent> {

    private static final Logger logger = Logger.getLogger(CheTargetPositionEventMapper.class.getName());

    @Override
    public CheTargetPositionEvent map(GenericRecord record) {
        String equipmentInstructionId = getString(record, "equipmentInstructionId");
        String cheShortName = getString(record, "cheShortName");
        String confirmedMapNodeName = getString(record, "confirmedMapNodeName");
        String terminalCode = getString(record, "terminalCode");

        logger.fine("Mapped CheTargetPosition: cheShortName=" + cheShortName
                + ", instructionId=" + equipmentInstructionId);

        return new CheTargetPositionEvent(equipmentInstructionId, cheShortName, confirmedMapNodeName, terminalCode);
    }

    private static String getString(GenericRecord record, String field) {
        Object value = record.get(field);
        return value != null ? value.toString() : "";
    }
}
