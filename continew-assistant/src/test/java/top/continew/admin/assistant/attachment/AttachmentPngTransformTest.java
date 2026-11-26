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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

class AttachmentPngTransformTest {

    private static final int[] ORIGINAL = {
        0xff102030, 0x80323456, 0xff123456, 0xfff0e0d0,
        0xffccbbaa, 0xff010203, 0x11020304, 0xff123123,
        0xff321321, 0xbf304050, 0xff020304, 0xffffffff,
    };

    @Test
    void returnsExactCroppedPixelsInEachClockwiseOrientation() throws IOException {
        byte[] source = rgbFixture();
        assertPixels(source, 0, 2, 3, 1, 2, 5, 6, 9, 10);
        assertPixels(source, 90, 3, 2, 9, 5, 1, 10, 6, 2);
        assertPixels(source, 180, 2, 3, 10, 9, 6, 5, 2, 1);
        assertPixels(source, 270, 3, 2, 2, 6, 10, 1, 5, 9);
    }

    @Test
    void addsOnlyTheFixedLocalMarkerAndRefusesItOnOriginalInput() throws IOException {
        byte[] source = rgbFixture();
        var shape = new AttachmentPngTransform.Edit(1, 0, 2, 3, 0);
        byte[] output = AttachmentPngTransform.render(source, shape);
        assertArrayEquals(output, AttachmentPngTransform.render(source, shape));
        assertArrayEquals(output, AttachmentPngTransform.canonicalizeRendered(output));
        assertTrue(new String(output, StandardCharsets.ISO_8859_1)
            .contains("AssistantProvenance\0local_image_edit_v1"));
        byte[] untrustedUpload = AttachmentPng.sanitize(output).bytes();
        assertThrows(IllegalArgumentException.class,
            () -> AttachmentPngTransform.canonicalizeRendered(untrustedUpload));
        assertThrows(IllegalArgumentException.class, () -> AttachmentPngTransform.render(output,
            new AttachmentPngTransform.Edit(0, 0, 1, 2, 90)));
    }

    @Test
    void rejectsInvalidNoOpOutsideAndOverflowingGeometry() throws IOException {
        byte[] source = rgbFixture();
        assertThrows(IllegalArgumentException.class,
            () -> new AttachmentPngTransform.Edit(-1, 0, 1, 1, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new AttachmentPngTransform.Edit(0, 0, 0, 1, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new AttachmentPngTransform.Edit(0, 0, 481, 1, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new AttachmentPngTransform.Edit(0, 0, 1, 1, 45));
        assertThrows(IllegalArgumentException.class, () -> AttachmentPngTransform.render(source,
            new AttachmentPngTransform.Edit(0, 0, 4, 3, 0)));
        assertThrows(IllegalArgumentException.class, () -> AttachmentPngTransform.render(source,
            new AttachmentPngTransform.Edit(Integer.MAX_VALUE, 0, 1, 1, 90)));
        assertThrows(IllegalArgumentException.class, () -> AttachmentPngTransform.render(source,
            new AttachmentPngTransform.Edit(1, 1, 2, 3, 0)));
    }

    @Test
    void checksOriginalDimensionsAndCompressionBeforeDecode() throws IOException {
        var edit = new AttachmentPngTransform.Edit(0, 0, 1, 1, 90);
        var oversized = new BufferedImage(481, 1, BufferedImage.TYPE_INT_ARGB);
        assertThrows(IllegalArgumentException.class,
            () -> AttachmentPngTransform.render(encodeFixture(oversized), edit));
        byte[] corrupt = rgbFixture();
        corrupt[corrupt.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class,
            () -> AttachmentPngTransform.render(corrupt, edit));
    }

    @Test
    void longestAllowedOpaqueAndTranslucentPixelDataStaysWithinAttachmentLimit()
        throws IOException {
        int dimension = AttachmentPngTransform.MAX_SIDE;
        var large = new BufferedImage(dimension, dimension, BufferedImage.TYPE_INT_ARGB);
        var random = new Random(352L);
        for (int y = 0; y < dimension; y++) {
            for (int x = 0; x < dimension; x++) {
                large.setRGB(x, y, random.nextInt());
            }
        }
        byte[] output = AttachmentPngTransform.render(encodeFixture(large),
            new AttachmentPngTransform.Edit(0, 0, dimension, dimension, 90));
        assertTrue(output.length <= AttachmentPng.MAX_BYTES);
        var rendered = decodeFixture(output);
        assertEquals(large.getRGB(48, 87), rendered.getRGB(dimension - 1 - 87, 48));
        assertArrayEquals(output, AttachmentPngTransform.canonicalizeRendered(output));
    }

    private static void assertPixels(byte[] source, int rotation, int width, int height,
        int... order) throws IOException {
        byte[] bytes = AttachmentPngTransform.render(source,
            new AttachmentPngTransform.Edit(1, 0, 2, 3, rotation));
        var output = decodeFixture(bytes);
        assertEquals(width, output.getWidth());
        assertEquals(height, output.getHeight());
        var expected = new int[order.length];
        for (int index = 0; index < order.length; index++) {
            expected[index] = ORIGINAL[order[index]];
        }
        assertArrayEquals(expected, output.getRGB(0, 0, width, height, null, 0, width));
    }

    private static byte[] rgbFixture() throws IOException {
        var image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 4, 3, ORIGINAL, 0, 4);
        return encodeFixture(image);
    }

    private static BufferedImage decodeFixture(byte[] bytes) throws IOException {
        var reader = ImageIO.getImageReadersByFormatName("png").next();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(input, true, true);
            return reader.read(0);
        } finally {
            reader.dispose();
        }
    }

    private static byte[] encodeFixture(BufferedImage image) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            assertTrue(ImageIO.write(image, "png", output));
        }
        return AttachmentPng.sanitize(bytes.toByteArray()).bytes();
    }
}
