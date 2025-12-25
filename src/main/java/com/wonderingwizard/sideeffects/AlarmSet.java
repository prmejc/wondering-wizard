package com.wonderingwizard.sideeffects;

import com.wonderingwizard.engine.SideEffect;

import java.time.Instant;

/**
 * Side effect indicating that an alarm has been set.
 *
 * @param alarmName the name of the alarm that was set
 * @param triggerTime the time at which the alarm will trigger
 */
public record AlarmSet(String alarmName, Instant triggerTime) implements SideEffect {

    @Override
    public String toString() {
        return "AlarmSet[alarmName=" + alarmName + ", triggerTime=" + triggerTime + "]";
    }
}
