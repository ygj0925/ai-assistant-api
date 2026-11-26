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

package top.continew.admin.assistant.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.analysis.AssistantDataAnalysis.Point;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Verifies bounded CSV parsing, decimal results, and a deterministic CPU timeout.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class NumericCsvAnalysisTest {

    @Test
    void computesExactDecimalsAndExportsOnlyNumbers() {
        String source = "\ufeff\"Name, notes\", Value\r\n"
            + "\"=1+SUM(2,3)\",1.20\r\n"
            + "\"embedded \"\"quote\"\"\nand newline\",\r\n"
            + "last,-0.2\r\n"
            + "extra,+2\r\n";
        var result = NumericCsvAnalysis.run(source, NumericCsvAnalysis.column(" Value "), () -> 0L);
        assertThat(result.rowCount()).isEqualTo(4);
        assertThat(result.valueCount()).isEqualTo(3);
        assertThat(result.min()).isEqualTo("-0.2");
        assertThat(result.max()).isEqualTo("2");
        assertThat(result.sum()).isEqualTo("3");
        assertThat(result.mean()).isEqualTo("1");
        assertThat(result.series()).containsExactly(new Point(1, "1.2"), new Point(3, "-0.2"),
            new Point(4, "2"));
        assertThat(result.artifact()).isEqualTo("row,value\r\n1,1.2\r\n3,-0.2\r\n4,2\r\n");
    }

    @Test
    void preservesMaximumDecimalPrecisionAndAppliesExplicitMeanRounding() {
        var result = NumericCsvAnalysis.run("Value\n999999999999999999.000000000001\n"
            + "999999999999999999.000000000002", "Value", () -> 0L);
        assertThat(result.sum()).isEqualTo("1999999999999999998.000000000003");
        assertThat(result.mean()).isEqualTo("999999999999999999.000000000002");
    }

    @Test
    void boundsTheChartButReturnsEveryNumericRecordInTheAttachment() {
        var result = NumericCsvAnalysis.run("Value\n" + "1\n".repeat(256), "Value", () -> 0L);
        assertThat(result.series()).hasSize(32).endsWith(new Point(32, "1"));
        assertThat(result.artifact().lines()).hasSize(257);
        assertThat(result.artifact()).endsWith("256,1\r\n");
    }

    @Test
    void rejectsMalformedOrUnboundedRecordsWithAStableError() {
        List<String> invalid = List.of("", "Value", "Value,value,Value\n1,2,3",
            "Other\n1", "Value,Other\n1", "Value,Other\n1,\"missing",
            "Value,Other\n1,\"x\"unexpected", "Value\n1\"2", "Value\n1e2",
            "Value\n=2", "Value\n9999999999999999999", "Value\n0.0000000000001",
            "Value,Other\n,empty\n", "Value\n" + "1\n".repeat(257),
            "Value," + "column,".repeat(31) + "overflow\n1", "Value\n" + "甲".repeat(8192));
        for (String source : invalid) {
            assertThatThrownBy(() -> NumericCsvAnalysis.run(source, "Value", () -> 0L))
                .isInstanceOfSatisfying(AssistantApiException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(failure.code()).isEqualTo("VALIDATION_ERROR");
                });
        }
    }

    @Test
    void failsClosedAtTheComputationDeadline() {
        var instant = new AtomicLong();
        assertThatThrownBy(() -> NumericCsvAnalysis.run("Value\n1", "Value",
            () -> instant.getAndAdd(TimeUnit.MILLISECONDS.toNanos(250))))
            .isInstanceOfSatisfying(AssistantApiException.class, failure -> {
                assertThat(failure.status()).isEqualTo(HttpStatus.REQUEST_TIMEOUT);
                assertThat(failure.code()).isEqualTo("ANALYSIS_TIMEOUT");
            });
    }
}
