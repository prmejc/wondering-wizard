package com.wonderingwizard.simulator;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.logging.Logger;

/**
 * Handles RTG equipment instructions.
 * <p>
 * SELECT_JOB → responds with JobOperation "A" (operator accepted) on the job-operation topic.
 */
public class RTGHandler implements InstructionHandler {

    private static final Logger logger = Logger.getLogger(RTGHandler.class.getName());
    private static final Schema JOB_OP_SCHEMA = loadSchema();

    private final String jobOperationTopic;

    public RTGHandler(String jobOperationTopic) {
        this.jobOperationTopic = jobOperationTopic;
    }

    @Override
    public void handle(GenericRecord instruction, KafkaInfra kafka) {
        String instructionType = getString(instruction, "equipmentInstructionType");
        String recipientChe = getString(instruction, "recipientCHEShortName");

        if (!"SELECT_JOB".equals(instructionType)) {
            logger.fine("RTG: ignoring instruction type " + instructionType + " for " + recipientChe);
            return;
        }

        Object containersObj = instruction.get("containers");
        if (!(containersObj instanceof List<?> containers) || containers.isEmpty()) {
            logger.warning("RTG: SELECT_JOB has no containers for " + recipientChe);
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
            jobOp.put("action", "A");
            jobOp.put("cheId", recipientChe);
            jobOp.put("cheType", "RTG");
            jobOp.put("yardSlot", null);
            jobOp.put("containerTruckPosition", containerTruckPosition.isEmpty() ? null : containerTruckPosition);

            logger.info("RTG: " + recipientChe + " → job accepted for WI " + workInstructionId
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
