package com.wonderingwizard.simulator;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Handles TT (terminal truck) equipment instructions.
 * <p>
 * Responds immediately with an Avro CheTargetPositionConfirmation message
 * confirming the truck has arrived at the destination.
 */
public class TTHandler implements InstructionHandler {

    private static final Logger logger = Logger.getLogger(TTHandler.class.getName());
    private static final Schema SCHEMA = loadSchema();
    private static final Schema GPS_SCHEMA = SCHEMA.getField("coordinates").schema();

    private final String cheTargetPositionTopic;
    private final String terminalCode;

    public TTHandler(String cheTargetPositionTopic, String terminalCode) {
        this.cheTargetPositionTopic = cheTargetPositionTopic;
        this.terminalCode = terminalCode;
    }

    @Override
    public void handle(GenericRecord instruction, KafkaInfra kafka) {
        String instructionType = getString(instruction, "equipmentInstructionType");
        String equipmentInstructionId = getString(instruction, "equipmentInstructionId");
        String destinationNodeName = getString(instruction, "destinationNodeName");

        // The TT name is in carryCHEShortName (the truck assigned to carry)
        String cheShortName = getNullableString(instruction, "carryCHEShortName");
        if (cheShortName.isEmpty()) {
            cheShortName = getString(instruction, "recipientCHEShortName");
        }

        GenericRecord coordinates = new GenericData.Record(GPS_SCHEMA);
        coordinates.put("latitude", 0.0);
        coordinates.put("longitude", 0.0);
        coordinates.put("hdop", 0.0);

        GenericRecord confirmation = new GenericData.Record(SCHEMA);
        confirmation.put("terminalCode", terminalCode);
        confirmation.put("cheShortName", cheShortName);
        confirmation.put("equipmentInstructionId", equipmentInstructionId);
        confirmation.put("destinationNodeId", 0L);
        confirmation.put("destinationNodeName", destinationNodeName);
        confirmation.put("confirmedMapNodeId", 0L);
        confirmation.put("confirmedMapNodeName", destinationNodeName);
        confirmation.put("coordinates", coordinates);
        confirmation.put("timeStamp", Instant.now().toEpochMilli());
        confirmation.put("eventSource", "Terminal Simulator");

        logger.info("TT: " + cheShortName + " → confirmed " + instructionType
                + " at " + destinationNodeName + " (instruction=" + equipmentInstructionId + ")");
        kafka.sendAvro(cheTargetPositionTopic, cheShortName, confirmation);
    }

    private static String getString(GenericRecord record, String field) {
        Object value = record.get(field);
        return value != null ? value.toString() : "";
    }

    private static String getNullableString(GenericRecord record, String field) {
        Object value = record.get(field);
        if (value == null) return "";
        return value.toString();
    }

    private static Schema loadSchema() {
        try (InputStream is = TTHandler.class.getResourceAsStream("/schemas/CheTargetPositionConfirmation.avro")) {
            if (is == null) {
                throw new IllegalStateException("CheTargetPositionConfirmation schema not found");
            }
            return new Schema.Parser().parse(is);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load CheTargetPositionConfirmation schema", e);
        }
    }
}
