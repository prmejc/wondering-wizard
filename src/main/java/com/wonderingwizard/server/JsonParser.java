package com.wonderingwizard.server;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal flat-object JSON parser for incoming HTTP request bodies.
 * Supports parsing simple JSON objects with string and number values.
 * Zero external dependencies.
 */
public final class JsonParser {

    private JsonParser() {}

    /**
     * Parses a flat JSON object into a map of string keys to string values.
     * Handles string, number, boolean, and null values (all returned as strings).
     *
     * @param json the JSON string to parse
     * @return a map of field names to their string values
     * @throws IllegalArgumentException if the JSON is malformed
     */
    public static Map<String, String> parseObject(String json) {
        Map<String, String> result = new HashMap<>();
        if (json == null || json.isBlank()) {
            return result;
        }

        String trimmed = json.strip();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new IllegalArgumentException("JSON must be an object: " + trimmed);
        }

        String content = trimmed.substring(1, trimmed.length() - 1).strip();
        if (content.isEmpty()) {
            return result;
        }

        int pos = 0;
        while (pos < content.length()) {
            // Skip whitespace
            pos = skipWhitespace(content, pos);
            if (pos >= content.length()) break;

            // Parse key
            if (content.charAt(pos) != '"') {
                throw new IllegalArgumentException("Expected '\"' at position " + pos);
            }
            int keyEnd = findClosingQuote(content, pos);
            String key = unescape(content.substring(pos + 1, keyEnd));
            pos = keyEnd + 1;

            // Skip whitespace and colon
            pos = skipWhitespace(content, pos);
            if (pos >= content.length() || content.charAt(pos) != ':') {
                throw new IllegalArgumentException("Expected ':' at position " + pos);
            }
            pos++;
            pos = skipWhitespace(content, pos);

            // Parse value
            String value;
            if (content.charAt(pos) == '"') {
                int valEnd = findClosingQuote(content, pos);
                value = unescape(content.substring(pos + 1, valEnd));
                pos = valEnd + 1;
            } else if (content.substring(pos).startsWith("null")) {
                value = null;
                pos += 4;
            } else if (content.substring(pos).startsWith("true")) {
                value = "true";
                pos += 4;
            } else if (content.substring(pos).startsWith("false")) {
                value = "false";
                pos += 5;
            } else {
                // Number - read until comma, whitespace, or end
                int numStart = pos;
                while (pos < content.length() && content.charAt(pos) != ',' && !Character.isWhitespace(content.charAt(pos)) && content.charAt(pos) != '}') {
                    pos++;
                }
                value = content.substring(numStart, pos);
            }

            result.put(key, value);

            // Skip whitespace and comma
            pos = skipWhitespace(content, pos);
            if (pos < content.length() && content.charAt(pos) == ',') {
                pos++;
            }
        }

        return result;
    }

    private static int skipWhitespace(String s, int pos) {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
            pos++;
        }
        return pos;
    }

    private static int findClosingQuote(String s, int openPos) {
        int pos = openPos + 1;
        while (pos < s.length()) {
            if (s.charAt(pos) == '\\') {
                pos += 2; // Skip escaped character
            } else if (s.charAt(pos) == '"') {
                return pos;
            } else {
                pos++;
            }
        }
        throw new IllegalArgumentException("Unterminated string at position " + openPos);
    }

    private static String unescape(String s) {
        if (!s.contains("\\")) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    default -> { sb.append('\\'); sb.append(next); }
                }
                i++;
            } else {
                sb.append(s.charAt(i));
            }
        }
        return sb.toString();
    }
}
