package com.wonderingwizard.kafka;

import com.wonderingwizard.events.DigitalMapEvent;
import org.apache.avro.generic.GenericRecord;

import java.util.logging.Logger;

/**
 * Maps Kafka Avro TerminalLayout messages to engine {@link DigitalMapEvent} events.
 * <p>
 * The Avro record contains a {@code terminalLayout} field with base64-encoded,
 * gzip-compressed OSM XML. This mapper reconstructs the JSON envelope expected
 * by {@link com.wonderingwizard.processors.DigitalMapProcessor#parseMap(String)}.
 */
public class TerminalLayoutEventMapper implements EventMapper<DigitalMapEvent> {

    private static final Logger logger = Logger.getLogger(TerminalLayoutEventMapper.class.getName());

    @Override
    public DigitalMapEvent map(GenericRecord record) {
        String terminalCode = getStringField(record, "terminalCode");
        String terminalLayoutVersion = getStringField(record, "terminalLayoutVersion");
        String terminalLayout = getStringField(record, "terminalLayout");
        String eventSource = getStringField(record, "eventSource");

        if (terminalLayout == null || terminalLayout.isBlank()) {
            logger.warning("TerminalLayout Kafka message has empty terminalLayout field");
            return new DigitalMapEvent("");
        }

        // Reconstruct JSON envelope matching the format expected by DigitalMapProcessor
        String json = "{\"terminalCode\":\"" + escapeJson(terminalCode != null ? terminalCode : "")
                + "\",\"terminalLayoutVersion\":\"" + escapeJson(terminalLayoutVersion != null ? terminalLayoutVersion : "")
                + "\",\"terminalLayout\":\"" + escapeJson(terminalLayout)
                + "\",\"eventSource\":\"" + escapeJson(eventSource != null ? eventSource : "") + "\"}";

        logger.info("Mapped TerminalLayout from Kafka: terminalCode=" + terminalCode
                + ", version=" + terminalLayoutVersion
                + ", layoutLength=" + terminalLayout.length());

        return new DigitalMapEvent(json);
    }

    private static String getStringField(GenericRecord record, String fieldName) {
        Object value = record.get(fieldName);
        return value != null ? value.toString() : null;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
