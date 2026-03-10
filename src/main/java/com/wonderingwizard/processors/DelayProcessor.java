package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Takt;
import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.EventProcessor;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.events.WorkQueueStatus;
import com.wonderingwizard.sideeffects.DelayUpdated;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import com.wonderingwizard.sideeffects.TaktActivated;
import com.wonderingwizard.sideeffects.TaktCompleted;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Processor that calculates schedule delays based on takt execution times.
 * <p>
 * On each TimeEvent, the processor checks all active schedules for delays.
 * A takt is delayed when it has been active longer than its planned duration.
 * The total delay propagates to future takts by shifting their estimated start times.
 * <p>
 * Delay definitions:
 * <ul>
 *   <li><b>Start delay:</b> How late a takt started relative to its planned start time.
 *       Computed as {@code actualStartTime - plannedStartTime}.</li>
 *   <li><b>Takt delay (execution delay):</b> How much longer a takt took (or is taking)
 *       beyond its planned duration. For active takts:
 *       {@code max(0, currentTime - actualStartTime - durationSeconds)}.
 *       For completed takts: {@code max(0, completedAt - actualStartTime - durationSeconds)}.</li>
 *   <li><b>Total delay:</b> The cumulative delay of the schedule.
 *       For the active takt: {@code max(0, currentTime - (plannedStartTime + durationSeconds))}.
 *       Decreases when takts complete faster than their planned duration.</li>
 * </ul>
 */
public class DelayProcessor implements EventProcessor {

    private static class TaktInfo {
        final String name;
        final Instant plannedStartTime;
        final int durationSeconds;
        Instant actualStartTime;
        Instant completedAt;

        TaktInfo(String name, Instant plannedStartTime, int durationSeconds) {
            this.name = name;
            this.plannedStartTime = plannedStartTime;
            this.durationSeconds = durationSeconds;
        }

        TaktInfo copy() {
            TaktInfo copy = new TaktInfo(name, plannedStartTime, durationSeconds);
            copy.actualStartTime = this.actualStartTime;
            copy.completedAt = this.completedAt;
            return copy;
        }
    }

    private static class ScheduleDelayState {
        final List<TaktInfo> takts = new ArrayList<>();
        final Map<String, TaktInfo> taktByName = new LinkedHashMap<>();
        long lastEmittedDelay = -1;

        ScheduleDelayState copy() {
            ScheduleDelayState copy = new ScheduleDelayState();
            for (TaktInfo takt : takts) {
                TaktInfo taktCopy = takt.copy();
                copy.takts.add(taktCopy);
                copy.taktByName.put(taktCopy.name, taktCopy);
            }
            copy.lastEmittedDelay = this.lastEmittedDelay;
            return copy;
        }
    }

    private final Map<Long, ScheduleDelayState> scheduleStates = new HashMap<>();
    private Instant currentTime = Instant.EPOCH;

    @Override
    public List<SideEffect> process(Event event) {
        if (event instanceof ScheduleCreated created) {
            return handleScheduleCreated(created);
        }
        if (event instanceof WorkQueueMessage message
                && message.status() == WorkQueueStatus.INACTIVE) {
            scheduleStates.remove(message.workQueueId());
            return List.of();
        }
        if (event instanceof TaktActivated activated) {
            return handleTaktActivated(activated);
        }
        if (event instanceof TaktCompleted completed) {
            return handleTaktCompleted(completed);
        }
        if (event instanceof TimeEvent timeEvent) {
            return handleTimeEvent(timeEvent);
        }
        return List.of();
    }

    private List<SideEffect> handleScheduleCreated(ScheduleCreated created) {
        ScheduleDelayState state = new ScheduleDelayState();
        for (Takt takt : created.takts()) {
            TaktInfo info = new TaktInfo(takt.name(), takt.plannedStartTime(), takt.durationSeconds());
            state.takts.add(info);
            state.taktByName.put(takt.name(), info);
        }
        scheduleStates.put(created.workQueueId(), state);
        return List.of();
    }

    private List<SideEffect> handleTaktActivated(TaktActivated activated) {
        ScheduleDelayState state = scheduleStates.get(activated.workQueueId());
        if (state == null) {
            return List.of();
        }
        TaktInfo takt = state.taktByName.get(activated.taktName());
        if (takt != null) {
            takt.actualStartTime = activated.activatedAt();
        }
        return List.of();
    }

