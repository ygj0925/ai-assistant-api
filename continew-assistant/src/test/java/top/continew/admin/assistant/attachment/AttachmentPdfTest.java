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

package top.continew.admin.assistant.attachment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.stream.IntStream;

class AttachmentPdfTest {

    private static final String SETUP = "BT\n/F1 12 Tf\n72 720 Td\n16 TL\n";

    @Test
    void extractsOnlyExactTextWithEscapedLiteralsAndLineBreaks() {
        byte[] document = basic(SETUP
            + "(Quarter \\(four\\)) Tj\nT*\n() Tj\nT*\n(Path C:\\\\docs /OpenAction) Tj\nET");
        assertArrayEquals(bytes("Quarter (four)\n\nPath C:\\docs /OpenAction\n"),
            AttachmentPdf.extract(document));
    }

    @Test
    void rejectsAlteredLengthCrossReferenceAndTrailingBytes() {
        byte[] clean = basic(SETUP + "(One) Tj\nET");
        assertRejected(change(clean, "/Length 40", "/Length 39"));
        assertRejected(change(clean, "0000000009 00000 n ", "0000000010 00000 n "));
        String raw = new String(clean, StandardCharsets.US_ASCII);
        assertRejected(bytes(raw.replaceFirst("startxref\\n[0-9]+", "startxref\n0")));
        assertRejected(bytes(raw + "%%EOF\n"));
    }

    @Test
    void rejectsActionsFiltersUnsupportedOperatorsAndUnapprovedEscapes() {
        byte[] clean = basic(SETUP + "(One) Tj\nET");
        assertRejected(change(clean, "/Pages 2 0 R", "/Pages 2 0 R /OpenAction 8 0 R"));
        assertRejected(change(clean, "/Length ", "/Filter /FlateDecode /Length "));
        assertRejected(basic(SETUP + "(One) Tj\n/Im0 Do\nET"));
        assertRejected(basic(SETUP + "(One\\050) Tj\nET"));
        assertRejected(basic(SETUP + "(One)\r Tj\nET"));
        assertRejected(basic(SETUP + "(" + (char) 127 + ") Tj\nET"));
        assertRejected(basic(SETUP + "((One)) Tj\nET"));
        assertRejected(basic(SETUP + "(One) Tj\nET\nBT"));
        assertRejected(basic(SETUP + "( ) Tj\nET"));
    }

    @Test
    void enforcesOutputAndLineCapsExactly() {
        String line = "A".repeat(4095);
        assertArrayEquals(bytes(line + "\n"),
            AttachmentPdf.extract(basic(SETUP + "(" + line + ") Tj\nET")));
        assertRejected(basic(SETUP + "(" + line + "X) Tj\nET"));
        String[] lines = IntStream.range(0, 32).mapToObj(value -> "(x) Tj\n")
            .toArray(String[]::new);
        String body = String.join("T*\n", lines);
        assertArrayEquals(bytes("x\n".repeat(32)),
            AttachmentPdf.extract(basic(SETUP + body + "ET")));
        assertRejected(basic(SETUP + body + "T*\n(x) Tj\nET"));
    }

    @Test
    void rejectsHeaderMismatchAndClearlyOversizedBodiesAtIntake() {
        assertThrows(IllegalArgumentException.class, () -> AttachmentPdf.requireHeader(null));
        assertThrows(IllegalArgumentException.class,
            () -> AttachmentPdf.requireHeader(bytes("%PDF-1.7\n")));
        var oversized = bytes("%PDF-1.4\n" + " ".repeat(AttachmentPdf.MAX_BYTES));
        assertThrows(IllegalArgumentException.class, () -> AttachmentPdf.requireHeader(oversized));
        AttachmentPdf.requireHeader(bytes("%PDF-1.4\nparse happens after the scanner"));
    }

    private static void assertRejected(byte[] bytes) {
        assertThrows(IllegalArgumentException.class, () -> AttachmentPdf.extract(bytes));
    }

    private static byte[] basic(String stream) {
        String[] objects = {
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            """
                << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\
                """,
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Length " + stream.length() + " >>\nstream\n" + stream + "\nendstream",
        };
        var document = new StringBuilder("%PDF-1.4\n");
        var index = new StringBuilder("xref\n0 6\n0000000000 65535 f \n");
        for (int offset = 0; offset < objects.length; offset++) {
            index.append(String.format(Locale.ROOT, "%010d 00000 n \n", document.length()));
            document.append(offset + 1).append(" 0 obj\n").append(objects[offset])
                .append("\nendobj\n");
        }
        int crossReference = document.length();
        return bytes(
            document.append(index).append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n")
                .append(crossReference).append("\n%%EOF\n").toString());
    }

    private static byte[] change(byte[] input, String from, String to) {
        String original = new String(input, StandardCharsets.US_ASCII);
        if (!original.contains(from)) {
            throw new AssertionError("The fixture does not contain the expected shape.");
        }
        return bytes(original.replace(from, to));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }
}
