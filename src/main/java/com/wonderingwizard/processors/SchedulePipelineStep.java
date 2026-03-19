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
 */
public interface SchedulePipelineStep {

    /**
     * Context passed to pipeline steps during template enrichment.
     *
     * @param workQueueId the work queue this schedule belongs to
     * @param bollardPosition the QC bollard position from the work queue (nullable)
     * @param containerIndex zero-based index of this container in the schedule
     * @param previousToPosition the toPosition of the previous container (nullable, for consecutive singles)
     */
    record EnrichmentContext(
            long workQueueId,
            String bollardPosition,
            int containerIndex,
            String previousToPosition
    ) {}

    /**
     * Enriches action templates for a single container before takt fitting.
     *
     * @param context the enrichment context with work queue, bollard position, etc.
     * @param templates the action templates for one container (may be modified)
     * @param workInstruction the work instruction for this container
     * @return the enriched templates (may be the same list with modified durations)
     */
    List<GraphScheduleBuilder.ActionTemplate> enrichTemplates(
            EnrichmentContext context,
            List<GraphScheduleBuilder.ActionTemplate> templates,
            WorkInstructionEvent workInstruction
    );

    /**
     * Backward-compatible default: delegates to the new method with a minimal context.
     */
    default List<GraphScheduleBuilder.ActionTemplate> enrichTemplates(
            long workQueueId,
            List<GraphScheduleBuilder.ActionTemplate> templates,
            WorkInstructionEvent workInstruction
    ) {
        return enrichTemplates(new EnrichmentContext(workQueueId, null, 0, null), templates, workInstruction);
    }
}
