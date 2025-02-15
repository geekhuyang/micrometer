/**
 * Copyright 2020 VMware, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.core.instrument.binder.kafka;

import io.micrometer.core.annotation.Incubating;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.util.NamedThreadFactory;
import io.micrometer.core.lang.NonNullApi;
import io.micrometer.core.lang.NonNullFields;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import io.micrometer.core.lang.Nullable;
import io.micrometer.core.util.internal.logging.InternalLogger;
import io.micrometer.core.util.internal.logging.InternalLoggerFactory;
import io.micrometer.core.util.internal.logging.WarnThenDebugLogger;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;

import static java.util.Collections.emptyList;

/**
 * Kafka metrics binder. This should be closed on application shutdown to clean up resources.
 *
 * @author Jorge Quilcate
 * @see <a href="https://docs.confluent.io/current/kafka/monitoring.html">Kakfa monitoring
 * documentation</a>
 * @since 1.4.0
 */
@Incubating(since = "1.4.0")
@NonNullApi
@NonNullFields
class KafkaMetrics implements MeterBinder, AutoCloseable {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(KafkaMetrics.class);
    private static final WarnThenDebugLogger warnThenDebugLogger = new WarnThenDebugLogger(KafkaMetrics.class);

    static final String METRIC_NAME_PREFIX = "kafka.";
    static final String METRIC_GROUP_APP_INFO = "app-info";
    static final String METRIC_GROUP_METRICS_COUNT = "kafka-metrics-count";
    static final String VERSION_METRIC_NAME = "version";
    static final String START_TIME_METRIC_NAME = "start-time-ms";
    static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(60);
    static final String KAFKA_VERSION_TAG_NAME = "kafka.version";
    static final String DEFAULT_VALUE = "unknown";

