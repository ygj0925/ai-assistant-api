/*
 * Copyright (c) 2022-present Charles7c Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package top.continew.admin.assistant.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.EnumSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/**
 * Emits privacy-safe operational samples and state transitions for assistant generation.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantOperations {

    private static final long RECENT_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final int MIN_PROVIDER_FAILURES = 5;

    private static final String ACTIVE =
        """
            SELECT COUNT(CASE WHEN r.state = 'queued' THEN 1 END) AS queued,
                MIN(CASE WHEN r.state = 'queued' THEN r.created_at END) AS oldest_queued,
                COUNT(CASE WHEN r.state = 'running' THEN 1 END) AS running,
                COUNT(CASE WHEN r.state = 'running' AND (r.lease_until IS NULL OR r.lease_until <= ?) THEN 1 END)
                    AS overdue_leases,
                COALESCE(SUM(CASE WHEN c.settled_units IS NULL THEN c.reserved_units END), 0) AS reserved_units,
                COUNT(CASE WHEN c.run_id IS NULL OR c.settled_units IS NOT NULL THEN 1 END) AS charge_mismatches
            FROM assistant_run r LEFT JOIN assistant_run_charge c ON c.run_id = r.id
            WHERE r.state IN ('queued', 'running')
            """;

    private static final String RECENT =
        """
            SELECT COUNT(CASE WHEN r.state = 'completed' THEN 1 END) AS completed,
                COUNT(CASE WHEN r.state = 'failed' AND r.error_code IN ('PROVIDER_UNAVAILABLE', 'RATE_LIMITED')
                    THEN 1 END) AS provider_failures,
                COALESCE(SUM(c.settled_units), 0) AS settled_units,
                COUNT(CASE WHEN c.settled_units IS NULL OR c.settled_units > c.reserved_units THEN 1 END)
                    AS charge_mismatches
            FROM assistant_run r LEFT JOIN assistant_run_charge c ON c.run_id = r.id
            WHERE r.state IN ('completed', 'failed', 'cancelled') AND r.created_at >= ?
            """;

    private final JdbcTemplate jdbc;
    private final long intervalMillis;
    private final long queueDelayMillis;
    private final int queueDepth;
    private final EnumSet<Alert> alerts = EnumSet.noneOf(Alert.class);
    private final AtomicBoolean pollFailed = new AtomicBoolean();
    private final ScheduledExecutorService sampler =
        Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "assistant-ops-sampler");
            thread.setDaemon(true);
            return thread;
        });

    /**
     * Creates a sampler with a dedicated JDBC statement deadline.
     *
     * @param dataSource application data source
     * @param intervalMillis delay between samples
     * @param queueDelayMillis maximum queue wait before warning
     * @param queueDepth maximum pending depth before warning
     */
    public AssistantOperations(DataSource dataSource,
        @Value("${assistant.observability.interval-millis:30000}") long intervalMillis,
        @Value("${assistant.observability.queue-delay-millis:30000}") long queueDelayMillis,
        @Value("${assistant.observability.queue-depth:25}") int queueDepth) {
        if (intervalMillis < 1000 || queueDelayMillis <= 0 || queueDepth <= 0) {
            throw new IllegalStateException("Invalid assistant monitoring interval or threshold.");
        }
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(5);
        this.intervalMillis = intervalMillis;
        this.queueDelayMillis = queueDelayMillis;
        this.queueDepth = queueDepth;
    }

    /**
     * Starts sampling on a dedicated thread after application initialization.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        sampler.scheduleWithFixedDelay(this::sample, intervalMillis, intervalMillis,
            TimeUnit.MILLISECONDS);
    }

    /**
     * Stops operational sampling on application shutdown.
     */
    @PreDestroy
    public void close() {
        sampler.shutdownNow();
    }

    /**
     * Starts an attempt using the durable run ID already carried by the client protocol.
     *
     * @param runId server-generated run ID
     * @param model catalog ID
     * @return model timing handle
     */
    public Generation startGeneration(String runId, String model) {
        String safeModel = switch (model) {
            case "local-echo", "dashscope", "ark" -> model;
            default -> "unavailable";
        };
        return new Generation(runId, safeModel);
    }

    /**
     * Reports queue polling availability at the first failure and subsequent recovery.
     *
     * @param succeeded whether the worker retrieved the next queue state
     */
    public void queuePoll(boolean succeeded) {
        boolean failed = !succeeded;
        if (pollFailed.getAndSet(failed) != failed) {
            logTransition("queue_poll", failed);
        }
    }

    /**
     * Reads operational counts and checks thresholds without loading row-level content.
     */
    void sample() {
        long now = System.currentTimeMillis();
        try {
            QueueSample queue = jdbc.queryForObject(ACTIVE, (row, index) -> {
                long queued = row.getLong("queued");
                long oldestMillis =
                    queued == 0 ? 0 : Math.max(0, now - row.getLong("oldest_queued"));
                return new QueueSample(queued, oldestMillis, row.getLong("running"),
                    row.getLong("overdue_leases"), row.getLong("reserved_units"),
                    row.getLong("charge_mismatches"));
            }, now);
            RecentSample recent = jdbc.queryForObject(RECENT, (row, index) -> new RecentSample(
                row.getLong("completed"), row.getLong("provider_failures"),
                row.getLong("settled_units"), row.getLong("charge_mismatches")),
                now - RECENT_WINDOW_MILLIS);
            if (queue == null || recent == null) {
                updateAlert(Alert.SAMPLE_UNAVAILABLE, true);
                return;
            }
            log.info(
                "event=assistant_ops_sample queued={} oldest_queued_ms={} running={} overdue_leases={}"
                    + " reserved_units={} recent_settled_units={} active_charge_mismatches={}"
                    + " recent_charge_mismatches={} recent_completed={} recent_provider_failures={}",
                queue.queued(), queue.oldestMillis(), queue.running(), queue.overdueLeases(),
                queue.reservedUnits(), recent.settledUnits(), queue.chargeMismatches(),
                recent.chargeMismatches(), recent.completed(), recent.providerFailures());
            updateAlert(Alert.SAMPLE_UNAVAILABLE, false);
            applySample(queue, recent);
        } catch (RuntimeException failure) {
            updateAlert(Alert.SAMPLE_UNAVAILABLE, true);
        }
    }

    void applySample(QueueSample queue, RecentSample recent) {
        updateAlert(Alert.QUEUE_BACKLOG,
            queue.queued() >= queueDepth || queue.oldestMillis() >= queueDelayMillis);
        updateAlert(Alert.OVERDUE_LEASE, queue.overdueLeases() > 0);
        updateAlert(Alert.LEDGER_MISMATCH,
            queue.chargeMismatches() > 0 || recent.chargeMismatches() > 0);
        long failures = recent.providerFailures();
        updateAlert(Alert.PROVIDER_FAILURE_RATIO,
            failures >= MIN_PROVIDER_FAILURES && failures >= (recent.completed() + failures) / 4.0);
    }

    private void updateAlert(Alert alert, boolean firing) {
        boolean changed = firing ? alerts.add(alert) : alerts.remove(alert);
        if (changed) {
            logTransition(alert.code, firing);
        }
    }

    private static void logTransition(String rule, boolean firing) {
        if (firing) {
            log.warn("event=assistant_ops_alert rule={} state=firing", rule);
        } else {
            log.info("event=assistant_ops_alert rule={} state=recovered", rule);
        }
    }

    record QueueSample(long queued, long oldestMillis, long running, long overdueLeases,
        long reservedUnits,
        long chargeMismatches) {
    }

    record RecentSample(long completed, long providerFailures, long settledUnits,
        long chargeMismatches) {
    }

    enum Alert {

        QUEUE_BACKLOG("queue_backlog"),
        OVERDUE_LEASE("overdue_lease"),
        LEDGER_MISMATCH("ledger_mismatch"),
        PROVIDER_FAILURE_RATIO("provider_failure_ratio"),
        SAMPLE_UNAVAILABLE("sample_unavailable");

        private final String code;

        Alert(String code) {
            this.code = code;
        }
    }

    /**
     * Fixed outcomes used in aggregate analysis of model attempts.
     */
    public enum Outcome {
        COMPLETED, CANCELLED, LEASE_LOST, RATE_LIMITED, PROVIDER_UNAVAILABLE, FAILED, INTERRUPTED
    }

    /**
     * Timing of one generation attempt and its first visible text fragment.
     */
    public static final class Generation {

        private final String runId;
        private final String model;
        private final long startedNanos = System.nanoTime();
        private long firstDeltaMillis = -1;

        private Generation(String runId, String model) {
            this.runId = runId;
            this.model = model;
        }

        /**
         * Records latency at the first visible fragment from the model.
         */
        public void firstDelta() {
            if (firstDeltaMillis < 0) {
                firstDeltaMillis = elapsedMillis();
            }
        }

        /**
         * Writes the safe run-level event.
         *
         * @param outcome attempt result
         */
        public void finish(Outcome outcome) {
            log.info(
                "event=assistant_model_attempt run_id={} model={} outcome={} duration_ms={} first_delta_ms={}",
                runId, model, outcome, elapsedMillis(), firstDeltaMillis);
        }

        private long elapsedMillis() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        }
    }
}
