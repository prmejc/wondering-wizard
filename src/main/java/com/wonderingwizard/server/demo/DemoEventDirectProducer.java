package com.wonderingwizard.server.demo;

import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.server.DemoServer;

import java.util.logging.Logger;

/**
 * Sends work instruction events directly to the engine (no Kafka).
 * Used when Kafka is disabled.
 */
public class DemoEventDirectProducer implements DemoEventProducer {

    private static final Logger logger = Logger.getLogger(DemoEventDirectProducer.class.getName());

    private final DemoServer demoServer;

    public DemoEventDirectProducer(DemoServer demoServer) {
        this.demoServer = demoServer;
    }

    @Override
    public void sendWorkInstruction(WorkInstructionEvent event) {
        logger.fine("Direct: processing WI " + event.workInstructionId());
        demoServer.processStep("WorkInstruction: " + event.workInstructionId(), event);
    }
}
