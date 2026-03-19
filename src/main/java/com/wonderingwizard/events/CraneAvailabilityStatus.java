package com.wonderingwizard.events;

/**
 * Availability status of a quay crane.
 */
public enum CraneAvailabilityStatus {

    READY("READY"),
    NOT_READY("NOT_READY");

    private final String code;

    CraneAvailabilityStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * Parse from the string code. Falls back to {@link #NOT_READY} for unknown values.
     */
    public static CraneAvailabilityStatus fromCode(String code) {
        if (code == null) return NOT_READY;
        for (CraneAvailabilityStatus s : values()) {
            if (s.code.equalsIgnoreCase(code)) return s;
        }
        return NOT_READY;
    }
}
