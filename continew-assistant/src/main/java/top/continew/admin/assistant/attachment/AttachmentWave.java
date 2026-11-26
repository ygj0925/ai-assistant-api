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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Validates and strips metadata from bounded 16-bit PCM WAVE project audio.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
final class AttachmentWave {

    private static final int HEADER_BYTES = 44;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_CHUNKS = 64;
    private static final int MAX_SECONDS = 30;
    private static final int RIFF = 0x52494646;
    private static final int WAVE = 0x57415645;
    private static final int FMT = 0x666D7420;
    private static final int DATA = 0x64617461;

    private AttachmentWave() {
    }

    static Format inspect(byte[] input) {
        return locate(input).format();
    }

    static byte[] canonicalize(byte[] input) {
        Layout layout = locate(input);
        Format format = layout.format();
        ByteBuffer result = ByteBuffer.allocate(HEADER_BYTES + layout.dataLength());
        tag(result, RIFF);
        result.putInt(36 + layout.dataLength());
        tag(result, WAVE);
        tag(result, FMT);
        result.putInt(16).putShort((short) 1).putShort((short) format.channels());
        int alignment = format.channels() * 2;
        result.putInt(format.sampleRate()).putInt(format.sampleRate() * alignment);
        result.putShort((short) alignment).putShort((short) 16);
        tag(result, DATA);
        result.putInt(layout.dataLength()).put(input, layout.dataOffset(), layout.dataLength());
        return result.array();
    }

    private static Layout locate(byte[] input) {
        if (input.length < HEADER_BYTES + 2 || input.length > MAX_BYTES) {
            throw invalid();
        }
        ByteBuffer body = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
        if (Integer.reverseBytes(body.getInt(0)) != RIFF
            || Integer.toUnsignedLong(body.getInt(4)) != input.length - 8L
            || Integer.reverseBytes(body.getInt(8)) != WAVE) {
            throw invalid();
        }
        int sampleRate = 0;
        int channels = 0;
        int dataOffset = 0;
        int dataLength = 0;
        int chunks = 0;
        for (int offset = 12; offset < input.length;) {
            if (input.length - offset < 8 || ++chunks > MAX_CHUNKS) {
                throw invalid();
            }
            int kind = Integer.reverseBytes(body.getInt(offset));
            int size = body.getInt(offset + 4);
            int from = offset + 8;
            if (size < 0 || size > input.length - from || (size & 1) > input.length - from - size) {
                throw invalid();
            }
            if (kind == FMT) {
                if (sampleRate != 0 || (size != 16 && size != 18)
                    || body.getShort(from) != 1 || (size == 18 && body.getShort(from + 16) != 0)) {
                    throw invalid();
                }
                channels = body.getShort(from + 2);
                sampleRate = body.getInt(from + 4);
                if (channels < 1 || channels > 2 || sampleRate < 8_000 || sampleRate > 48_000
                    || body.getInt(from + 8) != sampleRate * channels * 2
                    || body.getShort(from + 12) != channels * 2 || body.getShort(from + 14) != 16) {
                    throw invalid();
                }
            } else if (kind == DATA) {
                if (dataOffset != 0 || size == 0) {
                    throw invalid();
                }
                dataOffset = from;
                dataLength = size;
            }
            offset = from + size + (size & 1);
        }
        if (sampleRate == 0 || dataOffset == 0 || dataLength % (channels * 2) != 0) {
            throw invalid();
        }
        int frames = dataLength / (channels * 2);
        if (frames > sampleRate * MAX_SECONDS) {
            throw invalid();
        }
        int milliseconds = (int) ((frames * 1000L + sampleRate - 1) / sampleRate);
        return new Layout(new Format(sampleRate, channels, milliseconds), dataOffset, dataLength);
    }

    private static void tag(ByteBuffer output, int code) {
        output.order(ByteOrder.BIG_ENDIAN).putInt(code).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
            "Use 16-bit mono or stereo PCM WAVE within 1 MiB and 30 seconds.");
    }

    record Format(int sampleRate, int channels, int durationMillis) {
    }

    private record Layout(Format format, int dataOffset, int dataLength) {
    }
}
