package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

import java.time.Instant;

/**
 * Event representing a crane delay activity.
 * Consumed from the {@code APMT.terminalOperations.craneDelayActivities} topic.
 *
 * @param eventType the event type (e.g. "Crane Delay Occured")
 * @param opType the operation type (e.g. "I" for insert)
 * @param cdhTerminalCode the terminal code
 * @param messageSequenceNumber the message sequence number (nullable)
 * @param vesselVisitCraneDelayId the vessel visit crane delay ID (nullable)
 * @param vesselVisitId the vessel visit ID (nullable)
 * @param delayStartTime the delay start time (nullable)
 * @param delayStopTime the delay stop time (nullable)
 * @param cheShortName the crane short name (e.g. "QCZ8")
 * @param delayRemarks remarks about the delay (nullable)
 * @param delayType the delay type code (e.g. "1.1")
 * @param delayTypeDescription the delay type description (nullable)
 * @param positionEnum the position enum (e.g. "FIXED_START")
 * @param delayStatus the delay status (e.g. "CLERK_STARTED")
 * @param delayTypeAction the delay type action (e.g. "CONTAINER_MOVE_STOPPED")
 * @param delayTypeCategory the delay type category (e.g. "ABNORMAL")
 * @param sourceTsMs the source timestamp in milliseconds (nullable)
 */
public record CraneDelayActivityEvent(
        String eventType,
        String opType,
        String cdhTerminalCode,
        Long messageSequenceNumber,
        Long vesselVisitCraneDelayId,
        String vesselVisitId,
        Instant delayStartTime,
        Instant delayStopTime,
        String cheShortName,
        String delayRemarks,
        String delayType,
        String delayTypeDescription,
        String positionEnum,
        String delayStatus,
        String delayTypeAction,
        String delayTypeCategory,
        Long sourceTsMs
) implements Event {}
