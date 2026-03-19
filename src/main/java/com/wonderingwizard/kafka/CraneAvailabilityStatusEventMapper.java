package com.wonderingwizard.kafka;

import com.wonderingwizard.events.CraneAvailabilityStatus;
import com.wonderingwizard.events.CraneAvailabilityStatusEvent;
import org.apache.avro.generic.GenericRecord;

import java.util.logging.Logger;

/**
 * Maps Kafka Avro CraneAvailabilityStatus messages to engine
 * {@link CraneAvailabilityStatusEvent} events.
 */
public class CraneAvailabilityStatusEventMapper implements EventMapper<CraneAvailabilityStatusEvent> {

    private static final Logger logger = Logger.getLogger(CraneAvailabilityStatusEventMapper.class.getName());

    @Override
    public CraneAvailabilityStatusEvent map(GenericRecord record) {
        String terminalCode = getStringField(record, "terminalCode");
        String cheId = getStringField(record, "cheId");
        String cheType = getStringField(record, "cheType");
        String cheStatusStr = getStringField(record, "cheStatus");
        CraneAvailabilityStatus cheStatus = CraneAvailabilityStatus.fromCode(cheStatusStr);
        long sourceTsMs = getLongField(record, "SOURCE_TS_MS", 0L);

        logger.fine("Mapped CraneAvailabilityStatus: cheId=" + cheId
                + ", status=" + cheStatus + ", type=" + cheType);

        return new CraneAvailabilityStatusEvent(
                terminalCode != null ? terminalCode : "",
                cheId != null ? cheId : "",
                cheType != null ? cheType : "",
                cheStatus,
                sourceTsMs
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
}
