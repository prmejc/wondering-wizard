package com.wonderingwizard.engine;

import java.util.List;

/**
 * Interface for event processors that can handle events and produce side effects.
 * Processors are registered with the engine and called for each event.
 */
public interface EventProcessor {

    /**
     * Process an event and return any resulting side effects.
     *
     * @param event the event to process
     * @return list of side effects produced by processing this event, may be empty
     */
    List<SideEffect> process(Event event);

    /**
     * Returns the name of this processor for logging purposes.
     *
     * @return the processor name
     */
    default String getName() {
        return getClass().getSimpleName();
    }
}
