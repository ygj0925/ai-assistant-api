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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Validates bounded RGB/RGBA PNG scanlines and discards all ancillary chunks before download.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
final class AttachmentPng {

    static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_DIMENSION = 2048;
    private static final int MAX_PIXELS = 2_000_000;
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private static final String HEADER = "IHDR";
    private static final String PIXELS = "IDAT";
    private static final String END = "IEND";

    private AttachmentPng() {
    }

    static Dimensions inspect(byte[] bytes) {
        if (bytes.length < 45 || bytes.length > MAX_BYTES
            || !Arrays.equals(SIGNATURE, 0, SIGNATURE.length, bytes, 0, SIGNATURE.length)) {
            throw invalid();
        }
        ByteBuffer input = ByteBuffer.wrap(bytes);
        input.position(SIGNATURE.length);
        if (input.getInt() != 13 || !HEADER.equals(chunkName(input))) {
            throw invalid();
        }
        int width = input.getInt();
        int height = input.getInt();
        int depth = input.get() & 0xff;
        int color = input.get() & 0xff;
        if (width < 1 || width > MAX_DIMENSION || height < 1 || height > MAX_DIMENSION
            || width * height > MAX_PIXELS || depth != 8 || (color != 2 && color != 6)
            || input.get() != 0 || input.get() != 0 || input.get() != 0) {
            throw invalid();
        }
        return new Dimensions(width, height, color == 2 ? 3 : 4);
    }

    static Approved sanitize(byte[] source) {
        Dimensions dimensions = inspect(source);
        ByteBuffer input = ByteBuffer.wrap(source);
        input.position(SIGNATURE.length);
        var compressed = new ByteArrayOutputStream();
        var output = new ByteArrayOutputStream();
        output.writeBytes(SIGNATURE);
        boolean readPixels = false;
        boolean endedPixels = false;
        while (input.remaining() >= 12) {
            int start = input.position();
            int length = input.getInt();
            String kind = chunkName(input);
            if (length < 0 || length > input.remaining() - Integer.BYTES || !validChunk(kind)) {
                throw invalid();
            }
            int dataStart = input.position();
            input.position(dataStart + length);
            verifyChecksum(source, dataStart, length, input.getInt());
            switch (kind) {
                case HEADER -> {
                    if (start != SIGNATURE.length) {
                        throw invalid();
                    }
                    output.write(source, start, input.position() - start);
                }
                case PIXELS -> {
                    if (endedPixels) {
                        throw invalid();
                    }
                    readPixels = true;
                    compressed.write(source, dataStart, length);
                    output.write(source, start, input.position() - start);
                }
                case END -> {
                    if (length != 0 || input.hasRemaining() || compressed.size() == 0) {
                        throw invalid();
                    }
                    validateScanlines(compressed.toByteArray(), dimensions);
                    output.write(source, start, input.position() - start);
                    return new Approved(dimensions, output.toByteArray());
                }
                default -> {
                    if (kind.charAt(0) < 'a' || "acTL".equals(kind) || "fcTL".equals(kind)
                        || "fdAT".equals(kind)) {
                        throw invalid();
                    }
                    endedPixels = readPixels;
                }
            }
        }
        throw invalid();
    }

    private static String chunkName(ByteBuffer input) {
        byte[] kind = new byte[4];
        input.get(kind);
        return new String(kind, StandardCharsets.US_ASCII);
    }

    private static boolean validChunk(String type) {
        return type.charAt(2) >= 'A' && type.charAt(2) <= 'Z'
            && type.chars().allMatch(character -> (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z'));
    }

    private static void verifyChecksum(byte[] source, int offset, int length, int expected) {
        var checksum = new CRC32();
        checksum.update(source, offset - 4, length + 4);
        if (checksum.getValue() != Integer.toUnsignedLong(expected)) {
            throw invalid();
        }
    }

    private static void validateScanlines(byte[] compressed, Dimensions dimensions) {
        var inflater = new Inflater();
        inflater.setInput(compressed);
        byte[] row = new byte[1 + dimensions.width() * dimensions.channels()];
        try {
            for (int index = 0; index < dimensions.height(); index++) {
                int offset = 0;
                while (offset < row.length) {
                    int count = inflater.inflate(row, offset, row.length - offset);
                    if (count == 0) {
                        throw invalid();
                    }
                    offset += count;
                }
                if ((row[0] & 0xff) > 4) {
                    throw invalid();
                }
            }
            if (inflater.inflate(row, 0, 1) != 0 || !inflater.finished()
                || inflater.getRemaining() != 0) {
                throw invalid();
            }
        } catch (DataFormatException failure) {
            throw invalid();
        } finally {
            inflater.end();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Expected a bounded non-interlaced RGB or RGBA PNG.");
    }

    record Dimensions(int width, int height, int channels) {
    }

    record Approved(Dimensions dimensions, byte[] bytes) {
    }
}
