package com.wonderingwizard.metrics;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentSelector;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.View;
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer;

import java.util.List;
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

    /** Histogram buckets in milliseconds: 0.01, 0.05, 0.1, 0.5, 1, 2, 5, 10, 25, 50, 100, 250, 500, 1000, 5000 */
    private static final List<Double> MS_BUCKETS = List.of(
            0.01, 0.05, 0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 5000.0);

    public Metrics(int prometheusPort) {
        prometheusServer = PrometheusHttpServer.builder()
                .setPort(prometheusPort)
                .build();

        // Custom bucket boundaries for ms-level histograms
        var msBucketView = View.builder()
                .setAggregation(Aggregation.explicitBucketHistogram(MS_BUCKETS))
                .build();

        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(prometheusServer)
                .registerView(
                        InstrumentSelector.builder().setName("fes_kafka_processing_duration_ms").build(),
                        msBucketView)
                .registerView(
                        InstrumentSelector.builder().setName("fes_engine_processing_duration_ms").build(),
                        msBucketView)
                .build();

        OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .buildAndRegisterGlobal();

        Meter meter = meterProvider.get("com.wonderingwizard");

        kafkaMessagesTotal = meter.counterBuilder("fes_kafka_messages_total")
                .setDescription("Total Kafka messages consumed")
                .build();

        kafkaProcessingDuration = meter.histogramBuilder("fes_kafka_processing_duration_ms")
                .setDescription("Time to process a single Kafka message (map + engine) in milliseconds")
                .build();

        engineProcessingDuration = meter.histogramBuilder("fes_engine_processing_duration_ms")
                .setDescription("Time to process a single event through the engine in milliseconds")
                .build();

        // Record initial zero-value so metrics appear in Prometheus immediately
        // (OTEL histograms are invisible until the first recording)
        kafkaProcessingDuration.record(0, Attributes.of(TOPIC, "warmup"));
        engineProcessingDuration.record(0, Attributes.of(EVENT_TYPE, "warmup"));

        // Register JVM runtime metrics (heap, CPU, GC, threads)
        io.opentelemetry.instrumentation.runtimemetrics.java17.RuntimeMetrics.builder(
                OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build()
        ).enableAllFeatures().build();

        logger.info("OTEL metrics initialized, Prometheus endpoint on port " + prometheusPort);
    }

    /** Record a consumed Kafka message with processing duration in nanoseconds. */
    public void recordKafkaMessage(String topic, long durationNanos) {
        Attributes attrs = Attributes.of(TOPIC, topic);
        kafkaMessagesTotal.add(1, attrs);
        kafkaProcessingDuration.record(durationNanos / 1_000_000.0, attrs);
    }

    /** Record engine event processing duration in nanoseconds. */
    public void recordEngineProcessing(String eventType, long durationNanos) {
        Attributes attrs = Attributes.of(EVENT_TYPE, eventType);
        engineProcessingDuration.record(durationNanos / 1_000_000.0, attrs);
    }

    public void shutdown() {
        if (prometheusServer != null) {
            prometheusServer.shutdown();
        }
    }
}
