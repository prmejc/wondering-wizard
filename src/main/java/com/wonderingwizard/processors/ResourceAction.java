package com.wonderingwizard.processors;

import com.wonderingwizard.domain.takt.ActionType;

public record ResourceAction(ActionType actionType, String actionName, int duration, boolean firstInTakt, String anchorsOn, boolean onlyOnePerTakt) {
    public ResourceAction(ActionType actionType, int duration, boolean firstInTakt) {
        this(actionType, actionType.displayName(), duration, firstInTakt, null, false);
    }

    public ResourceAction(ActionType actionType, int duration, boolean firstInTakt, String anchorsOn) {
        this(actionType, actionType.displayName(), duration, firstInTakt, anchorsOn, false);
    }

    public ResourceAction(ActionType actionType, int duration, boolean firstInTakt, String anchorsOn, boolean onlyOnePerTakt) {
        this(actionType, actionType.displayName(), duration, firstInTakt, anchorsOn, onlyOnePerTakt);
    }
}
