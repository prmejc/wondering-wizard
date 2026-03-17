package com.wonderingwizard.engine;

import java.util.List;

/**
 * Interface for event processing engines.
 * Defines the contract for engines that process events through registered processors.
 */
public interface Engine {

    /**
     * Register an event processor with this engine.
     *
     * @param processor the processor to register
     */
    void register(EventProcessor processor);

    /**
     * Process an event through all registered processors.
     *
     * @param event the event to process
     * @return all side effects produced by all processors
     */
    List<SideEffect> processEvent(Event event);

    /**
     * Captures a snapshot of all processor state for step-back.
     * Call this before processing a user step.
     */
    void snapshot();

    /**
     * Reverts the engine to the most recent snapshot.
     *
     * @return true if step-back was successful, false if there is no history to revert to
     */
    boolean stepBack();

    /**
     * Returns the number of snapshots available for step-back.
     *
     * @return the number of available step-back operations
     */
    int getHistorySize();

    /**
     * Clears all state history to free memory.
     */
    void clearHistory();
}
