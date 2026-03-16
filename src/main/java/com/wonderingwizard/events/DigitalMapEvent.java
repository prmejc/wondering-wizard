package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

/**
 * Event representing a digital map update for a terminal.
 *
 * <p>When processed by the {@link com.wonderingwizard.processors.DigitalMapProcessor},
 * the map payload is parsed into a graph structure used for pathfinding between
 * yard positions. The computed travel durations are applied to TT drive actions
 * during schedule creation.
 *
 * <p>Existing schedules are not affected by map updates; only new schedules
 * created after this event will use the updated map data.
 *
 * @param mapPayload the raw digital map data as a JSON string containing nodes and edges
 */
public record DigitalMapEvent(
        String mapPayload
) implements Event {
}
