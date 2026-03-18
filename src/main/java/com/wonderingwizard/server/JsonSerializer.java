package com.wonderingwizard.server;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.ActionCompletedEvent;
import com.wonderingwizard.events.ContainerHandlingEquipmentEvent;
import com.wonderingwizard.events.DigitalMapEvent;
import com.wonderingwizard.events.NukeWorkQueueEvent;
import com.wonderingwizard.events.OverrideActionConditionEvent;
import com.wonderingwizard.events.OverrideConditionEvent;
import com.wonderingwizard.events.SetTimeAlarm;
import com.wonderingwizard.events.SystemTimeSet;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.sideeffects.ActionActivated;
import com.wonderingwizard.sideeffects.ActionCompleted;
import com.wonderingwizard.sideeffects.AlarmSet;
import com.wonderingwizard.sideeffects.AlarmTriggered;
import com.wonderingwizard.sideeffects.DelayUpdated;
import com.wonderingwizard.sideeffects.ScheduleAborted;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import com.wonderingwizard.sideeffects.TTStateUpdated;
import com.wonderingwizard.sideeffects.TaktActivated;
import com.wonderingwizard.sideeffects.TaktCompleted;
import com.wonderingwizard.sideeffects.TruckAssigned;
import com.wonderingwizard.sideeffects.WorkInstructionReassigned;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Hand-rolled JSON serializer using pattern matching on sealed types, records, enums,
 * Instant, and UUID. Zero external dependencies.
 */
public final class JsonSerializer {

    private JsonSerializer() {}

