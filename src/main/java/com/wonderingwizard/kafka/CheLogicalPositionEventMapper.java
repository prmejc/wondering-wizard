package com.wonderingwizard.kafka;

import com.wonderingwizard.events.CheLogicalPositionEvent;
import org.apache.avro.generic.GenericRecord;

import java.util.logging.Logger;

/**
 * Maps Kafka Avro CheLogicalPosition messages to engine
 * {@link CheLogicalPositionEvent} events.
 */
public class CheLogicalPositionEventMapper implements EventMapper<CheLogicalPositionEvent> {

    private static final Logger logger = Logger.getLogger(CheLogicalPositionEventMapper.class.getName());

    @Override
    public CheLogicalPositionEvent map(GenericRecord record) {
        String cheShortName = getStringField(record, "cheShortName");
        long currentMapNodeId = getLongField(record, "currentMapNodeID", 0L);
        String currentMapNodeName = getStringField(record, "currentMapNodeName");
        long timestampMs = getLongField(record, "timeStamp", 0L);

        double latitude = 0.0;
        double longitude = 0.0;
        double hdop = 0.0;
        Object coordsObj = record.get("coordinates");
        if (coordsObj instanceof GenericRecord coords) {
            latitude = getDoubleField(coords, "latitude", 0.0);
            longitude = getDoubleField(coords, "longitude", 0.0);
            hdop = getDoubleField(coords, "hdop", 0.0);
        }

        logger.fine("Mapped CheLogicalPosition: cheShortName=" + cheShortName
                + ", nodeId=" + currentMapNodeId + ", nodeName=" + currentMapNodeName
                + ", lat=" + latitude + ", lon=" + longitude);

        return new CheLogicalPositionEvent(
                cheShortName != null ? cheShortName : "",
                currentMapNodeId,
                currentMapNodeName,
                latitude, longitude, hdop,
                timestampMs
        );
    }

    private static String getStringField(GenericRecord record, String fieldName) {
        Object value = record.get(fieldName);
        return value != null ? value.toString() : null;
    }

    private static long getLongField(GenericRecord record, String fieldName, long defaultValue) {
        Object value = record.get(fieldName);
        if (value instanceof Long l) return l;
        if (value instanceof Number n) return n.longValue();
        return defaultValue;
    }

    private static double getDoubleField(GenericRecord record, String fieldName, double defaultValue) {
        Object value = record.get(fieldName);
        if (value instanceof Double d) return d;
        if (value instanceof Number n) return n.doubleValue();
        return defaultValue;
    }
}
