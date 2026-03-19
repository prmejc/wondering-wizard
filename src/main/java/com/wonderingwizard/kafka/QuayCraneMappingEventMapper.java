package com.wonderingwizard.kafka;

import com.wonderingwizard.events.QuayCraneMappingEvent;
import org.apache.avro.generic.GenericRecord;

import java.util.logging.Logger;

/**
 * Maps Kafka Avro QuayCraneMapping messages to engine {@link QuayCraneMappingEvent} events.
 */
public class QuayCraneMappingEventMapper implements EventMapper<QuayCraneMappingEvent> {

    private static final Logger logger = Logger.getLogger(QuayCraneMappingEventMapper.class.getName());

    @Override
    public QuayCraneMappingEvent map(GenericRecord record) {
        String qcName = getStringField(record, "quayCraneShortName");
        String vesselName = getStringField(record, "vesselName");
        String craneMode = getStringField(record, "craneMode");
        String lane = getStringField(record, "lane");
        String terminalCode = getStringField(record, "terminalCode");
        long timestampMs = getLongField(record, "timeStamp", 0L);

        // Extract nested standby position
        String standbyPositionName = null;
        String standbyNodeName = null;
        String standbyTrafficDirection = null;
        Object standbyObj = record.get("standby");
        if (standbyObj instanceof GenericRecord standby) {
            standbyPositionName = getStringField(standby, "positionName");
            standbyNodeName = getStringField(standby, "nodeName");
            standbyTrafficDirection = getStringField(standby, "trafficDirection");
        }

        // Extract nested pinning positions
        String loadPinningPositionName = null;
        Object loadPinObj = record.get("loadPinning");
        if (loadPinObj instanceof GenericRecord loadPin) {
            loadPinningPositionName = getStringField(loadPin, "positionName");
        }

        String dischargePinningPositionName = null;
        Object dischPinObj = record.get("dischargePinning");
        if (dischPinObj instanceof GenericRecord dischPin) {
            dischargePinningPositionName = getStringField(dischPin, "positionName");
        }

        logger.fine("Mapped QuayCraneMapping: qc=" + qcName + ", vessel=" + vesselName
                + ", standby=" + standbyPositionName + ", loadPin=" + loadPinningPositionName);

        return new QuayCraneMappingEvent(
                qcName != null ? qcName : "",
                vesselName, craneMode, lane,
                standbyPositionName, standbyNodeName, standbyTrafficDirection,
                loadPinningPositionName, dischargePinningPositionName,
                terminalCode != null ? terminalCode : "",
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
}
