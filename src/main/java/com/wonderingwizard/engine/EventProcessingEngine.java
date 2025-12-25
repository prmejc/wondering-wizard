package com.wonderingwizard.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * The main event processing engine that coordinates event processors.
 * All events and resulting side effects are logged.
 */
public class EventProcessingEngine {

    private static final Logger logger = Logger.getLogger(EventProcessingEngine.class.getName());

    private final List<EventProcessor> processors = new ArrayList<>();

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
     *
     * @param event the event to process
     * @return all side effects produced by all processors
     */
    public List<SideEffect> processEvent(Event event) {
        logger.info("Processing event: " + event);

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
}
