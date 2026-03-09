package com.wonderingwizard.server;

import com.wonderingwizard.domain.takt.Action;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.ActionCompletedEvent;
import com.wonderingwizard.events.SetTimeAlarm;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.sideeffects.ActionActivated;
import com.wonderingwizard.sideeffects.ActionCompleted;
import com.wonderingwizard.sideeffects.AlarmSet;
import com.wonderingwizard.sideeffects.AlarmTriggered;
import com.wonderingwizard.sideeffects.ScheduleAborted;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import com.wonderingwizard.sideeffects.TaktActivated;
import com.wonderingwizard.sideeffects.TaktCompleted;
import com.wonderingwizard.sideeffects.TtCountReport;
import com.wonderingwizard.sideeffects.WorkInstruction;

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
        } else if (obj instanceof WorkInstruction wi) {
            writeWorkInstruction(sb, wi);
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
        } else if (obj instanceof TtCountReport.IntervalEntry entry) {
            writeTtCountIntervalEntry(sb, entry);
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
                sb.append('}');
            }
            case WorkInstructionEvent e -> {
                sb.append('{');
                writeField(sb, "type", "WorkInstructionEvent", true);
                writeField(sb, "workInstructionId", e.workInstructionId(), false);
                writeField(sb, "workQueueId", e.workQueueId(), false);
                writeField(sb, "fetchChe", e.fetchChe(), false);
                writeField(sb, "status", e.status(), false);
                writeField(sb, "estimatedMoveTime", e.estimatedMoveTime(), false);
                writeField(sb, "estimatedCycleTimeSeconds", e.estimatedCycleTimeSeconds(), false);
                writeField(sb, "estimatedRtgCycleTimeSeconds", e.estimatedRtgCycleTimeSeconds(), false);
                sb.append('}');
            }
            case ActionCompletedEvent e -> {
                sb.append('{');
                writeField(sb, "type", "ActionCompletedEvent", true);
                writeField(sb, "actionId", e.actionId(), false);
                writeField(sb, "workQueueId", e.workQueueId(), false);
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
            case ActionActivated e -> {
                sb.append('{');
                writeField(sb, "type", "ActionActivated", true);
                writeField(sb, "actionId", e.actionId(), false);
                writeField(sb, "workQueueId", e.workQueueId(), false);
                writeField(sb, "taktName", e.taktName(), false);
                writeField(sb, "actionDescription", e.actionDescription(), false);
                writeField(sb, "activatedAt", e.activatedAt(), false);
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
            case TtCountReport e -> {
                sb.append('{');
                writeField(sb, "type", "TtCountReport", true);
                writeField(sb, "workQueueId", e.workQueueId(), false);
                writeFieldKey(sb, "intervals", false);
                writeValue(sb, e.intervals());
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
        sb.append('}');
    }

    private static void writeWorkInstruction(StringBuilder sb, WorkInstruction wi) {
        sb.append('{');
        writeField(sb, "workInstructionId", wi.workInstructionId(), true);
        writeField(sb, "workQueueId", wi.workQueueId(), false);
        writeField(sb, "fetchChe", wi.fetchChe(), false);
        writeField(sb, "status", wi.status(), false);
        writeField(sb, "estimatedMoveTime", wi.estimatedMoveTime(), false);
        writeField(sb, "estimatedCycleTimeSeconds", wi.estimatedCycleTimeSeconds(), false);
        writeField(sb, "estimatedRtgCycleTimeSeconds", wi.estimatedRtgCycleTimeSeconds(), false);
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
        writeField(sb, "durationSeconds", view.durationSeconds(), false);
        writeFieldKey(sb, "actions", false);
        writeValue(sb, view.actions());
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
        sb.append('}');
    }

    private static void writeTtCountIntervalEntry(StringBuilder sb, TtCountReport.IntervalEntry entry) {
        sb.append('{');
        writeField(sb, "intervalStart", entry.intervalStart(), true);
        writeField(sb, "ttCount", entry.ttCount(), false);
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
