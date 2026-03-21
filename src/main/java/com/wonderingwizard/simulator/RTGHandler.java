package com.wonderingwizard.simulator;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

/**
 * Handles RTG equipment instructions.
 * <p>
 * SELECT_JOB → responds with JobOperation "A" (operator accepted) on the job-operation topic.
 * LIFT → responds with RTG asset event "RTGliftedContainerfromTruck" on the asset event topic.
 * PLACE → responds with JobOperation "D" (done) + RTG asset event "RTGplacedContainerOnYard".
 */
public class RTGHandler implements InstructionHandler {

    private static final Logger logger = Logger.getLogger(RTGHandler.class.getName());
    private static final Schema JOB_OP_SCHEMA = loadSchema();

    private final String jobOperationTopic;
    private final String assetEventTopic;
    private final String terminalCode;

    public RTGHandler(String jobOperationTopic, String assetEventTopic, String terminalCode) {
        this.jobOperationTopic = jobOperationTopic;
        this.assetEventTopic = assetEventTopic;
        this.terminalCode = terminalCode;
    }

    @Override
    public void handle(GenericRecord instruction, KafkaInfra kafka) {
        String instructionType = getString(instruction, "equipmentInstructionType");
        String recipientChe = getString(instruction, "recipientCHEShortName");

        switch (instructionType) {
            case "SELECT_JOB" -> handleSelectJob(instruction, recipientChe, kafka);
            case "LIFT" -> handleLift(recipientChe, kafka);
            case "PLACE" -> handlePlace(instruction, recipientChe, kafka);
            default -> logger.fine("RTG: ignoring instruction type " + instructionType + " for " + recipientChe);
        }
    }

    private void handleLift(String cheId, KafkaInfra kafka) {
        String json = "{" +
                "\"move\":\"DSCH\"," +
                "\"operationalEvent\":\"RTGliftedContainerfromTruck\"," +
                "\"cheID\":\"" + cheId + "\"," +
                "\"terminalCode\":\"" + terminalCode + "\"," +
                "\"timestamp\":" + Instant.now().getEpochSecond() +
                "}";

        logger.fine("RTG: " + cheId + " → RTGliftedContainerfromTruck");
        kafka.send(assetEventTopic, cheId, json);
    }

    private void handlePlace(GenericRecord instruction, String recipientChe, KafkaInfra kafka) {
        // Send JobOperation "D" (done) for each container
        sendJobOperation(instruction, recipientChe, "D", kafka);

        // Send RTG asset event
        String json = "{" +
                "\"move\":\"DSCH\"," +
                "\"operationalEvent\":\"RTGplacedContainerOnYard\"," +
                "\"cheID\":\"" + recipientChe + "\"," +
                "\"terminalCode\":\"" + terminalCode + "\"," +
                "\"timestamp\":" + Instant.now().getEpochSecond() +
                "}";

        logger.fine("RTG: " + recipientChe + " → RTGplacedContainerOnYard");
        kafka.send(assetEventTopic, recipientChe, json);
    }

    private void handleSelectJob(GenericRecord instruction, String recipientChe, KafkaInfra kafka) {
        sendJobOperation(instruction, recipientChe, "A", kafka);
    }

    private void sendJobOperation(GenericRecord instruction, String recipientChe, String action, KafkaInfra kafka) {
        Object containersObj = instruction.get("containers");
        if (!(containersObj instanceof List<?> containers) || containers.isEmpty()) {
            logger.warning("RTG: " + action + " has no containers for " + recipientChe);
            return;
        }

        for (Object containerObj : containers) {
            if (!(containerObj instanceof GenericRecord container)) continue;

            String containerId = getString(container, "containerId");
            Object wiIdObj = container.get("workInstructionId");
            String workInstructionId = wiIdObj != null ? wiIdObj.toString() : "";
            String containerTruckPosition = getNullableString(container, "containerTruckPosition");

            GenericRecord jobOp = new GenericData.Record(JOB_OP_SCHEMA);
            jobOp.put("containerId", containerId);
            jobOp.put("workInstructionId", workInstructionId);
            jobOp.put("action", action);
            jobOp.put("cheId", recipientChe);
            jobOp.put("cheType", "RTG");
            jobOp.put("yardSlot", null);
            jobOp.put("containerTruckPosition", containerTruckPosition.isEmpty() ? null : containerTruckPosition);

            logger.fine("RTG: " + recipientChe + " → job operation '" + action + "' for WI " + workInstructionId
                    + " container " + containerId);
            kafka.sendAvro(jobOperationTopic, recipientChe, jobOp);
        }
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
        try (InputStream is = RTGHandler.class.getResourceAsStream("/schemas/JobOperation.avsc")) {
            if (is == null) {
                throw new IllegalStateException("JobOperation schema not found");
            }
            return new Schema.Parser().parse(is);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load JobOperation schema", e);
        }
    }
}
