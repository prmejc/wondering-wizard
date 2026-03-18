package com.wonderingwizard.metrics;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer;

import java.util.logging.Logger;

/**
 * Central OTEL metrics for the event processing engine.
 * <p>
 * Exposes a Prometheus scrape endpoint on port 9464 (default).
 * Grafana can scrape {@code http://<host>:9464/metrics}.
 */
public class Metrics {

    private static final Logger logger = Logger.getLogger(Metrics.class.getName());

    private static final AttributeKey<String> TOPIC = AttributeKey.stringKey("topic");
    private static final AttributeKey<String> EVENT_TYPE = AttributeKey.stringKey("event_type");

    private final LongCounter kafkaMessagesTotal;
    private final DoubleHistogram kafkaProcessingDuration;
    private final DoubleHistogram engineProcessingDuration;
    private final PrometheusHttpServer prometheusServer;

    public Metrics() {
        this(9464);
    }

    public Metrics(int prometheusPort) {
        prometheusServer = PrometheusHttpServer.builder()
                .setPort(prometheusPort)
                .build();

        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(prometheusServer)
                .build();

        OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .buildAndRegisterGlobal();

        Meter meter = meterProvider.get("com.wonderingwizard");

        kafkaMessagesTotal = meter.counterBuilder("fes_kafka_messages_total")
                .setDescription("Total Kafka messages consumed")
                .build();

        kafkaProcessingDuration = meter.histogramBuilder("fes_kafka_processing_duration_seconds")
                .setDescription("Time to process a single Kafka message (map + engine)")
                .setUnit("s")
                .build();

        engineProcessingDuration = meter.histogramBuilder("fes_engine_processing_duration_seconds")
                .setDescription("Time to process a single event through the engine")
                .setUnit("s")
                .build();

        logger.info("OTEL metrics initialized, Prometheus endpoint on port " + prometheusPort);
    }

    /** Record a consumed Kafka message with processing duration. */
    public void recordKafkaMessage(String topic, double durationSeconds) {
        Attributes attrs = Attributes.of(TOPIC, topic);
        kafkaMessagesTotal.add(1, attrs);
        kafkaProcessingDuration.record(durationSeconds, attrs);
    }

    /** Record engine event processing duration by event type. */
    public void recordEngineProcessing(String eventType, double durationSeconds) {
        Attributes attrs = Attributes.of(EVENT_TYPE, eventType);
        engineProcessingDuration.record(durationSeconds, attrs);
    }

    public void shutdown() {
        if (prometheusServer != null) {
            prometheusServer.shutdown();
        }
    }
}
