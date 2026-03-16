package com.wonderingwizard.processors;

import com.wonderingwizard.events.WorkInstructionEvent;

import java.util.List;

/**
 * A step in the schedule creation pipeline that can modify action templates
 * before they are fitted into takts.
 *
 * <p>Pipeline steps are registered with {@link WorkQueueProcessor} and executed
 * in registration order between template creation and takt fitting.
 * Each step receives the blueprint templates for a single container and may
 * modify durations or other properties based on external data (e.g., digital map paths).
 *
 * <p>If no pipeline steps are registered, templates pass directly to takt fitting
 * with their default durations.
 */
public interface SchedulePipelineStep {

    /**
     * Enriches action templates for a single container before takt fitting.
     *
     * @param workQueueId the work queue this schedule belongs to
     * @param templates the action templates for one container (may be modified)
     * @param workInstruction the work instruction for this container
     * @return the enriched templates (may be the same list with modified durations)
     */
    List<GraphScheduleBuilder.ActionTemplate> enrichTemplates(
            long workQueueId,
            List<GraphScheduleBuilder.ActionTemplate> templates,
            WorkInstructionEvent workInstruction
    );
}
