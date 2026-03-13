package com.wonderingwizard.kafka;

import com.wonderingwizard.domain.takt.ActionType;
import com.wonderingwizard.domain.takt.DeviceType;
import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.sideeffects.ActionActivated;
import com.wonderingwizard.sideeffects.ActionCompleted;
import com.wonderingwizard.events.WorkInstructionEvent;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static com.wonderingwizard.events.WorkInstructionStatus.PENDING;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("KafkaSideEffectPublisher Tests")
class KafkaSideEffectPublisherTest {

    private static final KafkaConfiguration KAFKA_CONFIG = new KafkaConfiguration(
            "localhost:9092", "test-client", "http://localhost:8081",
            null, null, null, null
    );

    @Test
    @DisplayName("Should build producer properties correctly")
    void buildsProducerProperties() {
        var publisher = new KafkaSideEffectPublisher(KAFKA_CONFIG);
        Properties props = publisher.buildProducerProperties();

        assertEquals("localhost:9092", props.get("bootstrap.servers"));
        assertEquals("test-client-producer", props.get("client.id"));
        assertEquals("http://localhost:8081", props.get("schema.registry.url"));
    }

    @Test
    @DisplayName("Should build producer properties with SASL configuration")
    void buildsProducerPropertiesWithSasl() {
        var saslConfig = new KafkaConfiguration(
                "broker:9094", "client", "http://registry:8081",
                "Plain", "user", "pass", "SaslPlaintext"
        );
        var publisher = new KafkaSideEffectPublisher(saslConfig);
        Properties props = publisher.buildProducerProperties();

        assertEquals("SaslPlaintext", props.get("security.protocol"));
        assertEquals("Plain", props.get("sasl.mechanism"));
        assertTrue(props.get("sasl.jaas.config").toString().contains("username=\"user\""));
        assertTrue(props.get("sasl.jaas.config").toString().contains("password=\"pass\""));
    }

    @Test
    @DisplayName("Should register multiple mappers for different side effect types")
    void registersMultipleMappers() {
        var publisher = new KafkaSideEffectPublisher(KAFKA_CONFIG);

        // Should not throw when registering mappers
        publisher.registerMapper(ActionActivated.class, "equipment-instructions", activated -> null);
        publisher.registerMapper(ActionCompleted.class, "action-completed-topic", completed -> null);

        // Verify properties are correct (proxy for correct initialization)
        Properties props = publisher.buildProducerProperties();
        assertNotNull(props.get("bootstrap.servers"));
    }

    @Test
    @DisplayName("ActionActivated enriched record should contain deviceType and workInstructions")
    void actionActivatedContainsEnrichedFields() {
        WorkInstructionEvent wi = new WorkInstructionEvent(
                100L, 1L, "QC01", PENDING, Instant.now(), 120, 60, "RTG05",
                false, false, false, 0, "Y01.01.01"
        );

        ActionActivated activated = new ActionActivated(
                UUID.randomUUID(), 1L, "TAKT100", ActionType.TT_DRIVE_TO_RTG_PULL, "drive to RTG pull",
                Instant.now(), DeviceType.TT, List.of(wi)
        );

        assertEquals(DeviceType.TT, activated.deviceType());
        assertEquals(1, activated.workInstructions().size());
        assertEquals(100L, activated.workInstructions().getFirst().workInstructionId());
    }

    @Test
    @DisplayName("ActionActivated backward-compatible constructor should default to null deviceType and empty workInstructions")
    void actionActivatedBackwardCompatible() {
        ActionActivated activated = new ActionActivated(
                UUID.randomUUID(), 1L, "TAKT100", "drive to RTG pull", Instant.now()
        );

        assertNull(activated.deviceType());
        assertTrue(activated.workInstructions().isEmpty());
    }
}
