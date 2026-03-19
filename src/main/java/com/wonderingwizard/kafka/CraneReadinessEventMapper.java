package com.wonderingwizard.kafka;

import com.wonderingwizard.events.CraneReadinessEvent;
import org.apache.avro.generic.GenericRecord;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * Maps Kafka Avro CraneReadiness messages to engine {@link CraneReadinessEvent} events.
 */
public class CraneReadinessEventMapper implements EventMapper<CraneReadinessEvent> {

    private static final Logger logger = Logger.getLogger(CraneReadinessEventMapper.class.getName());

    @Override
    public CraneReadinessEvent map(GenericRecord record) {
        String eventId = getStringField(record, "eventId");
        String qcShortName = getStringField(record, "qcShortName");
        long workQueueId = getLongField(record, "workQueueId", 0L);
        String updatedBy = getStringField(record, "updatedBy");
        long qcResumeTimestampMs = getLongField(record, "qcResumeTimestamp", 0L);
        Instant qcResumeTimestamp = Instant.ofEpochMilli(qcResumeTimestampMs);

        logger.fine("Mapped CraneReadiness: qc=" + qcShortName
                + ", wqId=" + workQueueId + ", resume=" + qcResumeTimestamp);

        return new CraneReadinessEvent(
                qcShortName != null ? qcShortName : "",
                workQueueId,
                qcResumeTimestamp,
                updatedBy,
                eventId != null ? eventId : ""
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
