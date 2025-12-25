package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

import java.time.Instant;

/**
 * Event requesting to set a time-based alarm.
 *
 * @param alarmName the name/identifier of the alarm
 * @param triggerTime the time at which the alarm should trigger
 */
public record SetTimeAlarm(String alarmName, Instant triggerTime) implements Event {

    @Override
    public String toString() {
        return "SetTimeAlarm[alarmName=" + alarmName + ", triggerTime=" + triggerTime + "]";
    }
}
