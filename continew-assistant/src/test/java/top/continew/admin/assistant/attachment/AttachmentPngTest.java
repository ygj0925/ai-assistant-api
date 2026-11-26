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

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/**
 * Exercises accepted pixels, metadata stripping, and maliciously malformed structure.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AttachmentPngTest {

    @Test
    void preservesValidPixelsAndRemovesAllAncillaryData() throws IOException {
        byte[] original = fixture();
        byte[] withMetadata = withChunk(original,
            chunk("tEXt", "Creator\0Synthetic material".getBytes(StandardCharsets.US_ASCII)));
        var result = AttachmentPng.sanitize(withMetadata);
        assertThat(result.dimensions().width()).isEqualTo(2);
        assertThat(result.dimensions().height()).isEqualTo(3);
        assertThat(result.dimensions().channels()).isEqualTo(4);
        assertThat(result.bytes()).isEqualTo(original);
    }

    @Test
    void rejectsWrongSignaturesExcessiveDimensionsAndTrailingBytes() throws IOException {
        byte[] fixture = fixture();
        assertThatIllegalArgumentException().isThrownBy(() -> AttachmentPng.inspect(new byte[45]));
        byte[] dimensions = fixture.clone();
        ByteBuffer.wrap(dimensions).putInt(16, 2049);
        assertThatIllegalArgumentException().isThrownBy(() -> AttachmentPng.inspect(dimensions));
        assertThatIllegalArgumentException().isThrownBy(
            () -> AttachmentPng.sanitize(Arrays.copyOf(fixture, fixture.length + 1)));
    }

    @Test
    void rejectsCorruptAncillaryCrcAndAnimation() throws IOException {
        byte[] fixture = fixture();
        byte[] invalid = chunk("tEXt", new byte[] {1});
        invalid[invalid.length - 1] ^= 1;
        assertThatIllegalArgumentException()
            .isThrownBy(() -> AttachmentPng.sanitize(withChunk(fixture, invalid)));
        assertThatIllegalArgumentException().isThrownBy(
            () -> AttachmentPng.sanitize(withChunk(fixture, chunk("acTL", new byte[8]))));
    }

    private static byte[] fixture() throws IOException {
        var image = new BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 2, 0xff3280cf);
        var bytes = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", bytes)).isTrue();
        return bytes.toByteArray();
    }

    private static byte[] withChunk(byte[] png, byte[] chunk) {
        var bytes = new ByteArrayOutputStream();
        bytes.write(png, 0, 33);
        bytes.writeBytes(chunk);
        bytes.write(png, 33, png.length - 33);
        return bytes.toByteArray();
    }

    private static byte[] chunk(String type, byte[] payload) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        var crc = new CRC32();
        crc.update(name);
        crc.update(payload);
        return ByteBuffer.allocate(12 + payload.length).putInt(payload.length).put(name)
            .put(payload).putInt((int) crc.getValue()).array();
    }
}
