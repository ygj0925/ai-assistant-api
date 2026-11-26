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

import org.springframework.http.HttpStatus;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.analysis.AssistantDataAnalysis.Point;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Runs a single built-in numeric summary without file, credential, network or execution access.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
final class NumericCsvAnalysis {

    static final String VERSION = "csv_numeric_v1";
    private static final int MAX_SOURCE_BYTES = 16 * 1024;
    private static final int MAX_HEADER_POINTS = 120;
    private static final int MAX_COLUMNS = 32;
    private static final int MAX_ROWS = 256;
    private static final int MAX_CHART_POINTS = 32;
    private static final int MEAN_SCALE = 12;
    private static final long BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final Pattern NUMBER = Pattern.compile("[+-]?[0-9]{1,18}(\\.[0-9]{1,12})?");

    private NumericCsvAnalysis() {
    }

    static String column(String input) {
        if (input == null || input.length() > MAX_HEADER_POINTS * 2
            || input.codePointCount(0, input.length()) > MAX_HEADER_POINTS
            || !StandardCharsets.UTF_8.newEncoder().canEncode(input)) {
            throw invalid("Select a column name of at most 120 characters.");
        }
        String column = input.strip();
        if (column.isEmpty() || column.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("Select a valid column name.");
        }
        return column;
    }

    static Result run(String source, String column, LongSupplier clock) {
        long started = clock.getAsLong();
        if (source.length() > MAX_SOURCE_BYTES
            || !StandardCharsets.UTF_8.newEncoder().canEncode(source)
            || source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw invalid("CSV must be at most 16 KiB of valid UTF-8 text.");
        }
        var records = new Records(source);
        List<String> headers = headers(records.next());
        int position = headers.indexOf(column);
        if (position < 0) {
            throw invalid("The selected column is missing from the CSV header.");
        }

        int rowCount = 0;
        int valueCount = 0;
        BigDecimal minimum = null;
        BigDecimal maximum = null;
        BigDecimal sum = BigDecimal.ZERO;
        var series = new ArrayList<Point>();
        var artifact = new StringBuilder("row,value\r\n");
        List<String> fields;
        while ((fields = records.next()) != null) {
            checkBudget(clock.getAsLong(), started);
            rowCount++;
            if (rowCount > MAX_ROWS) {
                throw invalid("CSV may contain at most 256 data rows.");
            }
            if (fields.size() != headers.size()) {
                throw invalid("CSV data rows must have the same number of columns as the header.");
            }
            String raw = fields.get(position).strip();
            if (raw.isEmpty()) {
                continue;
            }
            if (!NUMBER.matcher(raw).matches()) {
                throw invalid("Selected values must be bounded decimal numbers or empty cells.");
            }
            BigDecimal value = new BigDecimal(raw);
            minimum = minimum == null ? value : minimum.min(value);
            maximum = maximum == null ? value : maximum.max(value);
            sum = sum.add(value);
            valueCount++;
            String decimal = decimal(value);
            if (series.size() < MAX_CHART_POINTS) {
                series.add(new Point(rowCount, decimal));
            }
            artifact.append(rowCount).append(',').append(decimal).append("\r\n");
        }
        if (valueCount == 0) {
            throw invalid("The selected column has no decimal values.");
        }

        BigDecimal mean =
            sum.divide(BigDecimal.valueOf(valueCount), MEAN_SCALE, RoundingMode.HALF_UP);
        long completed = clock.getAsLong();
        checkBudget(completed, started);
        long duration = TimeUnit.NANOSECONDS.toMillis(completed - started);
        return new Result(rowCount, valueCount, decimal(minimum), decimal(maximum), decimal(sum),
            decimal(mean), List.copyOf(series), artifact.toString(), duration);
    }

    private static List<String> headers(List<String> input) {
        if (input == null) {
            throw invalid("CSV requires a header and numeric data.");
        }
        List<String> headers = input.stream().map(NumericCsvAnalysis::column).toList();
        if (new HashSet<>(headers).size() != headers.size()) {
            throw invalid("CSV column names must be unique.");
        }
        return headers;
    }

    private static void checkBudget(long current, long started) {
        if (current - started >= BUDGET_NANOS) {
            throw new AssistantApiException(HttpStatus.REQUEST_TIMEOUT, "ANALYSIS_TIMEOUT",
                "CSV analysis exceeded its time budget.");
        }
    }

    private static String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static AssistantApiException invalid(String message) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    record Result(int rowCount, int valueCount, String min, String max, String sum, String mean,
        List<Point> series, String artifact, long durationMillis) {
    }

    private static final class Records {

        private final String text;
        private int offset;

        private Records(String text) {
            this.text = text;
            this.offset = text.startsWith("\ufeff") ? 1 : 0;
        }

        private List<String> next() {
            if (offset == text.length()) {
                return null;
            }
            var fields = new ArrayList<String>();
            for (;;) {
                if (fields.size() == MAX_COLUMNS) {
                    throw invalid("CSV may contain at most 32 columns.");
                }
                fields
                    .add(offset < text.length() && text.charAt(offset) == '"' ? quoted() : bare());
                if (offset == text.length()) {
                    return fields;
                }
                char separator = text.charAt(offset++);
                if (separator == ',') {
                    continue;
                }
                if (separator == '\r' && offset < text.length() && text.charAt(offset) == '\n') {
                    offset++;
                }
                if (separator == '\r' || separator == '\n') {
                    return fields;
                }
                throw invalid("CSV has invalid characters after a quoted field.");
            }
        }

        private String quoted() {
            offset++;
            var field = new StringBuilder();
            while (offset < text.length()) {
                char current = text.charAt(offset++);
                if (current == '"') {
                    if (offset == text.length() || text.charAt(offset) != '"') {
                        return field.toString();
                    }
                    offset++;
                }
                field.append(current);
            }
            throw invalid("CSV contains an unterminated quoted field.");
        }

        private String bare() {
            int start = offset;
            while (offset < text.length()) {
                char current = text.charAt(offset);
                if (current == ',' || current == '\r' || current == '\n') {
                    break;
                }
                if (current == '"') {
                    throw invalid("CSV contains an unexpected quote.");
                }
                offset++;
            }
            return text.substring(start, offset);
        }
    }
}
