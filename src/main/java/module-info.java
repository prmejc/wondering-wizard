module com.wonderingwizard {
    requires java.logging;
    requires jdk.httpserver;

    exports com.wonderingwizard;
    exports com.wonderingwizard.engine;
    exports com.wonderingwizard.events;
    exports com.wonderingwizard.sideeffects;
    exports com.wonderingwizard.processors;
    exports com.wonderingwizard.server;
    exports com.wonderingwizard.domain.takt;
}
