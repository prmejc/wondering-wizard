package com.wonderingwizard.server.demo;

import com.wonderingwizard.events.WorkInstructionEvent;

/**
 * Abstraction for how the DemoServer sends work instruction events.
 * <p>
 * When Kafka is disabled, events go directly to the engine.
 * When Kafka is enabled, events are published to Kafka and flow back through the consumer.
 */
public interface DemoEventProducer {

    void sendWorkInstruction(WorkInstructionEvent event);
}
