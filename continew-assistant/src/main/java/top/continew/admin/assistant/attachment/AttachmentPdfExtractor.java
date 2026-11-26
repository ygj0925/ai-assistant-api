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
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Sends a scan-approved document to the explicitly configured isolated loopback extractor.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
final class AttachmentPdfExtractor {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{32,128}");
    private static final String TEXT_TYPE = "text/plain; charset=UTF-8";
    private static final byte[] OCR_WARNING = ("扫描件 OCR 文本，可能有误，请对照原件。\n"
        + "Scanned OCR text may contain errors. Check the original.\n\n")
        .getBytes(StandardCharsets.UTF_8);
    private static final Duration DEADLINE = Duration.ofSeconds(4);

    private final URI endpoint;
    private final String bearer;
    private final HttpClient client;

    AttachmentPdfExtractor(String origin, String key) {
        try {
            endpoint = URI.create(origin);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid isolated PDF extractor configuration.");
        }
        int port = endpoint.getPort();
        if (!"http".equals(endpoint.getScheme()) || port < 1 || port > 65535
            || !("127.0.0.1:" + port).equals(endpoint.getRawAuthority())
            || !"/extract".equals(endpoint.getRawPath()) || endpoint.getRawQuery() != null
            || endpoint.getRawFragment() != null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid isolated PDF extractor configuration.");
        }
        bearer = "Bearer " + key;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500))
            .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(ProxySelector.of(null)).build();
    }

    Result extract(byte[] bytes, boolean ocrEnabled) {
        AttachmentPdf.requireGeneralHeader(bytes);
        var builder = HttpRequest.newBuilder(endpoint).timeout(DEADLINE)
            .header("Authorization", bearer).header("Content-Type", "application/pdf");
        if (ocrEnabled) {
            builder.header("X-Assistant-Allow-OCR", "true");
        }
        var request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        var output = new ByteArrayOutputStream();
        var pending = client.sendAsync(request, info -> HttpResponse.BodySubscribers
            .ofByteArrayConsumer(part -> part.ifPresent(fragment -> {
                if (fragment.length > AttachmentPdf.MAX_TEXT_BYTES - output.size()) {
                    throw new Unavailable();
                }
                output.writeBytes(fragment);
            })));

        HttpResponse<Void> response;
        try {
            response = pending.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new Unavailable();
        } catch (ExecutionException | TimeoutException failure) {
            pending.cancel(true);
            throw new Unavailable();
        }
        if (response.statusCode() == 422 && output.size() == 0) {
            throw new IllegalArgumentException("The PDF could not be extracted within its limits.");
        }
        var contentTypes = response.headers().allValues("Content-Type");
        if (response.statusCode() != 200 || output.size() == 0
            || contentTypes.size() != 1 || !TEXT_TYPE.equalsIgnoreCase(contentTypes.get(0))
            || response.headers().firstValue("Content-Encoding").isPresent()
            || !response.headers().allValues("Content-Length")
                .equals(List.of(Integer.toString(output.size())))) {
            throw new Unavailable();
        }
        byte[] extracted = output.toByteArray();
        verifyText(extracted);
        var source = response.headers().allValues("X-Assistant-Text-Source");
        if (source.isEmpty()) {
            return new Result(extracted, false);
        }
        if (!source.equals(List.of("ocr"))) {
            throw new Unavailable();
        }
        if (!ocrEnabled || extracted.length > AttachmentPdf.MAX_TEXT_BYTES - OCR_WARNING.length) {
            throw new IllegalArgumentException(
                "The scanned PDF cannot be accepted within its limits.");
        }
        return new Result(ByteBuffer.allocate(OCR_WARNING.length + extracted.length)
            .put(OCR_WARNING).put(extracted).array(), true);
    }

    private static void verifyText(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
                .toString();
            if (text.isBlank() || !text.endsWith("\n") || text.endsWith("\n\n")
                || !Normalizer.isNormalized(text, Normalizer.Form.NFC)
                || text.codePoints().anyMatch(AttachmentPdfExtractor::invalidText)) {
                throw new Unavailable();
            }
        } catch (CharacterCodingException failure) {
            throw new Unavailable();
        }
    }

    private static boolean invalidText(int point) {
        int category = Character.getType(point);
        return point != '\n' && (category == Character.CONTROL || category == Character.FORMAT
            || category == Character.SURROGATE || category == Character.LINE_SEPARATOR
            || category == Character.PARAGRAPH_SEPARATOR);
    }

    record Result(byte[] bytes, boolean approximate) {
    }

    static final class Unavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
