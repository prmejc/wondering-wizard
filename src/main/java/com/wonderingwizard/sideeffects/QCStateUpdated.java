package com.wonderingwizard.sideeffects;

import com.wonderingwizard.engine.SideEffect;
import com.wonderingwizard.events.QuayCraneMappingEvent;

/**
 * Side effect emitted when a quay crane's mapping state is updated.
 *
 * @param quayCraneShortName the short name of the quay crane
 * @param event the latest mapping event
 */
public record QCStateUpdated(String quayCraneShortName, QuayCraneMappingEvent event) implements SideEffect {}
