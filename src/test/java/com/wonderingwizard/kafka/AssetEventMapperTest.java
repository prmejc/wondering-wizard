package com.wonderingwizard.kafka;

import com.wonderingwizard.events.AssetEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AssetEventMapper Tests")
class AssetEventMapperTest {

    private AssetEventMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new AssetEventMapper();
    }

    @Test
    @DisplayName("Should map QC placed container on vessel event")
    void mapsQcPlacedContainerOnVessel() {
        String json = """
                {
                    "move": "LOAD",
                    "operationalEvent": "QCplacedContaineronVessel",
                    "cheID": "QCZ1",
                    "terminalCode": "",
                    "timestamp": 1773103048
                }
                """;

        AssetEvent event = mapper.map(json);

        assertEquals("LOAD", event.move());
        assertEquals("QCplacedContaineronVessel", event.operationalEvent());
        assertEquals("QCZ1", event.cheId());
        assertEquals("", event.terminalCode());
        assertEquals(Instant.ofEpochSecond(1773103048), event.timestamp());
    }

    @Test
    @DisplayName("Should map QC lifted container from truck event")
    void mapsQcLiftedContainerFromTruck() {
        String json = """
                {
                    "move": "LOAD",
                    "operationalEvent": "QCliftedContainerfromTruck",
                    "cheID": "QCZ1",
                    "terminalCode": "",
                    "timestamp": 1773103046
                }
                """;

        AssetEvent event = mapper.map(json);

        assertEquals("QCliftedContainerfromTruck", event.operationalEvent());
        assertEquals("QCZ1", event.cheId());
    }

    @Test
    @DisplayName("Should map RTG placed container on truck event")
    void mapsRtgPlacedContainerOnTruck() {
        String json = """
                {
                    "move": "LOAD",
                    "operationalEvent": "RTGplacedContainerOnTruck",
                    "cheID": "RTZ04",
                    "terminalCode": "",
                    "timestamp": 1773103038
                }
                """;

        AssetEvent event = mapper.map(json);

        assertEquals("RTGplacedContainerOnTruck", event.operationalEvent());
        assertEquals("RTZ04", event.cheId());
        assertEquals(Instant.ofEpochSecond(1773103038), event.timestamp());
    }

    @Test
    @DisplayName("Should map RTG reached a row event")
    void mapsRtgReachedARow() {
        String json = """
                {
                    "move": "LOAD",
                    "operationalEvent": "RTGreachedArow",
                    "cheID": "RTZ04",
                    "terminalCode": "",
                    "timestamp": 1773103034
                }
                """;

        AssetEvent event = mapper.map(json);

        assertEquals("RTGreachedArow", event.operationalEvent());
    }

    @Test
    @DisplayName("Should map RTG lifted container from yard event")
    void mapsRtgLiftedContainerFromYard() {
        String json = """
                {
                    "move": "LOAD",
                    "operationalEvent": "RTGliftedContainerfromYard",
                    "cheID": "RTZ04",
                    "terminalCode": "",
                    "timestamp": 1773103034
                }
                """;

        AssetEvent event = mapper.map(json);

        assertEquals("RTGliftedContainerfromYard", event.operationalEvent());
        assertEquals("RTZ04", event.cheId());
    }

    @Test
    @DisplayName("Should handle terminal code with value")
    void handlesTerminalCodeWithValue() {
        String json = """
                {
                    "move": "DSCH",
                    "operationalEvent": "QCliftedContainerfromVessel",
                    "cheID": "QCZ2",
                    "terminalCode": "ECTDELTA",
                    "timestamp": 1773103100
                }
                """;

        AssetEvent event = mapper.map(json);

        assertEquals("DSCH", event.move());
        assertEquals("ECTDELTA", event.terminalCode());
    }

    @Test
    @DisplayName("Should handle null timestamp")
    void handlesNullTimestamp() {
        String json = """
                {
                    "move": "LOAD",
                    "operationalEvent": "RTGreachedArow",
                    "cheID": "RTZ04",
                    "terminalCode": "",
                    "timestamp": null
                }
                """;

        AssetEvent event = mapper.map(json);

        assertNull(event.timestamp());
        assertEquals("RTGreachedArow", event.operationalEvent());
    }

    @Test
    @DisplayName("Should handle compact JSON without whitespace")
    void handlesCompactJson() {
        String json = "{\"move\":\"LOAD\",\"operationalEvent\":\"QCplacedContaineronVessel\",\"cheID\":\"QCZ1\",\"terminalCode\":\"\",\"timestamp\":1773103048}";

        AssetEvent event = mapper.map(json);

        assertEquals("LOAD", event.move());
        assertEquals("QCplacedContaineronVessel", event.operationalEvent());
        assertEquals("QCZ1", event.cheId());
        assertEquals(Instant.ofEpochSecond(1773103048), event.timestamp());
    }
}
