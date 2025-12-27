package com.wonderingwizard.engine;

/**
 * Marker interface for all side effects produced by event processing.
 * Side effects represent actions to be taken as a result of processing events.
 */
public sealed interface SideEffect permits
        com.wonderingwizard.sideeffects.AlarmSet,
        com.wonderingwizard.sideeffects.AlarmTriggered,
        com.wonderingwizard.sideeffects.ScheduleCreated,
        com.wonderingwizard.sideeffects.ScheduleAborted,
        com.wonderingwizard.sideeffects.ActionActivated,
        com.wonderingwizard.sideeffects.ActionCompleted {
}
