package com.wonderingwizard.kafka;

import com.wonderingwizard.events.AssetEvent;
import com.wonderingwizard.server.JsonParser;

import java.time.Instant;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Maps JSON AssetEvent messages from Kafka to {@link AssetEvent} engine events.
 * <p>
 * AssetEvent messages are published by CHEs (QC, RTG, EH) to report operational
 * milestones. The JSON format is flat with string and numeric fields:
 * <pre>
 * {
 *     "move": "LOAD",
 *     "operationalEvent": "QCplacedContaineronVessel",
 *     "cheID": "QCZ1",
 *     "terminalCode": "",
 *     "timestamp": 1773103048
 * }
 * </pre>
 */
public class AssetEventMapper implements JsonEventMapper<AssetEvent> {

    private static final Logger logger = Logger.getLogger(AssetEventMapper.class.getName());

    @Override
    public AssetEvent map(String json) {
        Map<String, String> fields = JsonParser.parseObject(json);

        String move = fields.get("move");
        String operationalEvent = fields.get("operationalEvent");
        String cheId = fields.get("cheID");
        String terminalCode = fields.get("terminalCode");
        Instant timestamp = parseTimestamp(fields.get("timestamp"));

        logger.fine("Mapped AssetEvent: cheId=" + cheId + ", event=" + operationalEvent);

        return new AssetEvent(move, operationalEvent, cheId, terminalCode, timestamp);
    }

    private Instant parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        long epochSeconds = Long.parseLong(value);
        return Instant.ofEpochSecond(epochSeconds);
    }
}
