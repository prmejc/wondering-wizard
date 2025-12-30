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
     * Reverts the engine to the state before the last processed event.
     *
     * @return true if step-back was successful, false if there is no history to revert to
     */
    boolean stepBack();

    /**
     * Returns the number of steps that can be reverted.
     *
     * @return the number of available step-back operations
     */
    int getHistorySize();

    /**
     * Clears all state history to free memory.
     */
    void clearHistory();
}
