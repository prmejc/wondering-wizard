package com.wonderingwizard.domain.takt;

/**
 * A condition that must be satisfied before an active action can be completed.
 * Actions with completion conditions require all conditions to be satisfied
 * (by external events) before the action transitions from ACTIVE to COMPLETED.
 *
 * @param id unique identifier for this condition (used for tracking satisfaction)
 * @param type the condition type (e.g., "QC_ASSET_EVENT")
 * @param description human-readable description of what this condition expects
 */
public record CompletionCondition(String id, String type, String description) {}
