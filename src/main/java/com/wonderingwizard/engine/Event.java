package com.wonderingwizard.engine;

/**
 * Marker interface for all events processed by the engine.
 * Events are immutable data carriers that trigger processing logic.
 */
public sealed interface Event permits
        com.wonderingwizard.events.TimeEvent,
        com.wonderingwizard.events.SetTimeAlarm,
        com.wonderingwizard.events.WorkQueueMessage {
}
