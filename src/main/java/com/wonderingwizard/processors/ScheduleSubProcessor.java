package com.wonderingwizard.processors;

import com.wonderingwizard.engine.Event;
import com.wonderingwizard.engine.SideEffect;

import java.util.List;

/**
 * Interface for sub-processors that handle specific events within the schedule runner context.
 * <p>
 * Sub-processors are registered with {@link ScheduleRunnerProcessor} and receive events along with
 * a {@link ScheduleContext} that provides controlled access to schedule state and operations.
 * This pattern keeps specialized event handling logic encapsulated in its own class while
 * still having access to schedule state.
 */
public interface ScheduleSubProcessor {

    /**
     * Process an event within the schedule context.
     *
     * @param event the event to process
     * @param context provides access to schedule state and operations
     * @return list of side effects produced, or empty list if this sub-processor doesn't handle the event
     */
    List<SideEffect> process(Event event, ScheduleContext context);
}
