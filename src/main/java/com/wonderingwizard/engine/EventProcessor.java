package com.wonderingwizard.engine;

import java.util.List;

/**
 * Interface for event processors that can handle events and produce side effects.
 * Processors are registered with the engine and called for each event.
 * <p>
 * Processors support state capture and restoration for undo/step-back functionality.
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

    /**
     * Captures the current state of this processor for later restoration.
     * The returned object is opaque and should only be passed to {@link #restoreState(Object)}.
     *
     * @return an opaque state snapshot
     */
    Object captureState();

    /**
     * Restores the processor to a previously captured state.
     *
     * @param state the state snapshot previously returned by {@link #captureState()}
     * @throws IllegalArgumentException if the state is not a valid snapshot for this processor
     */
    void restoreState(Object state);
}
