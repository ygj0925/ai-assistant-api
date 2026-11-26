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

package top.continew.admin.assistant.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import top.continew.admin.assistant.account.AssistantApiException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates a bounded typed grid and produces an inert single-sheet Office workbook.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
public final class AssistantSpreadsheet {

    static final String FORMAT = "sheet";

    /**
     * Media type for generated XLSX downloads.
     */
    public static final String MIME =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final int MAX_ROWS = 64;
    private static final int MAX_COLUMNS = 16;
    private static final int MAX_TEXT_UNITS = 1024;
    private static final int MAX_DOWNLOAD_BYTES = 512 * 1024;
    private static final BigDecimal LOWEST_MAGNITUDE = new BigDecimal("1E-100");
    private static final BigDecimal HIGHEST_MAGNITUDE = new BigDecimal("1E+100");

    private AssistantSpreadsheet() {
    }

    static Sheet input(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != 2
            || !value.path("name").isTextual()) {
            throw invalid("Supply one named spreadsheet grid.");
        }
        String name = value.path("name").textValue();
        validateName(name);
        JsonNode rows = value.path("rows");
        if (!rows.isArray() || rows.isEmpty() || rows.size() > MAX_ROWS || !rows.get(0).isArray()
            || rows.get(0).isEmpty() || rows.get(0).size() > MAX_COLUMNS) {
            throw invalid("Choose between 1 and 64 rows and 1 and 16 columns.");
        }
        int width = rows.get(0).size();
        var cells = new ArrayList<List<JsonNode>>(rows.size());
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() != width) {
                throw invalid("All spreadsheet rows must contain the same number of cells.");
            }
            var values = new ArrayList<JsonNode>(width);
            for (JsonNode cell : row) {
                values.add(normalize(cell));
            }
            cells.add(List.copyOf(values));
        }
        return new Sheet(name, List.copyOf(cells));
    }

    static String encode(ObjectMapper mapper, Sheet sheet) {
        try {
            return mapper.writeValueAsString(sheet);
        } catch (JsonProcessingException failure) {
            throw unavailable();
        }
    }

    static Sheet readStored(ObjectMapper mapper, String content) {
        try {
            return input(AssistantArtifacts.jsonReader(mapper).readTree(content));
        } catch (JsonProcessingException | AssistantApiException failure) {
            throw unavailable();
        }
    }

    static byte[] xlsx(Sheet sheet) {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var target = workbook.createSheet(sheet.name());
            for (int index = 0; index < sheet.rows().size(); index++) {
                Row row = target.createRow(index);
                List<JsonNode> values = sheet.rows().get(index);
                for (int column = 0; column < values.size(); column++) {
                    fill(row, column, values.get(column));
                }
            }
            workbook.write(output);
            if (output.size() > MAX_DOWNLOAD_BYTES) {
                throw unavailable();
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw unavailable();
        }
    }

    private static void fill(Row row, int index, JsonNode value) {
        switch (value.getNodeType()) {
            case STRING -> row.createCell(index, CellType.STRING).setCellValue(value.textValue());
            case NUMBER ->
                row.createCell(index, CellType.NUMERIC).setCellValue(value.doubleValue());
            case BOOLEAN ->
                row.createCell(index, CellType.BOOLEAN).setCellValue(value.booleanValue());
            case NULL -> row.createCell(index, CellType.BLANK);
            default -> throw unavailable();
        }
    }

    private static JsonNode normalize(JsonNode cell) {
        return switch (cell.getNodeType()) {
            case NULL, BOOLEAN -> cell;
            case STRING -> literal(cell);
            case NUMBER -> number(cell);
            default ->
                throw invalid("Spreadsheet cells accept text, numbers, booleans or blank values.");
        };
    }

    private static JsonNode literal(JsonNode cell) {
        String text = cell.textValue();
        if (text.length() > MAX_TEXT_UNITS || !validText(text, true)) {
            throw invalid(
                "Spreadsheet text contains an invalid character or exceeds 1,024 characters.");
        }
        return cell;
    }

    private static JsonNode number(JsonNode cell) {
        if (!Double.isFinite(cell.doubleValue())) {
            throw invalid("Spreadsheet values must be finite numbers.");
        }
        BigDecimal value = cell.decimalValue().stripTrailingZeros();
        BigDecimal magnitude = value.abs();
        if (value.precision() > 15 || value.signum() != 0
            && (magnitude.compareTo(LOWEST_MAGNITUDE) < 0
                || magnitude.compareTo(HIGHEST_MAGNITUDE) > 0)) {
            throw invalid(
                "Use at most 15 significant digits and a magnitude between 1e-100 and 1e100, or zero.");
        }
        return DecimalNode.valueOf(value);
    }

    private static void validateName(String name) {
        if (name.isBlank() || name.length() > 31 || !validText(name, false)) {
            throw invalid("Choose a worksheet name of 1 to 31 valid characters.");
        }
        try {
            WorkbookUtil.validateSheetName(name);
        } catch (IllegalArgumentException failure) {
            throw invalid("Choose a worksheet name of 1 to 31 valid characters.");
        }
    }

    private static boolean validText(String text, boolean multiline) {
        return StandardCharsets.UTF_8.newEncoder().canEncode(text)
            && text.codePoints().noneMatch(point -> point == 0xfffe || point == 0xffff
                || Character.isISOControl(point)
                    && !(multiline && (point == '\t' || point == '\r' || point == '\n')));
    }

    private static AssistantApiException invalid(String detail) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", detail);
    }

    private static AssistantApiException unavailable() {
        return new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE, "CAPABILITY_UNAVAILABLE",
            "The spreadsheet is temporarily unavailable.");
    }

    /**
     * One rectangular grid using exact literal cell types.
     *
     * @param name visible worksheet name
     * @param rows immutable row data containing text, exact decimals, booleans or nulls
     */
    public record Sheet(String name, List<List<JsonNode>> rows) {

        @Override
        public String toString() {
            return "Sheet[protected]";
        }
    }
}
