package com.wonderingwizard.domain.takt;

/**
 * Represents an action within a Takt.
 * Actions are the individual operations performed during a takt cycle.
 *
 * @param description the action description (e.g., "QC lift container from truck")
 */
public record Action(String description) {
}
