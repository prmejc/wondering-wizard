package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

/**
 * Event representing a CHE logical position update.
 *
 * @param cheShortName the short name of the CHE (e.g., "TT01")
 * @param currentMapNodeId the current map node ID
 * @param currentMapNodeName the current map node name (nullable)
 * @param latitude GPS latitude
 * @param longitude GPS longitude
 * @param hdop GPS horizontal dilution of precision
 * @param timestampMs the event timestamp in milliseconds
 */
public record CheLogicalPositionEvent(
        String cheShortName,
        long currentMapNodeId,
        String currentMapNodeName,
        double latitude,
        double longitude,
        double hdop,
        long timestampMs
) implements Event {}
