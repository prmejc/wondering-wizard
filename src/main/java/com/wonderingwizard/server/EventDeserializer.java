package com.wonderingwizard.server;

import com.wonderingwizard.engine.Event;
import com.wonderingwizard.events.ActionCompletedEvent;
import com.wonderingwizard.events.LoadMode;
import com.wonderingwizard.events.OverrideActionConditionEvent;
import com.wonderingwizard.events.OverrideConditionEvent;
import com.wonderingwizard.events.SystemTimeSet;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.WorkInstructionStatus;
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
                    Long.parseLong(requireField(fields, "workInstructionId")),
                    Long.parseLong(requireField(fields, "workQueueId")),
                    fields.getOrDefault("fetchChe", ""),
                    WorkInstructionStatus.valueOf(requireField(fields, "status")),
                    fields.get("estimatedMoveTime") != null && !fields.get("estimatedMoveTime").equals("null")
                            ? Instant.parse(fields.get("estimatedMoveTime")) : null,
                    Integer.parseInt(fields.getOrDefault("estimatedCycleTimeSeconds", "0")),
                    Integer.parseInt(fields.getOrDefault("estimatedRtgCycleTimeSeconds", "60")),
                    fields.getOrDefault("putChe", ""),
                    Boolean.parseBoolean(fields.getOrDefault("isTwinFetch", "false")),
                    Boolean.parseBoolean(fields.getOrDefault("isTwinPut", "false")),
                    Boolean.parseBoolean(fields.getOrDefault("isTwinCarry", "false")),
                    Long.parseLong(fields.getOrDefault("twinCompanionWorkInstruction", "0")),
                    fields.getOrDefault("toPosition", ""));

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

            default -> throw new IllegalArgumentException("Unknown event type: " + type);
        };
    }

    private static String requireField(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing required field: " + name);
        }
        return value;
    }
}
