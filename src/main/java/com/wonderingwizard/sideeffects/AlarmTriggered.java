package com.wonderingwizard.sideeffects;

import com.wonderingwizard.engine.SideEffect;

import java.time.Instant;

/**
 * Side effect indicating that an alarm has been triggered.
 *
 * @param alarmName the name of the alarm that was triggered
 * @param triggeredAt the time at which the alarm was triggered
 */
public record AlarmTriggered(String alarmName, Instant triggeredAt) implements SideEffect {

    @Override
    public String toString() {
        return "AlarmTriggered[alarmName=" + alarmName + ", triggeredAt=" + triggeredAt + "]";
    }
}
