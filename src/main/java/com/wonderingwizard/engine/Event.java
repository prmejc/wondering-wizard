package com.wonderingwizard.engine;

/**
 * Marker interface for all events processed by the engine.
 * Events are immutable data carriers that trigger processing logic.
 * <p>
 * Processors should handle only the events they care about and return
 * an empty list of side effects for all other events.
 */
public interface Event {
}
