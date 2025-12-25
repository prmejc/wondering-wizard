package com.wonderingwizard.processors;

import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.EventProcessor;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.SetTimeAlarm;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.sideeffects.AlarmSet;
import com.wonderingwizard.sideeffects.AlarmTriggered;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Processor that handles time-based alarms.
 * <p>
 * When a SetTimeAlarm event is received, the alarm is stored and an AlarmSet side effect is produced.
 * When a TimeEvent is received, any alarms whose trigger time has passed are triggered and
 * AlarmTriggered side effects are produced.
 */
public class TimeAlarmProcessor implements EventProcessor {

    private final Map<String, Instant> pendingAlarms = new HashMap<>();

    @Override
    public List<SideEffect> process(Event event) {
        return switch (event) {
            case SetTimeAlarm setAlarm -> handleSetAlarm(setAlarm);
            case TimeEvent timeEvent -> handleTimeEvent(timeEvent);
            case WorkQueueMessage ignored -> List.of();
        };
    }

    private List<SideEffect> handleSetAlarm(SetTimeAlarm setAlarm) {
        pendingAlarms.put(setAlarm.alarmName(), setAlarm.triggerTime());
        return List.of(new AlarmSet(setAlarm.alarmName(), setAlarm.triggerTime()));
    }

    private List<SideEffect> handleTimeEvent(TimeEvent timeEvent) {
        List<SideEffect> triggeredAlarms = new ArrayList<>();
        Instant currentTime = timeEvent.timestamp();

        Iterator<Map.Entry<String, Instant>> iterator = pendingAlarms.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Instant> entry = iterator.next();
            if (!entry.getValue().isAfter(currentTime)) {
                triggeredAlarms.add(new AlarmTriggered(entry.getKey(), currentTime));
                iterator.remove();
            }
        }

        return triggeredAlarms;
    }

    @Override
    public Object captureState() {
        return new HashMap<>(pendingAlarms);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restoreState(Object state) {
        if (!(state instanceof Map)) {
            throw new IllegalArgumentException("Invalid state type for TimeAlarmProcessor");
        }
        pendingAlarms.clear();
        pendingAlarms.putAll((Map<String, Instant>) state);
    }
}
