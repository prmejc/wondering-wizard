package com.wonderingwizard.simulator;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

/**
 * Handles QC equipment instructions.
 * <p>
 * LIFT → produces "Lifted container from vessel" asset event + "QC Discharged Container" WI event.
 * PLACE → produces "Placed container on truck" asset event.
 */
public class QCHandler implements InstructionHandler {

    private static final Logger logger = Logger.getLogger(QCHandler.class.getName());

    private final String assetEventTopic;
    private final String wiTopic;
    private final String terminalCode;
    private final WorkInstructionStateTracker wiTracker;

    public QCHandler(String assetEventTopic, String wiTopic, String terminalCode,
                     WorkInstructionStateTracker wiTracker) {
        this.assetEventTopic = assetEventTopic;
        this.wiTopic = wiTopic;
        this.terminalCode = terminalCode;
        this.wiTracker = wiTracker;
    }

    @Override
    public void handle(GenericRecord instruction, KafkaInfra kafka) {
        String instructionType = getString(instruction, "equipmentInstructionType");
        String cheId = getString(instruction, "recipientCHEShortName");
        String moveKind = getString(instruction, "moveKind");

        String operationalEvent = switch (instructionType) {
            case "LIFT" -> "Lifted container from vessel";
            case "PLACE" -> "Placed container on truck";
            default -> null;
        };

        if (operationalEvent == null) {
            logger.fine("QC: ignoring instruction type " + instructionType + " for " + cheId);
            return;
        }

        // Send asset event
        String json = "{" +
                "\"move\":\"" + moveKind + "\"," +
                "\"operationalEvent\":\"" + operationalEvent + "\"," +
                "\"cheID\":\"" + cheId + "\"," +
                "\"terminalCode\":\"" + terminalCode + "\"," +
                "\"timestamp\":" + Instant.now().getEpochSecond() +
                "}";

        logger.fine("QC: " + cheId + " → " + operationalEvent);
        kafka.send(assetEventTopic, cheId, json);

        // On LIFT, also send "QC Discharged Container" WI event for each container
        if ("LIFT".equals(instructionType)) {
            sendQcDischargedEvents(instruction, kafka);
        }
    }

    private void sendQcDischargedEvents(GenericRecord instruction, KafkaInfra kafka) {
        Object containersObj = instruction.get("containers");
        if (!(containersObj instanceof List<?> containers)) return;

        for (Object containerObj : containers) {
            if (!(containerObj instanceof GenericRecord container)) continue;

            Object wiIdObj = container.get("workInstructionId");
            if (!(wiIdObj instanceof Long wiId)) continue;

            GenericRecord latestWi = wiTracker.get(wiId);
            if (latestWi == null) {
                logger.warning("QC: No WI state for workInstructionId=" + wiId + ", skipping QC Discharged Container");
                continue;
            }

            // Clone the WI record and set eventType to "QC Discharged Container"
            GenericRecord wiEvent = cloneWithEventType(latestWi, "QC Discharged Container");
            if (wiEvent != null) {
                logger.fine("QC: Sending QC Discharged Container for WI " + wiId);
                kafka.sendAvro(wiTopic, String.valueOf(wiId), wiEvent);
            }
        }
    }

    private GenericRecord cloneWithEventType(GenericRecord source, String eventType) {
        Schema schema = source.getSchema();
        GenericRecord clone = new GenericData.Record(schema);
        for (Schema.Field field : schema.getFields()) {
            clone.put(field.name(), source.get(field.name()));
        }
        clone.put("eventType", eventType);
        return clone;
    }

    private static String getString(GenericRecord record, String field) {
        Object value = record.get(field);
        return value != null ? value.toString() : "";
    }
}
