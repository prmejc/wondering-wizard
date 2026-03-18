package com.wonderingwizard.events;

/**
 * Status of a Container Handling Equipment (CHE).
 */
public enum CheStatus {
    WORKING("Working"),
    UNAVAILABLE("Unavailable");

    private final String displayName;

    CheStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * Looks up a CheStatus by its display name (case-insensitive).
     *
     * @param name the display name
     * @return the matching CheStatus
     * @throws IllegalArgumentException if no match is found
     */
    public static CheStatus fromDisplayName(String name) {
        for (CheStatus status : values()) {
            if (status.displayName.equalsIgnoreCase(name)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown CHE status: " + name);
    }
}
