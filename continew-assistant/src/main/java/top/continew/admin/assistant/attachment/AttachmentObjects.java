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

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/**
 * Private object storage for approved local attachment deployments.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
final class AttachmentObjects implements AutoCloseable {

    private final S3Client client;
    private final String bucket;

    AttachmentObjects(String url, String bucket, String accessKey, String secretKey) {
        URI endpoint = URI.create(url);
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
            || endpoint.getPort() < 1 || endpoint.getUserInfo() != null
            || endpoint.getQuery() != null
            || endpoint.getFragment() != null || !endpoint.getPath().isEmpty()
            || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
            || accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalArgumentException(
                "The private attachment storage configuration is invalid.");
        }
        this.bucket = bucket;
        this.client = S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey,
                    secretKey)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true)
                .chunkedEncodingEnabled(false).build())
            .overrideConfiguration(
                configuration -> configuration.apiCallTimeout(Duration.ofSeconds(5))
                    .apiCallAttemptTimeout(Duration.ofSeconds(3)))
            .build();
    }

    void put(String key, byte[] bytes) {
        client.putObject(request -> request.bucket(bucket).key(key)
            .contentType("application/octet-stream"), RequestBody.fromBytes(bytes));
    }

    byte[] read(String key, int maxBytes) throws IOException {
        try (var response = client.getObject(request -> request.bucket(bucket).key(key))) {
            long declared = response.response().contentLength();
            if (declared < 1 || declared > maxBytes) {
                response.abort();
                throw new IOException("Private object exceeds its bound.");
            }
            byte[] result = response.readNBytes(maxBytes + 1);
            if (result.length != declared) {
                throw new IOException("The private object could not be read in full.");
            }
            return result;
        }
    }

    void delete(String key) {
        client.deleteObject(request -> request.bucket(bucket).key(key));
    }

    boolean deletePage(String prefix) {
        var page =
            client.listObjectsV2(request -> request.bucket(bucket).prefix(prefix).maxKeys(32));
        for (var object : page.contents()) {
            if (!object.key().startsWith(prefix)) {
                throw new IllegalStateException(
                    "Private object storage returned an out-of-scope key.");
            }
            delete(object.key());
        }
        return !page.isTruncated();
    }

    @Override
    public void close() {
        client.close();
    }

    static String prefix(String account) {
        return "assistant-private/" + identifier(account) + "/";
    }

    static String key(String account, String workspace, String attachment, boolean approved) {
        return prefix(account) + identifier(workspace) + "/" + identifier(attachment) + "/"
            + (approved ? "approved.png" : "quarantined");
    }

    private static String identifier(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value)) {
            throw new IllegalArgumentException("Invalid private object scope.");
        }
        return value;
    }
}
