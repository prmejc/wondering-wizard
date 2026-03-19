package com.wonderingwizard.server;

import com.wonderingwizard.engine.Event;
import com.wonderingwizard.events.ActionCompletedEvent;
import com.wonderingwizard.events.CheJobStepState;
import com.wonderingwizard.events.CheStatus;
import com.wonderingwizard.events.ContainerHandlingEquipmentEvent;
import com.wonderingwizard.events.ContainerMoveStateEvent;
import com.wonderingwizard.events.CraneAvailabilityStatus;
import com.wonderingwizard.events.CraneAvailabilityStatusEvent;
import com.wonderingwizard.events.CraneDelayActivityEvent;
import com.wonderingwizard.events.CraneReadinessEvent;
import com.wonderingwizard.events.DigitalMapEvent;
import com.wonderingwizard.events.LoadMode;
import com.wonderingwizard.events.NukeWorkQueueEvent;
import com.wonderingwizard.events.OverrideActionConditionEvent;
import com.wonderingwizard.events.OverrideConditionEvent;
import com.wonderingwizard.events.SystemTimeSet;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.events.WorkQueueStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Deserializes events from flat JSON maps (as produced by {@link JsonSerializer}).
 * Uses the "type" field to determine which event record to reconstruct.
 */
public final class EventDeserializer {

    private EventDeserializer() {}

