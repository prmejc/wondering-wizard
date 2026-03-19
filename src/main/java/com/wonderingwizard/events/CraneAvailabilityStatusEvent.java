package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

/**
 * Event representing a crane availability status update.
 * Consumed from the {@code apmt.terminaloperations.craneavailabilitystatus} topic.
 *
 * @param terminalCode the terminal code
 * @param cheId the crane identifier (e.g., "QCZ9")
 * @param cheType the type of CHE (e.g., "QC")
 * @param cheStatus the availability status (READY or NOT_READY)
 * @param sourceTsMs the source timestamp in milliseconds
 */
public record CraneAvailabilityStatusEvent(
        String terminalCode,
        String cheId,
        String cheType,
        CraneAvailabilityStatus cheStatus,
        long sourceTsMs
) implements Event {}
