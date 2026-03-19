package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

import java.time.Instant;

/**
 * Event representing a QC resume (crane readiness) notification from PMB.
 * Consumed from the {@code apmt.terminal-operations.cranereadiness} topic.
 *
 * @param qcShortName the quay crane short name (e.g., "QCZ9")
 * @param workQueueId the work queue this readiness relates to
 * @param qcResumeTimestamp when the QC resume was initiated
 * @param updatedBy user or system id that initiated the resume (nullable)
 * @param eventId the unique event identifier
 */
public record CraneReadinessEvent(
        String qcShortName,
        long workQueueId,
        Instant qcResumeTimestamp,
        String updatedBy,
        String eventId
) implements Event {}
