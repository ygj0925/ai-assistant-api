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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Checks canonical audio bytes and bounded rejection of invalid PCM headers.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AttachmentWaveTest {

    @Test
    void preservesFramesAndRemovesOddSizedMetadata() {
        byte[] samples = {1, 2, 3, 4, 5, 6};
        byte[] original = fixture(samples, "private audio tag");
        byte[] expected = fixture(samples, null);
        assertThat(AttachmentWave.inspect(original))
            .isEqualTo(new AttachmentWave.Format(8_000, 1, 1));
        assertThat(AttachmentWave.canonicalize(original)).isEqualTo(expected);
    }

    @Test
    void requiresExactBytesAndOnePcmFormatWithAlignedFrames() {
        byte[] damaged = fixture(new byte[4], null);
        ByteBuffer.wrap(damaged).order(ByteOrder.LITTLE_ENDIAN).putInt(40, -1);
        assertThatIllegalArgumentException().isThrownBy(() -> AttachmentWave.inspect(damaged));
        byte[] compressed = fixture(new byte[2], null);
        ByteBuffer.wrap(compressed).order(ByteOrder.LITTLE_ENDIAN).putShort(20, (short) 2);
        assertThatIllegalArgumentException().isThrownBy(() -> AttachmentWave.inspect(compressed));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> AttachmentWave.inspect(fixture(new byte[3], null)));
        byte[] wrongSize = fixture(new byte[2], null);
        wrongSize[4] = 1;
        assertThatIllegalArgumentException().isThrownBy(() -> AttachmentWave.inspect(wrongSize));
    }

    @Test
    void refusesAValidHeaderWithTooMuchTimeBeforeCopyingSamples() {
        byte[] below = fixture(new byte[8_000 * 2 * 30], null);
        assertThat(AttachmentWave.inspect(below).durationMillis()).isEqualTo(30_000);
        byte[] above = fixture(new byte[8_000 * 2 * 30 + 2], null);
        assertThatIllegalArgumentException().isThrownBy(() -> AttachmentWave.canonicalize(above));
    }

    private static byte[] fixture(byte[] samples, String text) {
        byte[] metadata = text == null ? new byte[0] : text.getBytes(StandardCharsets.US_ASCII);
        int padding = samples.length & 1;
        int annotation = text == null ? 0 : 8 + metadata.length + (metadata.length & 1);
        ByteBuffer output = ByteBuffer.allocate(44 + annotation + samples.length + padding)
            .order(ByteOrder.LITTLE_ENDIAN);
        write(output, "RIFF").putInt(output.capacity() - 8);
        write(output, "WAVE");
        write(output, "fmt ").putInt(16).putShort((short) 1).putShort((short) 1);
        output.putInt(8_000).putInt(16_000).putShort((short) 2).putShort((short) 16);
        if (text != null) {
            write(output, "JUNK").putInt(metadata.length).put(metadata);
            if ((metadata.length & 1) == 1) {
                output.put((byte) 0);
            }
        }
        write(output, "data").putInt(samples.length).put(samples);
        return output.array();
    }

    private static ByteBuffer write(ByteBuffer output, String code) {
        return output.put(code.getBytes(StandardCharsets.US_ASCII));
    }
}
