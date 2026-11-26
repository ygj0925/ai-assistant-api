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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Renders a bounded crop and quarter-turn of an already approved PNG.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
final class AttachmentPngTransform {

    static final int MAX_SIDE = 480;
    private static final String ALGORITHM = "local_image_edit_v1";
    private static final int END_CHUNK_LENGTH = 12;
    private static final byte[] MARKER = provenanceChunk();

    private AttachmentPngTransform() {
    }

    static byte[] render(byte[] approved, Edit edit) throws IOException {
        var dimensions = AttachmentPng.inspect(approved);
        edit.requireBounds(dimensions);
        if (!Arrays.equals(approved, AttachmentPng.sanitize(approved).bytes())) {
            throw invalid();
        }
        var source = decode(approved, dimensions);
        var output =
            new BufferedImage(edit.outputWidth(), edit.outputHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < edit.height(); y++) {
            for (int x = 0; x < edit.width(); x++) {
                output.setRGB(edit.outputX(x, y), edit.outputY(x, y),
                    source.getRGB(edit.x() + x, edit.y() + y));
            }
        }
        byte[] pixels = AttachmentPng.sanitize(encode(output)).bytes();
        return mark(pixels);
    }

    static byte[] canonicalizeRendered(byte[] bytes) {
        var sanitized = AttachmentPng.sanitize(bytes).bytes();
        if (!Arrays.equals(bytes, mark(sanitized))) {
            throw invalid();
        }
        return bytes;
    }

    private static BufferedImage decode(byte[] approved, AttachmentPng.Dimensions dimensions)
        throws IOException {
        ImageReader reader = pngReader();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(approved))) {
            reader.setInput(input, true, true);
            if (reader.getWidth(0) != dimensions.width()
                || reader.getHeight(0) != dimensions.height()) {
                throw invalid();
            }
            return reader.read(0);
        } finally {
            reader.dispose();
        }
    }

    private static ImageReader pngReader() throws IOException {
        var readers = ImageIO.getImageReadersByFormatName("png");
        while (readers.hasNext()) {
            ImageReader reader = readers.next();
            if (reader.getClass().getModule() == ImageIO.class.getModule()) {
                return reader;
            }
            reader.dispose();
        }
        throw new IOException("The JDK PNG reader is unavailable.");
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        ImageWriter writer = pngWriter();
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(output);
                writer.write(null, new IIOImage(image, null, null), writer.getDefaultWriteParam());
            }
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static ImageWriter pngWriter() throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("png");
        while (writers.hasNext()) {
            ImageWriter writer = writers.next();
            if (writer.getClass().getModule() == ImageIO.class.getModule()) {
                return writer;
            }
            writer.dispose();
        }
        throw new IOException("The JDK PNG writer is unavailable.");
    }

    private static byte[] mark(byte[] canonical) {
        int offset = canonical.length - END_CHUNK_LENGTH;
        if (canonical.length > AttachmentPng.MAX_BYTES - MARKER.length) {
            throw invalid();
        }
        var marked = ByteBuffer.allocate(canonical.length + MARKER.length);
        return marked.put(canonical, 0, offset).put(MARKER)
            .put(canonical, offset, END_CHUNK_LENGTH).array();
    }

    private static byte[] provenanceChunk() {
        byte[] kind = "tEXt".getBytes(StandardCharsets.US_ASCII);
        byte[] content = ("AssistantProvenance\0" + ALGORITHM).getBytes(StandardCharsets.US_ASCII);
        var crc = new CRC32();
        crc.update(kind);
        crc.update(content);
        return ByteBuffer.allocate(12 + content.length).putInt(content.length).put(kind)
            .put(content).putInt((int) crc.getValue()).array();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
            "Expected a size-bounded crop and a clockwise quarter-turn of an original PNG.");
    }

    record Edit(int x, int y, int width, int height, int rotation) {

        Edit {
            if (x < 0 || y < 0 || width < 1 || width > MAX_SIDE || height < 1 || height > MAX_SIDE
                || (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)) {
                throw invalid();
            }
        }

        void requireBounds(AttachmentPng.Dimensions image) {
            if (image.width() > MAX_SIDE || image.height() > MAX_SIDE
                || x > image.width() - width || y > image.height() - height
                || (rotation == 0 && width == image.width() && height == image.height())) {
                throw invalid();
            }
        }

        int outputWidth() {
            return swapsAxes() ? height : width;
        }

        int outputHeight() {
            return swapsAxes() ? width : height;
        }

        private boolean swapsAxes() {
            return rotation == 90 || rotation == 270;
        }

        private int outputX(int column, int row) {
            return switch (rotation) {
                case 90 -> height - 1 - row;
                case 180 -> width - 1 - column;
                case 270 -> row;
                default -> column;
            };
        }

        private int outputY(int column, int row) {
            return switch (rotation) {
                case 90 -> column;
                case 180 -> height - 1 - row;
                case 270 -> width - 1 - column;
                default -> row;
            };
        }
    }
}