    private List<SideEffect> handleTaktCompleted(TaktCompleted completed) {
        ScheduleDelayState state = scheduleStates.get(completed.workQueueId());
        if (state == null) {
            return List.of();
        }
        TaktInfo takt = state.taktByName.get(completed.taktName());
        if (takt != null) {
            takt.completedAt = completed.completedAt();
        }
        return List.of();
    }

    private List<SideEffect> handleTimeEvent(TimeEvent timeEvent) {
        this.currentTime = timeEvent.timestamp();
        List<SideEffect> sideEffects = new ArrayList<>();

        for (Map.Entry<Long, ScheduleDelayState> entry : scheduleStates.entrySet()) {
            long workQueueId = entry.getKey();
            ScheduleDelayState state = entry.getValue();

            long totalDelay = calculateTotalDelay(state);

            if (totalDelay != state.lastEmittedDelay) {
                state.lastEmittedDelay = totalDelay;
                sideEffects.add(new DelayUpdated(workQueueId, totalDelay));
            }
        }

        return sideEffects;
    }

    /**
     * Calculates the total delay for a schedule.
     * <p>
     * The total delay is determined by finding the most advanced takt
     * (the last active or completed takt) and computing how far behind
     * the planned schedule it is.
     */
    long calculateTotalDelay(ScheduleDelayState state) {
        // Find the most advanced takt (last one that has started or completed)
        TaktInfo lastActiveTakt = null;
        TaktInfo lastCompletedTakt = null;

        for (TaktInfo takt : state.takts) {
            if (takt.completedAt != null) {
                lastCompletedTakt = takt;
            } else if (takt.actualStartTime != null) {
                lastActiveTakt = takt;
            }
        }

        // If there's an active takt, calculate delay based on it
        if (lastActiveTakt != null && lastActiveTakt.plannedStartTime != null) {
            Instant plannedEnd = lastActiveTakt.plannedStartTime
                    .plusSeconds(lastActiveTakt.durationSeconds);
            long delaySeconds = Duration.between(plannedEnd, currentTime).getSeconds();
            // Only report delay when takt has reached or passed its planned end
            if (delaySeconds < 0) {
                return -1;
            }
            return delaySeconds;
        }

        // If no active takt but there are completed takts, calculate delay from last completed
        if (lastCompletedTakt != null && lastCompletedTakt.plannedStartTime != null) {
            Instant plannedEnd = lastCompletedTakt.plannedStartTime
                    .plusSeconds(lastCompletedTakt.durationSeconds);
            long delaySeconds = Duration.between(plannedEnd, lastCompletedTakt.completedAt).getSeconds();
            return Math.max(0, delaySeconds);
        }

        // No takt has been activated or completed yet — no delay to report
        return -1;
    }

    @Override
    public Object captureState() {
        Map<String, Object> snapshot = new HashMap<>();

        Map<Long, ScheduleDelayState> statesCopy = new HashMap<>();
        for (Map.Entry<Long, ScheduleDelayState> entry : scheduleStates.entrySet()) {
            statesCopy.put(entry.getKey(), entry.getValue().copy());
        }
        snapshot.put("scheduleStates", statesCopy);
        snapshot.put("currentTime", currentTime);

        return snapshot;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restoreState(Object state) {
        if (!(state instanceof Map)) {
            throw new IllegalArgumentException("Invalid state type for DelayProcessor");
        }

        Map<String, Object> stateMap = (Map<String, Object>) state;

        scheduleStates.clear();
        Object schedulesState = stateMap.get("scheduleStates");
        if (schedulesState instanceof Map) {
            Map<Long, ScheduleDelayState> schedulesMap = (Map<Long, ScheduleDelayState>) schedulesState;
            for (Map.Entry<Long, ScheduleDelayState> entry : schedulesMap.entrySet()) {
                scheduleStates.put(entry.getKey(), entry.getValue().copy());
            }
        }

        Object currentTimeState = stateMap.get("currentTime");
        if (currentTimeState instanceof Instant instant) {
            this.currentTime = instant;
        }
    }
}
