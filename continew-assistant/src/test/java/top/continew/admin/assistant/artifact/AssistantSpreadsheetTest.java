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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import top.continew.admin.assistant.account.AssistantApiException;

import java.io.ByteArrayInputStream;
import java.util.zip.ZipInputStream;

/**
 * Verifies spreadsheet bounds, explicit cell types and actual OOXML round trips.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantSpreadsheetTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void writesStringsWithoutFormulasAndRetainsTypedValuesInAnActualWorkbook() throws Exception {
        var sheet = sheet(
            """
                {"name":"合成数据","rows":[["=1+2",42.125,true,null,"中文 🦀\\r\\n"],["",0,false,null,"@SUM(1,2)"]]}
                """);
        byte[] bytes = AssistantSpreadsheet.xlsx(sheet);
        assertThat(bytes).startsWith((byte) 'P', (byte) 'K');
        try (var reopened = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(reopened.getNumberOfSheets()).isOne();
            var worksheet = reopened.getSheetAt(0);
            assertThat(worksheet.getSheetName()).isEqualTo("合成数据");
            var row = worksheet.getRow(0);
            assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("=1+2");
            assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(42.125);
            assertThat(row.getCell(2).getBooleanCellValue()).isTrue();
            assertThat(row.getCell(3).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(row.getCell(4).getStringCellValue()).isEqualTo("中文 🦀\r\n");
            var other = worksheet.getRow(1);
            assertThat(other.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(other.getCell(0).getStringCellValue()).isEmpty();
            assertThat(other.getCell(2).getBooleanCellValue()).isFalse();
            assertThat(other.getCell(4).getStringCellValue()).isEqualTo("@SUM(1,2)");
        }
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                assertThat(entry.getName()).doesNotContain("externalLinks", "vbaProject");
            }
        }
    }

    @Test
    void keepsExactDecimalsBeforeRejectingValuesExcelCannotRepresent() throws Exception {
        var valid = sheet("""
            {"name":"Numeric","rows":[[123456789012345,1.2300,1e-100,-1e100,0.00]]}
            """);
        String stored = AssistantSpreadsheet.encode(mapper, valid);
        assertThat(AssistantSpreadsheet.readStored(mapper, stored)).isEqualTo(valid);
        assertThat(valid.rows().get(0).get(1).decimalValue().toPlainString()).isEqualTo("1.23");
        expect(400, "{\"name\":\"Precision\",\"rows\":[[0.100000000000000000001]]}");
        expect(400, "{\"name\":\"Precision\",\"rows\":[[9007199254740993]]}");
        expect(400, "{\"name\":\"Magnitude\",\"rows\":[[1e-101]]}");
        expect(400, "{\"name\":\"Magnitude\",\"rows\":[[1e101]]}");
    }

    @Test
    void validatesRectangularShapeAndAllowsACompletelyBlankGrid() throws Exception {
        var blank = sheet("{\"name\":\"Blank\",\"rows\":[[null,null],[null,null]]}");
        assertThat(blank.rows()).hasSize(2);
        assertThat(blank.rows().get(0)).allMatch(node -> node.isNull());
        expect(400, "{\"name\":\"Ragged\",\"rows\":[[true,false],[null]]}");
        expect(400, "{\"name\":\"Invalid/Name\",\"rows\":[[null]]}");
        expect(400, "{\"name\":\"Empty\",\"rows\":[[]]}");
        expect(400, "{\"name\":\"Objects\",\"rows\":[[{\"type\":\"number\"}]]}");
        expect(400, "{\"name\":\"Invalid text\",\"rows\":[[\"\\u0000\"]]}");
        expect(400, "{\"name\":\"Too long\",\"rows\":[[\"" + "a".repeat(1025) + "\"]]}");
    }

    private AssistantSpreadsheet.Sheet sheet(String json) throws JsonProcessingException {
        return AssistantSpreadsheet.input(AssistantArtifacts.jsonReader(mapper).readTree(json));
    }

    private void expect(int code, String json) {
        assertThatThrownBy(() -> sheet(json)).isInstanceOfSatisfying(AssistantApiException.class,
            failure -> assertThat(failure.status().value()).isEqualTo(code));
    }
}