    /**
     * Deserialize an event from a flat JSON key-value map.
     *
     * @param fields the parsed JSON fields (all values as strings)
     * @return the reconstructed event
     * @throws IllegalArgumentException if the event type is unknown or fields are missing
     */
    public static Event deserialize(Map<String, String> fields) {
        String type = fields.get("type");
        if (type == null) {
            throw new IllegalArgumentException("Missing 'type' field in event JSON");
        }

        return switch (type) {
            case "TimeEvent" -> new TimeEvent(
                    Instant.parse(requireField(fields, "timestamp")));

            case "SystemTimeSet" -> new SystemTimeSet(
                    Instant.parse(requireField(fields, "timestamp")));

            case "WorkQueueMessage" -> new WorkQueueMessage(
                    Long.parseLong(requireField(fields, "workQueueId")),
                    WorkQueueStatus.valueOf(requireField(fields, "status")),
                    Integer.parseInt(fields.getOrDefault("qcMudaSeconds", "0")),
                    fields.get("loadMode") != null && !fields.get("loadMode").equals("null")
                            ? LoadMode.valueOf(fields.get("loadMode")) : null);

            case "WorkInstructionEvent" -> new WorkInstructionEvent(
                    fields.getOrDefault("eventType", ""),
                    Long.parseLong(requireField(fields, "workInstructionId")),
                    Long.parseLong(requireField(fields, "workQueueId")),
                    fields.getOrDefault("fetchChe", ""),
                    requireField(fields,
                            fields.containsKey("workInstructionMoveStage") ? "workInstructionMoveStage" : "status"),
                    fields.get("estimatedMoveTime") != null && !fields.get("estimatedMoveTime").equals("null")
                            ? Instant.parse(fields.get("estimatedMoveTime")) : null,
                    Integer.parseInt(fields.getOrDefault("estimatedCycleTimeSeconds", "0")),
                    Integer.parseInt(fields.getOrDefault("estimatedRtgCycleTimeSeconds", "60")),
                    fields.getOrDefault("putChe", ""),
                    Boolean.parseBoolean(fields.getOrDefault("isTwinFetch", "false")),
                    Boolean.parseBoolean(fields.getOrDefault("isTwinPut", "false")),
                    Boolean.parseBoolean(fields.getOrDefault("isTwinCarry", "false")),
                    Long.parseLong(fields.getOrDefault("twinCompanionWorkInstruction", "0")),
                    fields.getOrDefault("fromPosition", ""),
                    fields.getOrDefault("toPosition", ""),
                    fields.getOrDefault("containerId", ""),
                    fields.getOrDefault("moveKind", ""),
                    fields.getOrDefault("jobPosition", "FWD"));

            case "ActionCompletedEvent" -> new ActionCompletedEvent(
                    UUID.fromString(requireField(fields, "actionId")),
                    Long.parseLong(requireField(fields, "workQueueId")));

            case "OverrideConditionEvent" -> new OverrideConditionEvent(
                    Long.parseLong(requireField(fields, "workQueueId")),
                    requireField(fields, "taktName"),
                    requireField(fields, "conditionId"));

            case "OverrideActionConditionEvent" -> new OverrideActionConditionEvent(
                    Long.parseLong(requireField(fields, "workQueueId")),
                    UUID.fromString(requireField(fields, "actionId")),
                    requireField(fields, "conditionId"));

            case "DigitalMapEvent" -> new DigitalMapEvent(
                    fields.getOrDefault("mapPayload", ""));

            case "NukeWorkQueueEvent" -> new NukeWorkQueueEvent(
                    Long.parseLong(requireField(fields, "workQueueId")));

            case "ContainerMoveStateEvent" -> new ContainerMoveStateEvent(
                    fields.getOrDefault("containerMoveAction", "STOPPED"),
                    fields.getOrDefault("containerMoveStateRequestStatus", "ERROR"),
                    fields.getOrDefault("responseContainerMoveState", "TT_ASSIGNED"),
                    requireField(fields, "carryCHEName"),
                    Long.parseLong(requireField(fields, "workInstructionId")),
                    fields.getOrDefault("moveKind", ""),
                    fields.getOrDefault("containerId", ""),
                    fields.getOrDefault("terminalCode", ""),
                    fields.getOrDefault("errorMessage", ""),
                    Long.parseLong(fields.getOrDefault("sourceTsMs", "0")));

            case "CraneDelayActivityEvent" -> new CraneDelayActivityEvent(
                    fields.getOrDefault("eventType", null),
                    fields.getOrDefault("opType", null),
                    fields.getOrDefault("cdhTerminalCode", null),
                    parseLongNullable(fields.get("messageSequenceNumber")),
                    parseLongNullable(fields.get("vesselVisitCraneDelayId")),
                    fields.getOrDefault("vesselVisitId", null),
                    parseInstantNullable(fields.get("delayStartTime")),
                    parseInstantNullable(fields.get("delayStopTime")),
                    requireField(fields, "cheShortName"),
                    fields.getOrDefault("delayRemarks", null),
                    fields.getOrDefault("delayType", null),
                    fields.getOrDefault("delayTypeDescription", null),
                    fields.getOrDefault("positionEnum", null),
                    fields.getOrDefault("delayStatus", null),
                    fields.getOrDefault("delayTypeAction", null),
                    fields.getOrDefault("delayTypeCategory", null),
                    parseLongNullable(fields.get("sourceTsMs")));

            case "CraneAvailabilityStatusEvent" -> new CraneAvailabilityStatusEvent(
                    fields.getOrDefault("terminalCode", ""),
                    requireField(fields, "cheId"),
                    fields.getOrDefault("cheType", ""),
                    CraneAvailabilityStatus.fromCode(fields.getOrDefault("cheStatus", "NOT_READY")),
                    Long.parseLong(fields.getOrDefault("sourceTsMs", "0")));

            case "CraneReadinessEvent" -> new CraneReadinessEvent(
                    requireField(fields, "qcShortName"),
                    Long.parseLong(requireField(fields, "workQueueId")),
                    fields.get("qcResumeTimestamp") != null && !fields.get("qcResumeTimestamp").equals("null")
                            ? Instant.parse(fields.get("qcResumeTimestamp")) : null,
                    fields.get("updatedBy"),
                    fields.getOrDefault("eventId", ""));

            case "ContainerHandlingEquipmentEvent" -> new ContainerHandlingEquipmentEvent(
                    fields.getOrDefault("eventType", ""),
                    fields.get("cheId") != null && !fields.get("cheId").equals("null")
                            ? Long.parseLong(fields.get("cheId")) : null,
                    fields.getOrDefault("opType", ""),
                    fields.getOrDefault("cdhTerminalCode", ""),
                    fields.get("messageSequenceNumber") != null && !fields.get("messageSequenceNumber").equals("null")
                            ? Long.parseLong(fields.get("messageSequenceNumber")) : null,
                    requireField(fields, "cheShortName"),
                    fields.get("cheStatus") != null && !fields.get("cheStatus").isEmpty()
                            && !fields.get("cheStatus").equals("null")
                            ? CheStatus.fromDisplayName(fields.get("cheStatus")) : null,
                    fields.getOrDefault("cheKind", "TT"),
                    fields.get("chePoolId") != null && !fields.get("chePoolId").equals("null")
                            ? Long.parseLong(fields.get("chePoolId")) : null,
                    fields.get("cheJobStepState") != null && !fields.get("cheJobStepState").isEmpty()
                            && !fields.get("cheJobStepState").equals("null")
                            ? CheJobStepState.fromCode(fields.get("cheJobStepState")) : null,
                    fields.get("sourceTsMs") != null && !fields.get("sourceTsMs").equals("null")
                            ? Long.parseLong(fields.get("sourceTsMs")) : null);

            default -> throw new IllegalArgumentException("Unknown event type: " + type);
        };
    }

    private static Long parseLongNullable(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) return null;
        return Long.parseLong(value);
    }

    private static Instant parseInstantNullable(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) return null;
        return Instant.parse(value);
    }

    private static String requireField(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing required field: " + name);
        }
        return value;
    }
}
