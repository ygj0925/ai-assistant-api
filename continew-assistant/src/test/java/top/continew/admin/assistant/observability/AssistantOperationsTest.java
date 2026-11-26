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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import top.continew.admin.assistant.observability.AssistantOperations.Outcome;
import top.continew.admin.assistant.observability.AssistantOperations.QueueSample;
import top.continew.admin.assistant.observability.AssistantOperations.RecentSample;

import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Tests alert transitions and the privacy boundaries of model attempt logs.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantOperationsTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(AssistantOperations.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private final DataSource dataSource = mock(DataSource.class);
    private final AssistantOperations operations =
        new AssistantOperations(dataSource, 30_000, 30_000, 25);

    @BeforeEach
    void collectLogs() {
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void releaseLogs() {
        operations.close();
        logger.detachAppender(events);
        events.stop();
    }

    @Test
    void detectsConditionsOnceAndRearmsAfterRecovery() {
        var overloaded = new QueueSample(3, 30_000, 7, 1, 2048, 0);
        var unhealthy = new RecentSample(15, 5, 22, 1);
        var emptyQueue = new QueueSample(0, 0, 0, 0, 0, 0);
        var healthy = new RecentSample(16, 5, 22, 0);

        operations.applySample(overloaded, unhealthy);
        operations.applySample(overloaded, unhealthy);
        operations.applySample(emptyQueue, healthy);
        operations.applySample(emptyQueue, healthy);
        operations.applySample(overloaded, unhealthy);

        assertThat(events.list.stream().filter(event -> event.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)).containsExactly(
                firing("queue_backlog"), firing("overdue_lease"), firing("ledger_mismatch"),
                firing("provider_failure_ratio"), firing("queue_backlog"), firing("overdue_lease"),
                firing("ledger_mismatch"), firing("provider_failure_ratio"));
        assertThat(events.list.stream().filter(event -> event.getLevel() == Level.INFO)
            .map(ILoggingEvent::getFormattedMessage)).containsExactly(
                recovered("queue_backlog"), recovered("overdue_lease"),
                recovered("ledger_mismatch"),
                recovered("provider_failure_ratio"));
    }

    @Test
    void ignoresSparseFailuresAndUsesQueueDepthAndActiveLedgerChecks() {
        operations.applySample(new QueueSample(25, 0, 1, 0, 0, 1), new RecentSample(0, 4, 0, 0));
        assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly(firing("queue_backlog"), firing("ledger_mismatch"));
    }

    @Test
    void announcesPollHealthAndSanitizesDatabaseFailures() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("private connection detail"));
        operations.queuePoll(false);
        operations.queuePoll(false);
        operations.queuePoll(true);
        operations.queuePoll(true);
        operations.sample();
        operations.sample();

        assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly(firing("queue_poll"), recovered("queue_poll"),
                firing("sample_unavailable"));
        assertThat(events.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    @Test
    void logsRunCorrelationAndFirstFragmentWithoutArbitraryTags() {
        String runId = "f77c6974-5029-425b-9f1a-d1de870a74d5";
        var generation = operations.startGeneration(runId, "local-echo");
        generation.firstDelta();
        generation.firstDelta();
        generation.finish(Outcome.COMPLETED);
        operations.startGeneration(runId, "unknown deployment metadata")
            .finish(Outcome.RATE_LIMITED);

        assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage).satisfiesExactly(
            successful -> assertThat(successful)
                .matches("event=assistant_model_attempt run_id=" + runId
                    + " model=local-echo outcome=COMPLETED duration_ms=\\d+ first_delta_ms=\\d+"),
            limited -> assertThat(limited).matches("event=assistant_model_attempt run_id=" + runId
                + " model=unavailable outcome=RATE_LIMITED duration_ms=\\d+ first_delta_ms=-1"));
    }

    private static String firing(String rule) {
        return "event=assistant_ops_alert rule=" + rule + " state=firing";
    }

    private static String recovered(String rule) {
        return "event=assistant_ops_alert rule=" + rule + " state=recovered";
    }
}
