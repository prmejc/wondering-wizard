package com.wonderingwizard.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * A decorator engine that wraps another engine and propagates side effects that are also events.
 * <p>
 * This engine delegates all method calls to the wrapped engine, with special behavior for
 * {@link #processEvent(Event)}: after processing an event, it checks all returned side effects.
 * If any side effect also implements the {@link Event} interface, it recursively processes
 * that side effect as an event and appends all resulting side effects to the original list.
 * <p>
 * This enables automatic event propagation where side effects can trigger further processing.
 */
public class DecoratorEngine implements Engine {

    private static final Logger logger = Logger.getLogger(DecoratorEngine.class.getName());

    private final Engine delegate;

    /**
     * Creates a new decorator engine that wraps the given engine.
     *
     * @param delegate the engine to wrap and delegate calls to
     */
    public DecoratorEngine(Engine delegate) {
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
