package com.wonderingwizard.events;

/**
 * Constants for work instruction event type values as received from Kafka.
 */
public final class EventType {

    private EventType() {}

    public static final String CARRY_CHE_ASSIGNED = "CarryCHE Assigned";
    public static final String CARRY_CHE_CHANGED = "CarryCHE Changed";
    public static final String DISPATCH_CHANGED = "Dispatch Changed";
    public static final String ESTIMATED_CYCLE_TIME_ASSIGNED = "Estimated Cycle Time Assigned";
    public static final String ESTIMATED_CYCLE_TIME_CHANGED = "Estimated Cycle Time Changed";
    public static final String ESTIMATED_MOVE_TIME_ASSIGNED = "Estimated Move Time Assigned";
    public static final String ESTIMATED_MOVE_TIME_CHANGED = "Estimated Move Time Changed";
    public static final String FETCH_CHE_ASSIGNED = "FetchCHE Assigned";
    public static final String FETCH_CHE_CHANGED = "FetchCHE Changed";
    public static final String PINNING_CHANGED = "Pinning Changed";
    public static final String PUT_CHE_ASSIGNED = "PutCHE Assigned";
    public static final String PUT_CHE_CHANGED = "PutCHE Changed";
    public static final String QC_DISCHARGED_CONTAINER = "QC Discharged Container";
    public static final String QC_LOADED_CONTAINER = "QC Loaded Container";
    public static final String STATE_FETCH_CHANGED = "StateFetch Changed";
    public static final String TO_POSITION_CHANGED = "To Position Changed";
    public static final String WI_ABANDONED = "WI Abandoned";
    public static final String WI_BYPASSED = "WI Bypassed";
    public static final String WI_CREATED = "WI Created";
    public static final String WI_MOVE_STAGE_CHANGED = "WI MoveStage Changed";
    public static final String WI_RESEQUENCED = "WI Resequenced";
    public static final String WI_RESET = "WI Reset";
    public static final String WI_RESUMED = "WI Resumed";
    public static final String WI_REVERTED = "WI Reverted";
    public static final String WQ_CHANGE = "WQ Change";
}
