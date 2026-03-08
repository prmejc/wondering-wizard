package com.wonderingwizard.processors;

public record ResourceAction(String actionName, int duration, boolean firstInTakt, String anchorsOn, boolean onlyOnePerTakt) {
    public ResourceAction(String actionName, int duration, boolean firstInTakt) {
        this(actionName, duration, firstInTakt, null, false);
    }

    public ResourceAction(String actionName, int duration, boolean firstInTakt, String anchorsOn) {
        this(actionName, duration, firstInTakt, anchorsOn, false);
    }
}
