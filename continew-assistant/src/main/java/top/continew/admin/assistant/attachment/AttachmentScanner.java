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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Submits the exact quarantined bytes to a local ClamAV daemon through its INSTREAM protocol.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
final class AttachmentScanner {

    private static final byte[] START_SCAN = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_REPLY = 1024;
    private static final long LIMIT_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final InetSocketAddress address;

    AttachmentScanner(int port) {
        address = new InetSocketAddress("127.0.0.1", port);
    }

    Verdict scan(byte[] body) {
        ByteBuffer command =
            ByteBuffer.allocate(START_SCAN.length + 2 * Integer.BYTES + body.length);
        command.put(START_SCAN).putInt(body.length).put(body).putInt(0).flip();
        try {
            String response = exchange(command);
            if ("stream: OK".equals(response)) {
                return Verdict.CLEAN;
            }
            return response.startsWith("stream: ") && response.endsWith(" FOUND")
                ? Verdict.REJECTED : Verdict.UNAVAILABLE;
        } catch (IOException failure) {
            return Verdict.UNAVAILABLE;
        }
    }

    private String exchange(ByteBuffer command) throws IOException {
        long deadline = System.nanoTime() + LIMIT_NANOS;
        try (var socket = SocketChannel.open(); var selector = Selector.open()) {
            socket.configureBlocking(false);
            var key = socket.register(selector, 0);
            if (!socket.connect(address)) {
                while (!socket.finishConnect()) {
                    await(selector, key, SelectionKey.OP_CONNECT, deadline);
                }
            }
            while (command.hasRemaining()) {
                if (socket.write(command) == 0) {
                    await(selector, key, SelectionKey.OP_WRITE, deadline);
                }
            }
            ByteBuffer reply = ByteBuffer.allocate(MAX_REPLY);
            int inspected = 0;
            while (reply.hasRemaining()) {
                int received = socket.read(reply);
                if (received < 0) {
                    break;
                }
                if (received == 0) {
                    await(selector, key, SelectionKey.OP_READ, deadline);
                }
                while (inspected < reply.position()) {
                    if (reply.get(inspected) == 0) {
                        return new String(reply.array(), 0, inspected, StandardCharsets.US_ASCII);
                    }
                    inspected++;
                }
            }
            throw new IOException("Invalid malware scanner response.");
        }
    }

    private static void await(Selector selector, SelectionKey key, int operation,
        long deadline) throws IOException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0 || Thread.currentThread().isInterrupted()) {
            throw new IOException("The malware scanner exceeded its time budget.");
        }
        key.interestOps(operation);
        selector.select(Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos)));
        selector.selectedKeys().clear();
    }

    enum Verdict {
        CLEAN, REJECTED, UNAVAILABLE
    }
}
