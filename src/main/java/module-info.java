module com.wonderingwizard {
    requires java.logging;
    requires jdk.httpserver;
    requires jdk.jfr;
    requires io.opentelemetry.api;
    requires io.opentelemetry.sdk;
    requires io.opentelemetry.sdk.metrics;
    requires io.opentelemetry.sdk.common;
    requires io.opentelemetry.exporter.prometheus;

    exports com.wonderingwizard;
    exports com.wonderingwizard.engine;
    exports com.wonderingwizard.events;
    exports com.wonderingwizard.sideeffects;
    exports com.wonderingwizard.processors;
    exports com.wonderingwizard.server;
    exports com.wonderingwizard.domain.takt;
    exports com.wonderingwizard.kafka;
    exports com.wonderingwizard.metrics;
}
