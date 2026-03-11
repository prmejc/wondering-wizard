package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

import java.time.Instant;

/**
 * Event representing an asset (CHE) operational event received from Kafka.
 * <p>
 * AssetEvents are published by container handling equipment (QC, RTG, EH) to report
 * operational milestones such as lifting a container, placing it, or reaching a position.
 *
 * @param move the move type (e.g., LOAD, DSCH)
 * @param operationalEvent the specific operational event (e.g., "QCplacedContaineronVessel")
 * @param cheId the identifier of the CHE that produced this event (e.g., "QCZ1", "RTZ04")
 * @param terminalCode the terminal code (may be empty)
 * @param timestamp the time the event occurred
 */
public record AssetEvent(
        String move,
        String operationalEvent,
        String cheId,
        String terminalCode,
        Instant timestamp
) implements Event {

    @Override
    public String toString() {
        return "AssetEvent[move=" + move +
                ", operationalEvent=" + operationalEvent +
                ", cheId=" + cheId +
                ", terminalCode=" + terminalCode +
                ", timestamp=" + timestamp + "]";
    }
}
