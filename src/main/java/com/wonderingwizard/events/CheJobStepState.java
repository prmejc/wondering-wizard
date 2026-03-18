package com.wonderingwizard.events;

/**
 * Job step state of a Container Handling Equipment (CHE).
 */
public enum CheJobStepState {
    IDLE("IDLE"),
    LOGGED_OUT("LOGGEDOUT"),
    TO_ROW_TO_COLLECT_CNTR("TOROWTOCOLLECTCNTR"),
    AT_ROW_TO_COLLECT_CNTR("ATROWTOCOLLECTCNTR"),
    TO_ROW_TO_DROP_CNTR("TOROWTODROPCNTR"),
    AT_ROW_TO_DROP_CNTR("ATROWTODROPCNTR"),
    TO_SHIP_TO_COLLECT_CNTR("TOSHIPTOCOLLECTCNTR"),
    AT_SHIP_TO_COLLECT_CNTR("ATSHIPTOCOLLECTCNTR"),
    TO_SHIP_TO_DROP_CNTR("TOSHIPTODROPCNTR"),
    AT_SHIP_TO_DROP_CNTR("ATSHIPTODROPCNTR");

    private final String code;

    CheJobStepState(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * Looks up a CheJobStepState by its code (case-insensitive).
     *
     * @param code the code string
     * @return the matching CheJobStepState
     * @throws IllegalArgumentException if no match is found
     */
    public static CheJobStepState fromCode(String code) {
        for (CheJobStepState state : values()) {
            if (state.code.equalsIgnoreCase(code)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown CHE job step state: " + code);
    }
}
