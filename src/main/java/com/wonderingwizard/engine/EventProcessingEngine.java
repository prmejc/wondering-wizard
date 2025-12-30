package com.wonderingwizard.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The main event processing engine that coordinates event processors.
 * All events and resulting side effects are logged.
 * <p>
 * Supports step-back functionality to revert to the state before the last processed event.
 * <p>
 * Implements the {@link Engine} interface.
 */
public class EventProcessingEngine implements Engine {

    private static final Logger logger = Logger.getLogger(EventProcessingEngine.class.getName());

    private final List<EventProcessor> processors = new ArrayList<>();
    private final List<Map<EventProcessor, Object>> stateHistory = new ArrayList<>();

    /**
     * Register an event processor with this engine.
     *
     * @param processor the processor to register
     */
    public void register(EventProcessor processor) {
        processors.add(processor);
        logger.info("Registered processor: " + processor.getName());
    }

    /**
     * Process an event through all registered processors.
     * The event and all resulting side effects are logged.
     * State is captured before processing to enable step-back.
     *
     * @param event the event to process
     * @return all side effects produced by all processors
     */
    public List<SideEffect> processEvent(Event event) {
        logger.info("Processing event: " + event);

        // Capture state before processing
        Map<EventProcessor, Object> snapshot = captureAllStates();
        stateHistory.add(snapshot);

        List<SideEffect> allSideEffects = new ArrayList<>();

        for (EventProcessor processor : processors) {
            List<SideEffect> sideEffects = processor.process(event);
            allSideEffects.addAll(sideEffects);
        }

        if (allSideEffects.isEmpty()) {
            logger.info("No side effects produced");
        } else {
            for (SideEffect sideEffect : allSideEffects) {
                logger.info("Side effect: " + sideEffect);
            }
        }

        return allSideEffects;
    }

    /**
     * Reverts the engine to the state before the last processed event.
     *
     * @return true if step-back was successful, false if there is no history to revert to
     */
    public boolean stepBack() {
        if (stateHistory.isEmpty()) {
            logger.info("No history to step back to");
            return false;
        }

        Map<EventProcessor, Object> previousState = stateHistory.remove(stateHistory.size() - 1);
        restoreAllStates(previousState);
        logger.info("Stepped back to previous state");
        return true;
    }

    /**
     * Returns the number of steps that can be reverted.
     *
     * @return the number of available step-back operations
     */
    public int getHistorySize() {
        return stateHistory.size();
    }

    /**
     * Clears all state history to free memory.
     */
    public void clearHistory() {
        stateHistory.clear();
        logger.info("State history cleared");
    }

    private Map<EventProcessor, Object> captureAllStates() {
        Map<EventProcessor, Object> snapshot = new HashMap<>();
        for (EventProcessor processor : processors) {
            snapshot.put(processor, processor.captureState());
        }
        return snapshot;
    }

    private void restoreAllStates(Map<EventProcessor, Object> snapshot) {
        for (EventProcessor processor : processors) {
            Object state = snapshot.get(processor);
            if (state != null) {
                processor.restoreState(state);
            }
        }
    }
}
