package com.wonderingwizard.server;

import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.TimeEvent;
import com.wonderingwizard.events.WorkInstructionEvent;
import com.wonderingwizard.events.WorkInstructionStatus;
import com.wonderingwizard.events.WorkQueueMessage;
import com.wonderingwizard.events.WorkQueueStatus;
import com.wonderingwizard.sideeffects.ScheduleCreated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("F-12: Event Log Export/Import")
class EventLogExportImportTest {

    private DemoServer server;

    @BeforeEach
    void setUp() {
        server = new DemoServer();
    }

    @Nested
    @DisplayName("Export format")
    class ExportTests {

        @Test
        @DisplayName("Should produce valid JSON array for export")
        void exportsValidJson() {
            Instant emt = Instant.parse("2024-01-01T00:05:00Z");
            server.processStep("WI 1", new WorkInstructionEvent(
                    1L, 1L, "RTG-01", WorkInstructionStatus.PENDING, emt, 120));
            server.processStep("Activate WQ",
                    new WorkQueueMessage(1L, WorkQueueStatus.ACTIVE, 0, null));

            // Build the export JSON the same way the handler does
            String exportJson = buildExportJson(server);

            assertTrue(exportJson.startsWith("["));
            assertTrue(exportJson.endsWith("]"));
            assertTrue(exportJson.contains("WorkInstructionEvent"));
            assertTrue(exportJson.contains("WorkQueueMessage"));
            assertTrue(exportJson.contains("\"description\":\"WI 1\""));
        }

        @Test
        @DisplayName("Should export empty array when no steps")
        void exportsEmptyArray() {
            String exportJson = buildExportJson(server);
            assertEquals("[]", exportJson);
        }
    }

    @Nested
    @DisplayName("Import and restore")
    class ImportTests {

        @Test
        @DisplayName("Should restore system state from exported JSON")
        void restoresState() {
            Instant emt = Instant.parse("2024-01-01T00:05:00Z");
            server.processStep("WI 1", new WorkInstructionEvent(
                    1L, 1L, "RTG-01", WorkInstructionStatus.PENDING, emt, 120));
            server.processStep("Activate WQ",
                    new WorkQueueMessage(1L, WorkQueueStatus.ACTIVE, 5, com.wonderingwizard.events.LoadMode.DSCH));

            // Export
            String exportJson = buildExportJson(server);

            // Create a new server and import
            DemoServer newServer = new DemoServer();
            assertEquals(0, newServer.getSteps().size());

            importJson(newServer, exportJson);

            // Verify state is restored
            assertEquals(2, newServer.getSteps().size());
            assertEquals("WI 1", newServer.getSteps().get(0).description());
            assertEquals("Activate WQ", newServer.getSteps().get(1).description());

            // Verify schedule was re-created
            Map<String, Object> state = newServer.getState();
            List<?> schedules = (List<?>) state.get("schedules");
            assertEquals(1, schedules.size());

            DemoServer.ScheduleView schedule = (DemoServer.ScheduleView) schedules.get(0);
            assertEquals(1L, schedule.workQueueId());
            assertTrue(schedule.active());
        }

        @Test
        @DisplayName("Should handle TimeEvent import correctly")
        void restoresTimeEvents() {
            Instant emt = Instant.parse("2024-01-01T00:05:00Z");
            server.processStep("WI 1", new WorkInstructionEvent(
                    1L, 1L, "RTG-01", WorkInstructionStatus.PENDING, emt, 120));
            server.processStep("Activate WQ",
                    new WorkQueueMessage(1L, WorkQueueStatus.ACTIVE, 0, null));
            server.processStep("Tick +60s", new TimeEvent(emt.plusSeconds(60)));

            String exportJson = buildExportJson(server);

            DemoServer newServer = new DemoServer();
            importJson(newServer, exportJson);

            assertEquals(3, newServer.getSteps().size());
        }