    public static String serialize(Object obj) {
        if (obj == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        writeValue(sb, obj);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object obj) {
        if (obj == null) {
            sb.append("null");
        } else if (obj instanceof String s) {
            writeString(sb, s);
        } else if (obj instanceof Number n) {
            sb.append(n);
        } else if (obj instanceof Boolean b) {
            sb.append(b);
        } else if (obj instanceof Instant instant) {
            writeString(sb, instant.toString());
        } else if (obj instanceof UUID uuid) {
            writeString(sb, uuid.toString());
        } else if (obj instanceof Enum<?> e) {
            writeString(sb, e.name());
        } else if (obj instanceof Event event) {
            writeEvent(sb, event);
        } else if (obj instanceof SideEffect sideEffect) {
            writeSideEffect(sb, sideEffect);
        } else if (obj instanceof Takt takt) {
            writeTakt(sb, takt);
        } else if (obj instanceof Action action) {
            writeAction(sb, action);
        } else if (obj instanceof WebViewWorkInstruction wvwi) {
            writeWebViewWorkInstruction(sb, wvwi);
        } else if (obj instanceof List<?> list) {
            writeList(sb, list);
        } else if (obj instanceof Set<?> set) {
            writeList(sb, set.stream().toList());
        } else if (obj instanceof Map<?, ?> map) {
            writeMap(sb, map);
        } else if (obj instanceof DemoServer.Step step) {
            writeStep(sb, step);
        } else if (obj instanceof DemoServer.ActionState actionState) {
            writeActionState(sb, actionState);
        } else if (obj instanceof DemoServer.TaktState taktState) {
            writeTaktState(sb, taktState);
        } else if (obj instanceof DemoServer.ScheduleView scheduleView) {
            writeScheduleView(sb, scheduleView);
        } else if (obj instanceof DemoServer.TaktView taktView) {
            writeTaktView(sb, taktView);
        } else if (obj instanceof DemoServer.ActionView actionView) {
            writeActionView(sb, actionView);
        } else if (obj instanceof DemoServer.ConditionView conditionView) {
            writeConditionView(sb, conditionView);
        } else {
            writeString(sb, obj.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
    }

    private static void writeList(StringBuilder sb, Collection<?> list) {
        sb.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) sb.append(',');
            writeValue(sb, item);
            first = false;
        }
        sb.append(']');
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) sb.append(',');
            writeString(sb, entry.getKey().toString());
            sb.append(':');
            writeValue(sb, entry.getValue());
            first = false;
        }
        sb.append('}');
    }

    private static void writeEvent(StringBuilder sb, Event event) {
        switch (event) {
            case TimeEvent e -> {
                sb.append('{');
                writeField(sb, "type", "TimeEvent", true);
                writeField(sb, "timestamp", e.timestamp(), false);
                sb.append('}');
            }
            case SystemTimeSet e -> {
                sb.append('{');
                writeField(sb, "type", "SystemTimeSet", true);
                writeField(sb, "timestamp", e.timestamp(), false);
                sb.append('}');
            }
            case SetTimeAlarm e -> {
                sb.append('{');
                writeField(sb, "type", "SetTimeAlarm", true);
                writeField(sb, "alarmName", e.alarmName(), false);
                writeField(sb, "triggerTime", e.triggerTime(), false);
                sb.append('}');
            }
            case WorkQueueMessage e -> {
                sb.append('{');
                writeField(sb, "type", "WorkQueueMessage", true);
                writeField(sb, "workQueueId", e.workQueueId(), false);
                writeField(sb, "status", e.status(), false);
                writeField(sb, "qcMudaSeconds", e.qcMudaSeconds(), false);
                writeField(sb, "loadMode", e.loadMode(), false);
                sb.append('}');
            }
            case WorkInstructionEvent e -> {
                sb.append('{');
                writeField(sb, "type", "WorkInstructionEvent", true);
                writeField(sb, "eventType", e.eventType(), false);
                writeField(sb, "workInstructionId", e.workInstructionId(), false);
                writeField(sb, "workQueueId", e.workQueueId(), false);
                writeField(sb, "fetchChe", e.fetchChe(), false);
                writeField(sb, "workInstructionMoveStage", e.workInstructionMoveStage(), false);
                writeField(sb, "estimatedMoveTime", e.estimatedMoveTime(), false);
                writeField(sb, "estimatedCycleTimeSeconds", e.estimatedCycleTimeSeconds(), false);
                writeField(sb, "estimatedRtgCycleTimeSeconds", e.estimatedRtgCycleTimeSeconds(), false);
                writeField(sb, "putChe", e.putChe(), false);
                writeField(sb, "isTwinFetch", e.isTwinFetch(), false);
                writeField(sb, "isTwinPut", e.isTwinPut(), false);
                writeField(sb, "isTwinCarry", e.isTwinCarry(), false);
                writeField(sb, "twinCompanionWorkInstruction", e.twinCompanionWorkInstruction(), false);
                writeField(sb, "toPosition", e.toPosition(), false);
                writeField(sb, "containerId", e.containerId(), false);
                sb.append('}');
            }
            case ActionCompletedEvent e -> {
                sb.append('{');
                writeField(sb, "type", "ActionCompletedEvent", true);
                writeField(sb, "actionId", e.actionId(), false);
                writeField(sb, "workQueueId", e.workQueueId(), false);
                sb.append('}');
            }
            case OverrideConditionEvent e -> {
                sb.append('{');
                writeField(sb, "type", "OverrideConditionEvent", true);
                writeField(sb, "workQueueId", e.workQueueId(), false);
                writeField(sb, "taktName", e.taktName(), false);
                writeField(sb, "conditionId", e.conditionId(), false);
                sb.append('}');
            }
            case OverrideActionConditionEvent e -> {
                sb.append('{');
                writeField(sb, "type", "OverrideActionConditionEvent", true);
                writeField(sb, "workQueueId", e.workQueueId(), false);
                writeField(sb, "actionId", e.actionId(), false);
                writeField(sb, "conditionId", e.conditionId(), false);
                sb.append('}');
            }
            case DigitalMapEvent e -> {
                sb.append('{');
                writeField(sb, "type", "DigitalMapEvent", true);
                writeField(sb, "mapPayload", e.mapPayload(), false);
                sb.append('}');
            }
            case NukeWorkQueueEvent e -> {
                sb.append('{');
                writeField(sb, "type", "NukeWorkQueueEvent", true);
                writeField(sb, "workQueueId", e.workQueueId(), false);
                sb.append('}');
            }
            case WorkInstructionReassigned e -> {
                // Serialize as WorkInstructionEvent so the UI picks it up
                var wi = e.workInstruction();
                sb.append('{');
                writeField(sb, "type", "WorkInstructionEvent", true);
                writeField(sb, "eventType", wi.eventType(), false);
                writeField(sb, "workInstructionId", wi.workInstructionId(), false);
                writeField(sb, "workQueueId", wi.workQueueId(), false);
                writeField(sb, "fetchChe", wi.fetchChe(), false);
                writeField(sb, "workInstructionMoveStage", wi.workInstructionMoveStage(), false);
                writeField(sb, "estimatedMoveTime", wi.estimatedMoveTime(), false);
                writeField(sb, "estimatedCycleTimeSeconds", wi.estimatedCycleTimeSeconds(), false);
                writeField(sb, "estimatedRtgCycleTimeSeconds", wi.estimatedRtgCycleTimeSeconds(), false);
                writeField(sb, "putChe", wi.putChe(), false);
                writeField(sb, "isTwinFetch", wi.isTwinFetch(), false);
                writeField(sb, "isTwinPut", wi.isTwinPut(), false);
                writeField(sb, "isTwinCarry", wi.isTwinCarry(), false);
                writeField(sb, "twinCompanionWorkInstruction", wi.twinCompanionWorkInstruction(), false);
                writeField(sb, "toPosition", wi.toPosition(), false);
                writeField(sb, "containerId", wi.containerId(), false);
                sb.append('}');
            }
            case ScheduleCreated e -> {
                sb.append('{');
                writeField(sb, "type", "ScheduleCreated", true);
                writeField(sb, "workQueueId", e.workQueueId(), false);
                writeFieldKey(sb, "takts", false);
                writeValue(sb, e.takts());
                writeField(sb, "estimatedMoveTime", e.estimatedMoveTime(), false);
                sb.append('}');
            }
            case TaktActivated e -> {
                sb.append('{');
                writeField(sb, "type", "TaktActivated", true);
                writeField(sb, "workQueueId", e.workQueueId(), false);
                writeField(sb, "taktName", e.taktName(), false);
                writeField(sb, "activatedAt", e.activatedAt(), false);
                sb.append('}');
            }
            case TaktCompleted e -> {
                sb.append('{');
                writeField(sb, "type", "TaktCompleted", true);
                writeField(sb, "workQueueId", e.workQueueId(), false);
                writeField(sb, "taktName", e.taktName(), false);
                writeField(sb, "completedAt", e.completedAt(), false);
                sb.append('}');
            }
            case ContainerHandlingEquipmentEvent e -> {
                sb.append('{');
                writeField(sb, "type", "ContainerHandlingEquipmentEvent", true);
                writeField(sb, "eventType", e.eventType(), false);
                writeField(sb, "cheId", e.cheId(), false);
                writeField(sb, "opType", e.opType(), false);
                writeField(sb, "cdhTerminalCode", e.cdhTerminalCode(), false);
                writeField(sb, "messageSequenceNumber", e.messageSequenceNumber(), false);
                writeField(sb, "cheShortName", e.cheShortName(), false);
                writeField(sb, "cheStatus", e.cheStatus() != null ? e.cheStatus().displayName() : null, false);
                writeField(sb, "cheKind", e.cheKind(), false);
                writeField(sb, "chePoolId", e.chePoolId(), false);
                writeField(sb, "cheJobStepState", e.cheJobStepState() != null ? e.cheJobStepState().code() : null, false);
                writeField(sb, "sourceTsMs", e.sourceTsMs(), false);
                sb.append('}');
            }
            default -> writeString(sb, event.toString());
        }
    }

    private static void writeSideEffect(StringBuilder sb, SideEffect sideEffect) {
        switch (sideEffect) {
            case AlarmSet e -> {
                sb.append('{');
                writeField(sb, "type", "AlarmSet", true);
                writeField(sb, "alarmName", e.alarmName(), false);
                writeField(sb, "triggerTime", e.triggerTime(), false);
                sb.append('}');
            }
            case AlarmTriggered e -> {
                sb.append('{');
                writeField(sb, "type", "AlarmTriggered", true);
                writeField(sb, "alarmName", e.alarmName(), false);
                writeField(sb, "triggeredAt", e.triggeredAt(), false);
                sb.append('}');
            }
            case ScheduleCreated e -> {
                sb.append('{');
                writeField(sb, "type", "ScheduleCreated", true);
                writeField(sb, "workQueueId", e.workQueueId(), false);
                writeFieldKey(sb, "takts", false);
                writeValue(sb, e.takts());
                writeField(sb, "estimatedMoveTime", e.estimatedMoveTime(), false);
                sb.append('}');
            }
            case ScheduleAborted e -> {
                sb.append('{');
                writeField(sb, "type", "ScheduleAborted", true);
                writeField(sb, "workQueueId", e.workQueueId(), false);
                sb.append('}');
            }
            case WorkInstructionReassigned e -> {
                var wi = e.workInstruction();
                sb.append('{');
                writeField(sb, "type", "WorkInstructionReassigned", true);
                writeField(sb, "workInstructionId", wi.workInstructionId(), false);
                writeField(sb, "workQueueId", wi.workQueueId(), false);
                sb.append('}');
            }
            case ActionActivated e -> {
                sb.append('{');
                writeField(sb, "type", "ActionActivated", true);
                writeField(sb, "actionId", e.actionId(), false);
                writeField(sb, "workQueueId", e.workQueueId(), false);
                writeField(sb, "taktName", e.taktName(), false);
                writeField(sb, "actionDescription", e.actionDescription(), false);
                writeField(sb, "activatedAt", e.activatedAt(), false);
                if (e.deviceType() != null) {
                    writeField(sb, "deviceType", e.deviceType().name(), false);
                }
                if (e.workInstructions() != null && !e.workInstructions().isEmpty()) {
                    writeFieldKey(sb, "workInstructions", false);
                    writeValue(sb, e.workInstructions().stream()
                            .map(WebViewWorkInstruction::new)
                            .toList());
                }
                sb.append('}');
            }
            case ActionCompleted e -> {
                sb.append('{');
                writeField(sb, "type", "ActionCompleted", true);
                writeField(sb, "actionId", e.actionId(), false);
                writeField(sb, "workQueueId", e.workQueueId(), false);
                writeField(sb, "taktName", e.taktName(), false);
                writeField(sb, "actionDescription", e.actionDescription(), false);
                writeField(sb, "completedAt", e.completedAt(), false);
                sb.append('}');
            }
            case TaktActivated e -> {
                sb.append('{');
                writeField(sb, "type", "TaktActivated", true);
                writeField(sb, "workQueueId", e.workQueueId(), false);
                writeField(sb, "taktName", e.taktName(), false);
                writeField(sb, "activatedAt", e.activatedAt(), false);
                sb.append('}');
            }
            case TaktCompleted e -> {
                sb.append('{');
                writeField(sb, "type", "TaktCompleted", true);
                writeField(sb, "workQueueId", e.workQueueId(), false);
                writeField(sb, "taktName", e.taktName(), false);
                writeField(sb, "completedAt", e.completedAt(), false);
                sb.append('}');
            }
            case DelayUpdated e -> {
                sb.append('{');
                writeField(sb, "type", "DelayUpdated", true);
                writeField(sb, "workQueueId", e.workQueueId(), false);
                writeField(sb, "totalDelaySeconds", e.totalDelaySeconds(), false);
                sb.append('}');
            }
            case TTStateUpdated e -> {
                sb.append('{');
                writeField(sb, "type", "TTStateUpdated", true);
                writeField(sb, "cheShortName", e.cheShortName(), false);
                var evt = e.event();
                if (evt.cheStatus() != null) writeField(sb, "cheStatus", evt.cheStatus().displayName(), false);
                if (evt.cheJobStepState() != null) writeField(sb, "cheJobStepState", evt.cheJobStepState().code(), false);
                sb.append('}');
            }
            case TruckAssigned e -> {
                sb.append('{');
                writeField(sb, "type", "TruckAssigned", true);
                writeField(sb, "actionId", e.actionId(), false);
                writeField(sb, "workQueueId", e.workQueueId(), false);
                writeField(sb, "cheShortName", e.cheShortName(), false);
                writeField(sb, "cheId", e.cheId(), false);
                sb.append('}');
            }
        }
    }

    private static void writeTakt(StringBuilder sb, Takt takt) {
        sb.append('{');
        writeField(sb, "name", takt.name(), true);
        writeField(sb, "plannedStartTime", takt.plannedStartTime(), false);
        writeField(sb, "estimatedStartTime", takt.estimatedStartTime(), false);
        writeField(sb, "durationSeconds", takt.durationSeconds(), false);
        writeFieldKey(sb, "actions", false);
        writeValue(sb, takt.actions());
        sb.append('}');
    }

    private static void writeAction(StringBuilder sb, Action action) {
        sb.append('{');
        writeField(sb, "id", action.id(), true);
        writeField(sb, "deviceType", action.deviceType(), false);
        writeField(sb, "description", action.description(), false);
        writeFieldKey(sb, "dependsOn", false);
        writeValue(sb, action.dependsOn() != null ? action.dependsOn().stream().toList() : List.of());
        writeField(sb, "containerIndex", action.containerIndex(), false);
        writeField(sb, "durationSeconds", action.durationSeconds(), false);
        writeField(sb, "deviceIndex", action.deviceIndex(), false);
        if (action.cheId() != null) writeField(sb, "cheId", action.cheId(), false);
        if (action.cheShortName() != null) writeField(sb, "cheShortName", action.cheShortName(), false);
        sb.append('}');
    }

    private static void writeWebViewWorkInstruction(StringBuilder sb, WebViewWorkInstruction wvwi) {
        var wi = wvwi.workInstruction();
        sb.append('{');
        writeField(sb, "eventType", wi.eventType(), true);
        writeField(sb, "workInstructionId", wi.workInstructionId(), false);
        writeField(sb, "workQueueId", wi.workQueueId(), false);
        writeField(sb, "fetchChe", wi.fetchChe(), false);
        writeField(sb, "workInstructionMoveStage", wi.workInstructionMoveStage(), false);
        writeField(sb, "estimatedMoveTime", wi.estimatedMoveTime(), false);
        writeField(sb, "estimatedCycleTimeSeconds", wi.estimatedCycleTimeSeconds(), false);
        writeField(sb, "estimatedRtgCycleTimeSeconds", wi.estimatedRtgCycleTimeSeconds(), false);
        writeField(sb, "putChe", wi.putChe(), false);
        writeField(sb, "isTwinFetch", wi.isTwinFetch(), false);
        writeField(sb, "isTwinPut", wi.isTwinPut(), false);
        writeField(sb, "isTwinCarry", wi.isTwinCarry(), false);
        writeField(sb, "twinCompanionWorkInstruction", wi.twinCompanionWorkInstruction(), false);
        writeField(sb, "toPosition", wi.toPosition(), false);
        writeField(sb, "containerId", wi.containerId(), false);
        sb.append('}');
    }

    private static void writeStep(StringBuilder sb, DemoServer.Step step) {
        sb.append('{');
        writeField(sb, "stepNumber", step.stepNumber(), true);
        writeField(sb, "description", step.description(), false);
        writeFieldKey(sb, "event", false);
        writeValue(sb, step.event());
        writeFieldKey(sb, "sideEffects", false);
        writeValue(sb, step.sideEffects());
        sb.append('}');
    }

    private static void writeActionState(StringBuilder sb, DemoServer.ActionState actionState) {
        writeString(sb, actionState.name());
    }

    private static void writeScheduleView(StringBuilder sb, DemoServer.ScheduleView view) {
        sb.append('{');
        writeField(sb, "workQueueId", view.workQueueId(), true);
        writeField(sb, "active", view.active(), false);
        writeField(sb, "estimatedMoveTime", view.estimatedMoveTime(), false);
        writeField(sb, "totalDelaySeconds", view.totalDelaySeconds(), false);
        writeFieldKey(sb, "takts", false);
        writeValue(sb, view.takts());
        sb.append('}');
    }

    private static void writeTaktView(StringBuilder sb, DemoServer.TaktView view) {
        sb.append('{');
        writeField(sb, "name", view.name(), true);
        writeField(sb, "status", view.status(), false);
        writeField(sb, "plannedStartTime", view.plannedStartTime(), false);
        writeField(sb, "estimatedStartTime", view.estimatedStartTime(), false);
        writeField(sb, "actualStartTime", view.actualStartTime(), false);
        writeField(sb, "completedAt", view.completedAt(), false);
        writeField(sb, "durationSeconds", view.durationSeconds(), false);
        writeField(sb, "startDelaySeconds", view.startDelaySeconds(), false);
        writeField(sb, "taktDelaySeconds", view.taktDelaySeconds(), false);
        writeFieldKey(sb, "actions", false);
        writeValue(sb, view.actions());
        writeFieldKey(sb, "conditions", false);
        writeValue(sb, view.conditions());
        sb.append('}');
    }

    private static void writeTaktState(StringBuilder sb, DemoServer.TaktState taktState) {
        writeString(sb, taktState.name());
    }

    private static void writeActionView(StringBuilder sb, DemoServer.ActionView view) {
        sb.append('{');
        writeField(sb, "id", view.id(), true);
        writeField(sb, "deviceType", view.deviceType(), false);
        writeField(sb, "description", view.description(), false);
        writeField(sb, "status", view.status(), false);
        writeFieldKey(sb, "dependsOn", false);
        writeValue(sb, view.dependsOn() != null ? view.dependsOn().stream().toList() : List.of());
        writeField(sb, "containerIndex", view.containerIndex(), false);
        writeField(sb, "durationSeconds", view.durationSeconds(), false);
        writeField(sb, "deviceIndex", view.deviceIndex(), false);
        writeFieldKey(sb, "conditions", false);
        writeValue(sb, view.conditions() != null ? view.conditions() : List.of());
        writeFieldKey(sb, "containerIds", false);
        writeValue(sb, view.containerIds() != null ? view.containerIds() : List.of());
        if (view.cheShortName() != null) writeField(sb, "cheShortName", view.cheShortName(), false);
        sb.append('}');
    }

    private static void writeConditionView(StringBuilder sb, DemoServer.ConditionView view) {
        sb.append('{');
        writeField(sb, "id", view.id(), true);
        writeField(sb, "type", view.type(), false);
        writeField(sb, "satisfied", view.satisfied(), false);
        writeField(sb, "overridden", view.overridden(), false);
        writeField(sb, "explanation", view.explanation(), false);
        sb.append('}');
    }

    private static void writeField(StringBuilder sb, String key, Object value, boolean first) {
        if (!first) sb.append(',');
        writeString(sb, key);
        sb.append(':');
        writeValue(sb, value);
    }

    private static void writeFieldKey(StringBuilder sb, String key, boolean first) {
        if (!first) sb.append(',');
        writeString(sb, key);
        sb.append(':');
    }
}
