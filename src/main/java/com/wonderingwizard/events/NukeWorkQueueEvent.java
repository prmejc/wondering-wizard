package com.wonderingwizard.events;

import com.wonderingwizard.engine.Event;

/**
 * Event that deletes all data for a work queue: the work queue itself,
 * all its work instructions, and any active schedule.
 *
 * @param workQueueId the work queue to nuke
 */
public record NukeWorkQueueEvent(long workQueueId) implements Event {}
