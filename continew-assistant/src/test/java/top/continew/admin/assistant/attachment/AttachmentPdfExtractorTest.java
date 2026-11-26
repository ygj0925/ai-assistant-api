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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class AttachmentPdfExtractorTest {

    private static final String KEY = "w".repeat(32);
    private static final String TEXT = "text/plain; charset=UTF-8";
    private static final byte[] PDF = "%PDF-1.7\r\nFixture".getBytes(StandardCharsets.US_ASCII);

    @Test
    void keepsBasicHeaderStrictAndExtendsOnlyTheBoundsOfTheNewProfile() {
        assertThatThrownBy(() -> AttachmentPdf.requireHeader(PDF))
            .isInstanceOf(IllegalArgumentException.class);
        AttachmentPdf.requireGeneralHeader(PDF);
        AttachmentPdf.requireGeneralHeader("%PDF-2.0\n".getBytes(StandardCharsets.US_ASCII));
        for (String broken : new String[] {"%PDF-1.8\n", "%PDF-2.1\n", "x%PDF-1.4\n",
            "%PDF-1.4 "}) {
            assertThatThrownBy(() -> AttachmentPdf.requireGeneralHeader(
                broken.getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void acceptsOnlyTheConfiguredIPv4LoopbackPathAndDedicatedKey() {
        for (String invalid : new String[] {"http://localhost:80/extract",
            "http://127.0.0.1/extract",
            "http://127.0.0.1:80/extract?x", "http://127.0.0.1:80/other",
            "https://127.0.0.1:80/extract",
            "http://user@127.0.0.1:80/extract", "http://127.0.0.1:70000/extract"}) {
            assertThatThrownBy(() -> new AttachmentPdfExtractor(invalid, KEY))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new AttachmentPdfExtractor("http://127.0.0.1:80/extract", "short"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sendsRawBoundedBytesAndAcceptsOnlyCanonicalText() throws IOException {
        var result = new AtomicReference<>(new Output(200, TEXT, utf8("Café\n规范文本\n")));
        var received = new AtomicReference<Request>();
        var provenance = new AtomicReference<>(List.<String>of());
        HttpServer server = server();
        server.createContext("/extract", exchange -> {
            try (exchange) {
                received.set(new Request(exchange.getRequestMethod(), exchange.getProtocol(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("X-Assistant-Allow-OCR"),
                    exchange.getRequestBody().readAllBytes()));
                Output output = result.get();
                if (output.type() != null) {
                    exchange.getResponseHeaders().set("Content-Type", output.type());
                }
                provenance.get().forEach(
                    value -> exchange.getResponseHeaders().add("X-Assistant-Text-Source", value));
                exchange.sendResponseHeaders(output.status(),
                    output.bytes().length == 0 ? -1 : output.bytes().length);
                exchange.getResponseBody().write(output.bytes());
            }
        });
        server.start();
        try {
            AttachmentPdfExtractor extractor = extractor(server);
            var embedded = extractor.extract(PDF, false);
            assertThat(embedded.bytes()).isEqualTo(result.get().bytes());
            assertThat(embedded.approximate()).isFalse();
            Request request = received.get();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.protocol()).isEqualTo("HTTP/1.1");
            assertThat(request.authorization()).isEqualTo("Bearer " + KEY);
            assertThat(request.type()).isEqualTo("application/pdf");
            assertThat(request.ocr()).isNull();
            assertThat(request.bytes()).isEqualTo(PDF);

            result.set(new Output(422, null, new byte[0]));
            assertThatThrownBy(() -> extractor.extract(PDF, false))
                .isInstanceOf(IllegalArgumentException.class);
            for (Output invalid : new Output[] {
                new Output(503, null, new byte[0]),
                new Output(200, "text/html", utf8("data\n")),
                new Output(200, TEXT, utf8("x".repeat(AttachmentPdf.MAX_TEXT_BYTES) + "\n")),
                new Output(200, TEXT, new byte[] {(byte) 0xc3, 0x28, 0x0a}),
                new Output(200, TEXT, utf8("Cafe" + (char) 0x0301 + "\n")),
                new Output(200, TEXT, utf8("hidden" + (char) 0x202E + "\n")),
                new Output(200, TEXT, utf8("empty tail\n\n"))
            }) {
                result.set(invalid);
                assertThatThrownBy(() -> extractor.extract(PDF, true))
                    .isInstanceOf(AttachmentPdfExtractor.Unavailable.class);
            }

            result.set(new Output(200, TEXT, utf8("AMBER FIELD 2048\n")));
            provenance.set(List.of("ocr"));
            assertThatThrownBy(() -> extractor.extract(PDF, false))
                .isInstanceOf(IllegalArgumentException.class);
            var scanned = extractor.extract(PDF, true);
            assertThat(scanned.approximate()).isTrue();
            assertThat(received.get().ocr()).isEqualTo("true");
            assertThat(new String(scanned.bytes(), StandardCharsets.UTF_8))
                .isEqualTo("扫描件 OCR 文本，可能有误，请对照原件。\n"
                    + "Scanned OCR text may contain errors. Check the original.\n\nAMBER FIELD 2048\n");

            result.set(
                new Output(200, TEXT, utf8("x".repeat(AttachmentPdf.MAX_TEXT_BYTES - 1) + "\n")));
            assertThatThrownBy(() -> extractor.extract(PDF, true))
                .isInstanceOf(IllegalArgumentException.class);
            result.set(new Output(200, TEXT, utf8("data\n")));
            for (var invalid : List.of(List.of("ocr", "ocr"), List.of("other"))) {
                provenance.set(invalid);
                assertThatThrownBy(() -> extractor.extract(PDF, true))
                    .isInstanceOf(AttachmentPdfExtractor.Unavailable.class);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void boundsTheCompleteBodyEvenAfterSuccessfulHeaders() throws IOException {
        CountDownLatch releaseBody = new CountDownLatch(1);
        HttpServer server = server();
        server.createContext("/extract", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", TEXT);
                exchange.sendResponseHeaders(200, 6);
                exchange.getResponseBody().write('a');
                exchange.getResponseBody().flush();
                releaseBody.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            var extractor = extractor(server);
            assertTimeoutPreemptively(Duration.ofSeconds(7),
                () -> assertThatThrownBy(() -> extractor.extract(PDF, false))
                    .isInstanceOf(AttachmentPdfExtractor.Unavailable.class));
        } finally {
            releaseBody.countDown();
            server.stop(0);
        }
    }

    private static HttpServer server() throws IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private static AttachmentPdfExtractor extractor(HttpServer server) {
        return new AttachmentPdfExtractor("http://127.0.0.1:" + server.getAddress().getPort()
            + "/extract", KEY);
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Output(int status, String type, byte[] bytes) {
    }

    private record Request(String method, String protocol, String authorization, String type,
        String ocr,
        byte[] bytes) {
    }
}
