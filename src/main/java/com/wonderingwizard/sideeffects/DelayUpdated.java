package com.wonderingwizard.sideeffects;

import com.wonderingwizard.engine.SideEffect;

/**
 * Side effect produced by the DelayProcessor when the schedule delay changes.
 * <p>
 * Emitted on each TimeEvent when a schedule has a non-zero delay, or when
 * the delay transitions back to zero.
 * <p>
 * The total delay represents how far behind the planned schedule the schedule
 * currently is. It increases when a takt takes longer than its planned duration,
 * and decreases when a takt finishes faster than its planned duration.
 *
 * @param workQueueId the work queue this delay applies to
 * @param totalDelaySeconds the current total delay in seconds (0 or positive)
 */
public record DelayUpdated(
        long workQueueId,
        long totalDelaySeconds
) implements SideEffect {

    @Override
    public String toString() {
        return "DelayUpdated[workQueueId=" + workQueueId +
                ", totalDelaySeconds=" + totalDelaySeconds + "]";
    }
}
