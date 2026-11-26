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

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Verifies explicit scanner acceptance and bounded, fail-closed protocol handling.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AttachmentScannerTest {

    @Test
    void acceptsOnlyTheExactCleanReply() throws Exception {
        assertThat(exchange("stream: OK\0")).isEqualTo(AttachmentScanner.Verdict.CLEAN);
        assertThat(exchange("stream: Synthetic.Test FOUND\0"))
            .isEqualTo(AttachmentScanner.Verdict.REJECTED);
        assertThat(exchange("stream: IO error\0")).isEqualTo(AttachmentScanner.Verdict.UNAVAILABLE);
        assertThat(exchange("stream: OK")).isEqualTo(AttachmentScanner.Verdict.UNAVAILABLE);
    }

    private static AttachmentScanner.Verdict exchange(String reply) throws Exception {
        byte[] bytes = {1, 2, 3, 4};
        var executor = Executors.newSingleThreadExecutor();
        try (var listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(2_000);
            var expected = executor.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(2_000);
                    var input = new DataInputStream(socket.getInputStream());
                    assertThat(input.readNBytes(10))
                        .isEqualTo("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                    assertThat(input.readInt()).isEqualTo(bytes.length);
                    assertThat(input.readNBytes(bytes.length)).isEqualTo(bytes);
                    assertThat(input.readInt()).isZero();
                    socket.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));
                }
                return null;
            });
            var verdict = new AttachmentScanner(listener.getLocalPort()).scan(bytes);
            expected.get(3, TimeUnit.SECONDS);
            return verdict;
        } finally {
            executor.shutdownNow();
        }
    }
}
