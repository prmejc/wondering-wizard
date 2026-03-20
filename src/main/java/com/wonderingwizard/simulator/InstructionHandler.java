package com.wonderingwizard.simulator;

import org.apache.avro.generic.GenericRecord;

/**
 * Handles an equipment instruction and produces simulated responses.
 */
public interface InstructionHandler {

    void handle(GenericRecord instruction, KafkaInfra kafka);
}
