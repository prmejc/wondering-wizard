package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.Takt;

import java.util.List;

/**
 * A post-processing step that runs on the final list of takts after actions
 * have been placed and dependencies wired.
 *
 * <p>Unlike {@link SchedulePipelineStep}, which operates on templates per-container
 * before takt fitting, post-processing steps have access to the complete dependency
 * graph and takt timing information.
 */
public interface SchedulePostProcessingStep {

    /**
     * Processes the final list of takts, potentially modifying actions in place
     * or returning a new list.
     *
     * @param takts the fully built takts with actions and dependencies wired
     * @return the processed takts
     */
    List<Takt> process(List<Takt> takts);
}