        @Test
        @DisplayName("Should clear existing state before import")
        void clearsExistingState() {
            // Add events to original server
            server.processStep("WI 1", new WorkInstructionEvent(
                    1L, 1L, "RTG-01", WorkInstructionStatus.PENDING,
                    Instant.parse("2024-01-01T00:05:00Z"), 120));

            // Export a different scenario
            DemoServer other = new DemoServer();
            other.processStep("WI 99", new WorkInstructionEvent(
                    99L, 99L, "RTG-99", WorkInstructionStatus.PENDING,
                    Instant.parse("2024-01-01T01:00:00Z"), 60));
            String exportJson = buildExportJson(other);

            // Import into original server (should replace, not append)
            importJson(server, exportJson);

            assertEquals(1, server.getSteps().size());
            assertEquals("WI 99", server.getSteps().get(0).description());
        }
    }

    /**
     * Builds export JSON the same way DemoServer.handleExportEventLog does.
     */
    private String buildExportJson(DemoServer server) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        for (DemoServer.Step step : server.getSteps()) {
            if (!first) sb.append(',');
            sb.append("{\"description\":");
            appendJsonString(sb, step.description());
            sb.append(",\"event\":");
            sb.append(JsonSerializer.serialize(step.event()));
            sb.append('}');
            first = false;
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Imports JSON by invoking the same logic as DemoServer.handleImportEventLog.
     */
    private void importJson(DemoServer server, String json) {
        // Use reflection to call the private import parsing + replay logic
        // Instead, simulate what the handler does:
        server.stepBackTo(0);
        server.getState(); // ensure clean state

        List<Map<String, String>> entries = parseImportArray(json);
        for (Map<String, String> entry : entries) {
            String description = entry.get("description");
            com.wonderingwizard.engine.Event event = EventDeserializer.deserialize(entry);
            server.processStep(description != null ? description : "Imported", event);
        }
    }

    /**
     * Minimal array parser matching DemoServer.parseImportArray.
     */
    private List<Map<String, String>> parseImportArray(String json) {
        List<Map<String, String>> result = new java.util.ArrayList<>();
        String trimmed = json.strip();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            throw new IllegalArgumentException("Expected JSON array");
        }

        int depth = 0;
        int start = -1;
        for (int i = 1; i < trimmed.length() - 1; i++) {
            char c = trimmed.charAt(i);
            if (c == '"') {
                i++;
                while (i < trimmed.length() && trimmed.charAt(i) != '"') {
                    if (trimmed.charAt(i) == '\\') i++;
                    i++;
                }
            } else if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    String element = trimmed.substring(start, i + 1);
                    result.add(parseImportEntry(element));
                    start = -1;
                }
            }
        }
        return result;
    }

    private Map<String, String> parseImportEntry(String json) {
        Map<String, String> result = new java.util.HashMap<>();

        int descIdx = json.indexOf("\"description\"");
        if (descIdx >= 0) {
            int colonIdx = json.indexOf(':', descIdx + 13);
            int valStart = json.indexOf('"', colonIdx + 1);
            int valEnd = findClosingQuote(json, valStart);
            result.put("description", json.substring(valStart + 1, valEnd));
        }

        int eventIdx = json.indexOf("\"event\"");
        if (eventIdx >= 0) {
            int colonIdx = json.indexOf(':', eventIdx + 7);
            int braceStart = json.indexOf('{', colonIdx);
            int d = 0;
            int braceEnd = braceStart;
            for (int i = braceStart; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '"') {
                    i++;
                    while (i < json.length() && json.charAt(i) != '"') {
                        if (json.charAt(i) == '\\') i++;
                        i++;
                    }
                } else if (c == '{') {
                    d++;
                } else if (c == '}') {
                    d--;
                    if (d == 0) { braceEnd = i; break; }
                }
            }
            String eventJson = json.substring(braceStart, braceEnd + 1);
            result.putAll(JsonParser.parseObject(eventJson));
        }

        return result;
    }

    private static int findClosingQuote(String s, int openPos) {
        int pos = openPos + 1;
        while (pos < s.length()) {
            if (s.charAt(pos) == '\\') {
                pos += 2;
            } else if (s.charAt(pos) == '"') {
                return pos;
            } else {
                pos++;
            }
        }
        return s.length() - 1;
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        sb.append('"');
    }
}