    private final Supplier<Map<MetricName, ? extends Metric>> metricsSupplier;
    private final Iterable<Tag> extraTags;
    private final Duration refreshInterval;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("micrometer-kafka-metrics"));

    @Nullable
    private Iterable<Tag> commonTags;

    /**
     * Keeps track of current set of metrics.
     */
    private volatile Set<MetricName> currentMeters = new HashSet<>();

    private String kafkaVersion = DEFAULT_VALUE;

    @Nullable
    private volatile MeterRegistry registry;

    private final Set<Meter> registeredMeters = ConcurrentHashMap.newKeySet();

    KafkaMetrics(Supplier<Map<MetricName, ? extends Metric>> metricsSupplier) {
        this(metricsSupplier, emptyList());
    }

    KafkaMetrics(Supplier<Map<MetricName, ? extends Metric>> metricsSupplier, Iterable<Tag> extraTags) {
        this(metricsSupplier, extraTags, DEFAULT_REFRESH_INTERVAL);
    }

    KafkaMetrics(Supplier<Map<MetricName, ? extends Metric>> metricsSupplier, Iterable<Tag> extraTags, Duration refreshInterval) {
        this.metricsSupplier = metricsSupplier;
        this.extraTags = extraTags;
        this.refreshInterval = refreshInterval;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = registry;

        commonTags = getCommonTags(registry);
        prepareToBindMetrics(registry);
        checkAndBindMetrics(registry);
        scheduler.scheduleAtFixedRate(() -> checkAndBindMetrics(registry), getRefreshIntervalInMillis(), getRefreshIntervalInMillis(), TimeUnit.MILLISECONDS);
    }

    private Iterable<Tag> getCommonTags(MeterRegistry registry) {
        // FIXME hack until we have proper API to retrieve common tags
        Meter.Id dummyId = Meter.builder("delete.this", Meter.Type.OTHER, Collections.emptyList()).register(registry).getId();
        registry.remove(dummyId);
        return dummyId.getTags();
    }

    /**
     * Define common tags and meters before binding metrics
     */
    void prepareToBindMetrics(MeterRegistry registry) {
        Map<MetricName, ? extends Metric> metrics = metricsSupplier.get();
        // Collect static metrics and tags
        Metric startTime = null;

        for (Map.Entry<MetricName, ? extends Metric> entry : metrics.entrySet()) {
            MetricName name = entry.getKey();
            if (METRIC_GROUP_APP_INFO.equals(name.group()))
                if (VERSION_METRIC_NAME.equals(name.name())) {
                    kafkaVersion = (String) entry.getValue().metricValue();
                } else if (START_TIME_METRIC_NAME.equals(name.name())) {
                    startTime = entry.getValue();
                }
        }

        if (startTime != null) {
            bindMeter(registry, metricsSupplier, startTime.metricName(), meterName(startTime), meterTags(startTime));
        }
    }

    private long getRefreshIntervalInMillis() {
        return refreshInterval.toMillis();
    }

    /**
     * Gather metrics from Kafka metrics API and register Meters.
     * <p>
     * As this is a one-off execution when binding a Kafka client, Meters include a call to this
     * validation to double-check new metrics when returning values. This should only add the cost of
     * comparing meters last returned from the Kafka client.
     */
    void checkAndBindMetrics(MeterRegistry registry) {
        Map<MetricName, ? extends Metric> metrics = metricsSupplier.get();

        if (!currentMeters.equals(metrics.keySet())) {
            currentMeters = new HashSet<>(metrics.keySet());

            Map<String, List<Meter>> registryMetersByNames = registry.getMeters().stream()
                    .collect(Collectors.groupingBy(meter -> meter.getId().getName()));

            metrics.forEach((name, metric) -> {
                // Filter out non-numeric values
                // Filter out metrics from groups that include metadata
                if (!(metric.metricValue() instanceof Number) ||
                        METRIC_GROUP_APP_INFO.equals(name.group()) ||
                        METRIC_GROUP_METRICS_COUNT.equals(name.group())) {
                    return;
                }

                String meterName = meterName(metric);

                // Kafka has metrics with lower number of tags (e.g. with/without topic or partition tag)
                // Remove meters with lower number of tags
                boolean hasLessTags = false;
                for (Meter other : registryMetersByNames.getOrDefault(meterName, emptyList())) {
                    List<Tag> tags = other.getId().getTags();
                    List<Tag> meterTagsWithCommonTags = meterTags(metric, true);
                    if (tags.size() < meterTagsWithCommonTags.size()) {
                        registry.remove(other);
                        registeredMeters.remove(other);
                    }
                    // Check if already exists
                    else if (tags.size() == meterTagsWithCommonTags.size())
                        if (tags.containsAll(meterTagsWithCommonTags)) return;
                        else break;
                    else hasLessTags = true;
                }
                if (hasLessTags) return;

                List<Tag> tags = meterTags(metric);
                try {
                    Meter meter = bindMeter(registry, metricsSupplier, metric.metricName(), meterName, tags);
                    List<Meter> meters = registryMetersByNames.computeIfAbsent(meterName, k -> new ArrayList<>());
                    meters.add(meter);
                }
                catch (Exception ex) {
                    String message = ex.getMessage();
                    if (message != null && message.contains("Prometheus requires")) {
                        warnThenDebugLogger.log("Failed to bind meter: " + meterName + " " + tags
                                + ". However, this could happen and might be restored in the next refresh.");
                    }
                    else {
                        log.warn("Failed to bind meter: " + meterName + " " + tags + ".", ex);
                    }
                }
            });
        }
    }

    private Meter bindMeter(MeterRegistry registry, Supplier<Map<MetricName, ? extends Metric>> metricsSupplier, MetricName metricName, String meterName, Iterable<Tag> tags) {
        Meter meter = registerMeter(registry, metricsSupplier, metricName, meterName, tags);
        registeredMeters.add(meter);
        return meter;
    }

    private Meter registerMeter(MeterRegistry registry, Supplier<Map<MetricName, ? extends Metric>> metricsSupplier, MetricName metricName, String meterName, Iterable<Tag> tags) {
        if (meterName.endsWith("total") || meterName.endsWith("count")) {
            return registerCounter(registry, metricsSupplier, metricName, meterName, tags);
        } else {
            return registerGauge(registry, metricsSupplier, metricName, meterName, tags);
        }
    }

    private Gauge registerGauge(MeterRegistry registry, Supplier<Map<MetricName, ? extends Metric>> metricsSupplier, MetricName metricName, String meterName, Iterable<Tag> tags) {
        return Gauge.builder(meterName, metricsSupplier, toMetricValue(metricName))
                .tags(tags)
                .description(metricName.description())
                .register(registry);
    }

    private FunctionCounter registerCounter(MeterRegistry registry, Supplier<Map<MetricName, ? extends Metric>> metricsSupplier, MetricName metricName, String meterName, Iterable<Tag> tags) {
        return FunctionCounter.builder(meterName, metricsSupplier, toMetricValue(metricName))
                .tags(tags)
                .description(metricName.description())
                .register(registry);
    }

    private ToDoubleFunction<Supplier<Map<MetricName, ? extends Metric>>> toMetricValue(MetricName metricName) {
        return metricsSupplier -> toDouble(metricsSupplier.get().get(metricName));
    }

    private double toDouble(@Nullable Metric metric) {
        return (metric != null) ? ((Number) metric.metricValue()).doubleValue() : Double.NaN;
    }

    private List<Tag> meterTags(Metric metric, boolean includeCommonTags) {
        List<Tag> tags = new ArrayList<>();
        metric.metricName().tags().forEach((key, value) -> tags.add(Tag.of(key.replaceAll("-", "."), value)));
        tags.add(Tag.of(KAFKA_VERSION_TAG_NAME, kafkaVersion));
        extraTags.forEach(tags::add);
        if (includeCommonTags) {
            commonTags.forEach(tags::add);
        }
        return tags;
    }

    private List<Tag> meterTags(Metric metric) {
        return meterTags(metric, false);
    }

    private String meterName(Metric metric) {
        String name = METRIC_NAME_PREFIX + metric.metricName().group() + "." + metric.metricName().name();
        return name.replaceAll("-metrics", "").replaceAll("-", ".");
    }

    @Override
    public void close() {
        this.scheduler.shutdownNow();

        for (Meter meter : registeredMeters) {
            registry.remove(meter);
        }
    }
}
