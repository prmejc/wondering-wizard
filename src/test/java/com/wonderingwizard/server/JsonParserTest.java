package com.wonderingwizard.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JsonParser Tests")
class JsonParserTest {

    @Nested
    @DisplayName("Parsing flat objects")
    class FlatObjectTests {

        @Test
        @DisplayName("Should parse empty object")
        void parsesEmptyObject() {
            Map<String, String> result = JsonParser.parseObject("{}");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should parse object with string values")
        void parsesStringValues() {
            Map<String, String> result = JsonParser.parseObject(
                    "{\"name\":\"test\",\"value\":\"hello\"}");
            assertEquals("test", result.get("name"));
            assertEquals("hello", result.get("value"));
        }

        @Test
        @DisplayName("Should parse object with number values")
        void parsesNumberValues() {
            Map<String, String> result = JsonParser.parseObject(
                    "{\"seconds\":300,\"count\":42}");
            assertEquals("300", result.get("seconds"));
            assertEquals("42", result.get("count"));
        }

        @Test
        @DisplayName("Should parse object with boolean values")
        void parsesBooleanValues() {
            Map<String, String> result = JsonParser.parseObject(
                    "{\"active\":true,\"deleted\":false}");
            assertEquals("true", result.get("active"));
            assertEquals("false", result.get("deleted"));
        }

        @Test
        @DisplayName("Should parse object with null values")
        void parsesNullValues() {
            Map<String, String> result = JsonParser.parseObject(
                    "{\"data\":null}");
            assertNull(result.get("data"));
        }

        @Test
        @DisplayName("Should handle whitespace")
        void handlesWhitespace() {
            Map<String, String> result = JsonParser.parseObject(
                    "  { \"key\" : \"value\" , \"num\" : 42 }  ");
            assertEquals("value", result.get("key"));
            assertEquals("42", result.get("num"));
        }

        @Test
        @DisplayName("Should handle escaped characters in strings")
        void handlesEscapedCharacters() {
            Map<String, String> result = JsonParser.parseObject(
                    "{\"msg\":\"hello\\\"world\"}");
            assertEquals("hello\"world", result.get("msg"));
        }

        @Test
        @DisplayName("Should return empty map for null input")
        void handlesNullInput() {
            Map<String, String> result = JsonParser.parseObject(null);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return empty map for blank input")
        void handlesBlankInput() {
            Map<String, String> result = JsonParser.parseObject("   ");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should throw on non-object JSON")
        void throwsOnNonObject() {
            assertThrows(IllegalArgumentException.class, () ->
                    JsonParser.parseObject("[1,2,3]"));
        }

        @Test
        @DisplayName("Should parse work instruction request body")
        void parsesWorkInstructionBody() {
            Map<String, String> result = JsonParser.parseObject(
                    "{\"workInstructionId\":\"WI-001\",\"workQueueId\":\"WQ-001\"," +
                    "\"fetchChe\":\"RTG-01\",\"status\":\"PENDING\"," +
                    "\"estimatedMoveTime\":\"2024-01-01T10:00:00Z\"}");
            assertEquals("WI-001", result.get("workInstructionId"));
            assertEquals("WQ-001", result.get("workQueueId"));
            assertEquals("RTG-01", result.get("fetchChe"));
            assertEquals("PENDING", result.get("status"));
            assertEquals("2024-01-01T10:00:00Z", result.get("estimatedMoveTime"));
        }

        @Test
        @DisplayName("Should parse tick request body")
        void parsesTickBody() {
            Map<String, String> result = JsonParser.parseObject("{\"seconds\":300}");
            assertEquals("300", result.get("seconds"));
        }

        @Test
        @DisplayName("Should parse action completed request body")
        void parsesActionCompletedBody() {
            Map<String, String> result = JsonParser.parseObject(
                    "{\"actionId\":\"550e8400-e29b-41d4-a716-446655440000\",\"workQueueId\":\"WQ-001\"}");
            assertEquals("550e8400-e29b-41d4-a716-446655440000", result.get("actionId"));
            assertEquals("WQ-001", result.get("workQueueId"));
        }
    }
}
