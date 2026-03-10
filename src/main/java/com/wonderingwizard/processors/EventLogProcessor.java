package com.wonderingwizard.processors;

import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.EventProcessor;
import com.wonderingwizard.engine.SideEffect;

import java.util.ArrayList;
import java.util.List;

/**
 * Processor that records every event passing through the system into an in-memory log.
 * <p>
 * This processor produces no side effects — its sole purpose is to maintain an ordered
 * event log that can be exported and later imported to restore system state.
 */
public class EventLogProcessor implements EventProcessor {

    private final List<Event> eventLog = new ArrayList<>();

    @Override
    public List<SideEffect> process(Event event) {
        eventLog.add(event);
        return List.of();
    }

    /**
     * Returns an unmodifiable view of the recorded event log.
     *
     * @return the ordered list of all events processed
     */
    public List<Event> getEventLog() {
        return List.copyOf(eventLog);
    }

    /**
     * Clears the event log (used when importing a new event sequence).
     */
    public void clear() {
        eventLog.clear();
    }

    @Override
    public Object captureState() {
        return new ArrayList<>(eventLog);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restoreState(Object state) {
        if (!(state instanceof List)) {
            throw new IllegalArgumentException("Invalid state type for EventLogProcessor");
        }
        eventLog.clear();
        eventLog.addAll((List<Event>) state);
    }
}