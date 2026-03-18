package com.wonderingwizard.domain.takt;

/**
 * Reason why an action was completed without being fully executed.
 */
public enum CompletionReason {
    TT_UNAVAILABLE("TT Unavailable");

    private final String displayName;

    CompletionReason(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
