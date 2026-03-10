package com.wonderingwizard.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LayoutTest {

    @Test
    @DisplayName("Should parse full position string Y-PTM-1L20E4")
    void parsesFullPosition() {
        YardLocation layout = YardLocation.parse("Y-PTM-1L20E4");

        assertNotNull(layout);
        assertEquals("1L", layout.block());
        assertEquals("20", layout.bay());
        assertEquals("E", layout.row());
        assertEquals("4", layout.height());
    }

    @Test
    @DisplayName("Should parse position with different prefix")
    void parsesDifferentPrefix() {
        YardLocation layout = YardLocation.parse("X-ABC-2M15B3");

        assertNotNull(layout);
        assertEquals("2M", layout.block());
        assertEquals("15", layout.bay());
        assertEquals("B", layout.row());
        assertEquals("3", layout.height());
    }

    @Test
    @DisplayName("Should parse bare 6-char location without prefix")
    void parsesBareLocation() {
        YardLocation layout = YardLocation.parse("1L20E4");

        assertNotNull(layout);
        assertEquals("1L", layout.block());
        assertEquals("20", layout.bay());
        assertEquals("E", layout.row());
        assertEquals("4", layout.height());
    }

    @Test
    @DisplayName("Should return null for null input")
    void returnsNullForNull() {
        assertNull(YardLocation.parse(null));
    }

    @Test
    @DisplayName("Should return null for empty string")
    void returnsNullForEmpty() {
        assertNull(YardLocation.parse(""));
    }

    @Test
    @DisplayName("Should return null when location part is too short")
    void returnsNullForShortLocation() {
        assertNull(YardLocation.parse("Y-PTM-1L2"));
    }

    @Test
    @DisplayName("Should return null when location part is too long")
    void returnsNullForLongLocation() {
        assertNull(YardLocation.parse("Y-PTM-1L20E44"));
    }

    @Test
    @DisplayName("Two positions with same bay should have equal bay")
    void sameBayComparison() {
        YardLocation a = YardLocation.parse("Y-PTM-1L20E4");
        YardLocation b = YardLocation.parse("Y-PTM-2M20B3");

        assertEquals(a.bay(), b.bay());
    }

    @Test
    @DisplayName("Two positions with different bay should not be equal")
    void differentBayComparison() {
        YardLocation a = YardLocation.parse("Y-PTM-1L20E4");
        YardLocation b = YardLocation.parse("Y-PTM-1L22E4");

        assertNotEquals(a.bay(), b.bay());
    }
}
