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

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Reads the uncompressed, one-page PDF text profile accepted by the local attachment preview.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
final class AttachmentPdf {

    private static final String HEADER = "%PDF-1.4\n";
    private static final Pattern GENERAL_HEADER =
        Pattern.compile("%PDF-(?:1\\.[0-7]|2\\.0)[\\r\\n]");
    private static final String PAGE = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]"
        + " /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>";
    private static final String TEXT_SETUP = "BT\n/F1 12 Tf\n72 720 Td\n16 TL\n";
    static final int MAX_BYTES = 32 * 1024;
    static final int MAX_TEXT_BYTES = 4 * 1024;
    private static final int MAX_LINES = 32;

    private AttachmentPdf() {
    }

    static void requireHeader(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER.length() || bytes.length > MAX_BYTES) {
            throw invalid();
        }
        for (int index = 0; index < HEADER.length(); index++) {
            if (bytes[index] != HEADER.charAt(index)) {
                throw invalid();
            }
        }
    }

    static void requireGeneralHeader(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER.length() || bytes.length > MAX_BYTES
            || !GENERAL_HEADER
                .matcher(new String(bytes, 0, HEADER.length(), StandardCharsets.US_ASCII))
                .matches()) {
            throw invalid();
        }
    }

    static byte[] extract(byte[] bytes) {
        requireHeader(bytes);
        Cursor cursor = new Cursor(new String(bytes, StandardCharsets.ISO_8859_1));
        cursor.expect(HEADER);
        int catalog = cursor.object("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        int pages = cursor.object("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        int page = cursor.object("3 0 obj\n" + PAGE + "\nendobj\n");
        int font = cursor
            .object("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n");
        int content = cursor.object("5 0 obj\n<< /Length ");
        int declaredLength = cursor.decimal();
        cursor.expect(" >>\nstream\n");
        int streamStart = cursor.position;
        cursor.expect(TEXT_SETUP);
        String text = cursor.readText();
        cursor.expect("ET");
        if (cursor.position - streamStart != declaredLength) {
            throw invalid();
        }
        cursor.expect("\nendstream\nendobj\n");
        int xref = cursor.position;
        cursor.expect("xref\n0 6\n0000000000 65535 f \n");
        for (int offset : new int[] {catalog, pages, page, font, content}) {
            String digits = Integer.toString(offset);
            cursor.expect("0".repeat(10 - digits.length()) + digits + " 00000 n \n");
        }
        cursor.expect("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        if (cursor.position != cursor.source.length()) {
            throw invalid();
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("The file is outside the basic PDF text profile.");
    }

    private static final class Cursor {

        private final String source;
        private int position;

        private Cursor(String source) {
            this.source = source;
        }

        private int object(String body) {
            int offset = position;
            expect(body);
            return offset;
        }

        private void expect(String literal) {
            if (!take(literal)) {
                throw invalid();
            }
        }

        private boolean take(String literal) {
            if (!source.startsWith(literal, position)) {
                return false;
            }
            position += literal.length();
            return true;
        }

        private int decimal() {
            int start = position;
            int value = 0;
            while (position < source.length() && position - start < 5) {
                char digit = source.charAt(position);
                if (digit < '0' || digit > '9') {
                    break;
                }
                value = 10 * value + digit - '0';
                position++;
            }
            if (start == position || (position - start > 1 && source.charAt(start) == '0')
                || value > MAX_BYTES) {
                throw invalid();
            }
            return value;
        }

        private String readText() {
            var text = new StringBuilder();
            for (int line = 0; line < MAX_LINES; line++) {
                if (line > 0) {
                    expect("T*\n");
                }
                readLine(text);
                if (source.startsWith("ET", position)) {
                    String result = text.toString();
                    if (result.isBlank()) {
                        throw invalid();
                    }
                    return result;
                }
            }
            throw invalid();
        }

        private void readLine(StringBuilder text) {
            expect("(");
            while (position < source.length()) {
                char next = source.charAt(position++);
                if (next == ')') {
                    expect(" Tj\n");
                    append(text, '\n');
                    return;
                }
                if (next == '\\') {
                    next = literalEscape();
                } else if (next < ' ' || next > '~' || next == '(') {
                    throw invalid();
                }
                append(text, next);
            }
            throw invalid();
        }

        private char literalEscape() {
            if (position == source.length()) {
                throw invalid();
            }
            char escaped = source.charAt(position++);
            if (escaped != '\\' && escaped != '(' && escaped != ')') {
                throw invalid();
            }
            return escaped;
        }

        private void append(StringBuilder text, char value) {
            if (text.length() == MAX_TEXT_BYTES) {
                throw invalid();
            }
            text.append(value);
        }
    }
}
