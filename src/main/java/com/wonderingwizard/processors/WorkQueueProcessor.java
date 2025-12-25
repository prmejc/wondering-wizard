package com.wonderingwizard.processors;

import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.EventProcessor;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.SetTimeAlarm;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.sideeffects.ScheduleAborted;
import com.wonderingwizard.sideeffects.ScheduleCreated;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Processor that handles work queue messages and manages schedule creation.
 * <p>
 * When a WorkQueueMessage with status "Active" is processed:
 * - If no schedule exists for the workQueueId, a new schedule is created (ScheduleCreated side effect)
 * - If a schedule already exists for the workQueueId, no side effect is produced (idempotent)
 * <p>
 * When a WorkQueueMessage with status "Inactive" is processed:
 * - If a schedule exists for the workQueueId, it is aborted (ScheduleAborted side effect)
 * - If no schedule exists, no side effect is produced
 */
public class WorkQueueProcessor implements EventProcessor {

    private final Map<String, Boolean> activeSchedules = new HashMap<>();

    @Override
    public List<SideEffect> process(Event event) {
        return switch (event) {
            case WorkQueueMessage message -> handleWorkQueueMessage(message);
            case TimeEvent ignored -> List.of();
            case SetTimeAlarm ignored -> List.of();
        };
    }

    private List<SideEffect> handleWorkQueueMessage(WorkQueueMessage message) {
        String workQueueId = message.workQueueId();
        String status = message.status();

        if ("Active".equals(status)) {
            return handleActiveStatus(workQueueId);
        } else if ("Inactive".equals(status)) {
            return handleInactiveStatus(workQueueId);
        }

        return List.of();
    }

    private List<SideEffect> handleActiveStatus(String workQueueId) {
        if (activeSchedules.containsKey(workQueueId)) {
            // Schedule already exists, idempotent - no side effect
            return List.of();
        }

        // Create new schedule
        activeSchedules.put(workQueueId, true);
        return List.of(new ScheduleCreated(workQueueId));
    }

    private List<SideEffect> handleInactiveStatus(String workQueueId) {
        if (!activeSchedules.containsKey(workQueueId)) {
            // No schedule exists, nothing to abort
            return List.of();
        }

        // Abort the schedule
        activeSchedules.remove(workQueueId);
        return List.of(new ScheduleAborted(workQueueId));
    }
}
