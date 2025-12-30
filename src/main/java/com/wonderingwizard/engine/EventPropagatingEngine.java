package com.wonderingwizard.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * An engine that propagates internally triggered events from side effects.
 * <p>
 * This engine wraps another engine and delegates all method calls to it, with special behavior for
 * {@link #processEvent(Event)}: after processing an event, it checks all returned side effects.
 * If any side effect also implements the {@link Event} interface, it recursively processes
 * that side effect as an event and appends all resulting side effects to the original list.
 * <p>
 * This enables automatic event propagation where side effects can trigger further processing.
 */
public class EventPropagatingEngine implements Engine {

    private static final Logger logger = Logger.getLogger(EventPropagatingEngine.class.getName());

    private final Engine delegate;

    /**
     * Creates a new event propagating engine that wraps the given engine.
     *
     * @param delegate the engine to wrap and delegate calls to
     */
    public EventPropagatingEngine(Engine delegate) {
        this.delegate = delegate;
    }

    @Override
    public void register(EventProcessor processor) {
        delegate.register(processor);
    }

    @Override
    public List<SideEffect> processEvent(Event event) {
        List<SideEffect> sideEffects = delegate.processEvent(event);
        List<SideEffect> allSideEffects = new ArrayList<>(sideEffects);

        for (SideEffect sideEffect : sideEffects) {
            if (sideEffect instanceof Event eventSideEffect) {
                logger.info("Side effect implements Event, processing recursively: " + sideEffect);
                List<SideEffect> recursiveSideEffects = processEvent(eventSideEffect);
                allSideEffects.addAll(recursiveSideEffects);
            }
        }

        return allSideEffects;
    }

    @Override
    public boolean stepBack() {
        return delegate.stepBack();
    }

    @Override
    public int getHistorySize() {
        return delegate.getHistorySize();
    }

    @Override
    public void clearHistory() {
        delegate.clearHistory();
    }
}
