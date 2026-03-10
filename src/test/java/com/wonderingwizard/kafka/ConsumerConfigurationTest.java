package com.wonderingwizard.kafka;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConsumerConfigurationTest {

    @Test
    void shouldIdentifyAvroConsumer() {
        var config = new ConsumerConfiguration(
                "topic", "group", "com.test.AvroMessage", null, false
        );
        assertTrue(config.isAvro());
        assertFalse(config.isJson());
    }

    @Test
    void shouldIdentifyJsonConsumer() {
        var config = new ConsumerConfiguration(
                "topic", "group", null, "com.test.JsonMessage", false
        );
        assertFalse(config.isAvro());
        assertTrue(config.isJson());
    }

    @Test
    void shouldHandleBlankAvroType() {
        var config = new ConsumerConfiguration(
                "topic", "group", "  ", null, false
        );
        assertFalse(config.isAvro());
    }
}
