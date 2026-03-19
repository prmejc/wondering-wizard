package com.wonderingwizard.kafka;

import com.wonderingwizard.events.CraneDelayActivityEvent;
import org.apache.avro.generic.GenericRecord;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * Maps Kafka Avro CraneDelayActivities messages to engine
 * {@link CraneDelayActivityEvent} events.
 */
public class CraneDelayActivityEventMapper implements EventMapper<CraneDelayActivityEvent> {

    private static final Logger logger = Logger.getLogger(CraneDelayActivityEventMapper.class.getName());

    @Override
    public CraneDelayActivityEvent map(GenericRecord record) {
        String eventType = getStringField(record, "eventType");
        String opType = getStringField(record, "opType");
        String cdhTerminalCode = getStringField(record, "cdhTerminalCode");
        Long messageSequenceNumber = getLongFieldNullable(record, "messageSequenceNumber");
        Long vesselVisitCraneDelayId = getLongFieldNullable(record, "vesselVisitCraneDelayId");
        String vesselVisitId = getStringField(record, "vesselVisitId");
        Instant delayStartTime = getInstantField(record, "delayStartTime");
        Instant delayStopTime = getInstantField(record, "delayStopTime");
        String cheShortName = getStringField(record, "cheShortName");
        String delayRemarks = getStringField(record, "delayRemarks");
        String delayType = getStringField(record, "delayType");
        String delayTypeDescription = getStringField(record, "delayTypeDescription");
        String positionEnum = getStringField(record, "positionEnum");
        String delayStatus = getStringField(record, "delayStatus");
        String delayTypeAction = getStringField(record, "delayTypeAction");
        String delayTypeCategory = getStringField(record, "delayTypeCategory");
        Long sourceTsMs = getLongFieldNullable(record, "SOURCE_TS_MS");

        logger.fine("Mapped CraneDelayActivity: che=" + cheShortName
                + ", type=" + delayType + ", status=" + delayStatus);

        return new CraneDelayActivityEvent(
                eventType, opType, cdhTerminalCode, messageSequenceNumber,
                vesselVisitCraneDelayId, vesselVisitId, delayStartTime, delayStopTime,
                cheShortName, delayRemarks, delayType, delayTypeDescription,
                positionEnum, delayStatus, delayTypeAction, delayTypeCategory, sourceTsMs);
    }

    private static String getStringField(GenericRecord record, String fieldName) {
        Object value = record.get(fieldName);
        return value != null ? value.toString() : null;
    }

    private static Long getLongFieldNullable(GenericRecord record, String fieldName) {
        Object value = record.get(fieldName);
        if (value instanceof Long l) return l;
        if (value instanceof Number n) return n.longValue();
        return null;
    }

    private static Instant getInstantField(GenericRecord record, String fieldName) {
        Object value = record.get(fieldName);
        if (value instanceof Long l) return Instant.ofEpochMilli(l);
        if (value instanceof Number n) return Instant.ofEpochMilli(n.longValue());
        return null;
    }
}
