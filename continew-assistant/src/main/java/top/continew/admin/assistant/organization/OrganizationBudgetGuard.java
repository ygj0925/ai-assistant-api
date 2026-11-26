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

package top.continew.admin.assistant.organization;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import top.continew.admin.assistant.account.AssistantApiException;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists a budget ledger watermark independently of database backups.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Component
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class OrganizationBudgetGuard {

    private static final String HEADER = "assistant-org-model-budget-v1 ";
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final String identity;
    private final boolean configured;
    private final Map<String, Marker> current = new ConcurrentHashMap<>();
    private FileChannel channel;
    private FileLock lock;
    private String tip;
    private volatile boolean healthy;

    /**
     * Opens only an externally provisioned budget guard when explicitly configured.
     *
     * @param path dedicated durable file outside database backup restores
     * @param identity expected guard UUID
     */
    public OrganizationBudgetGuard(
        @Value("${assistant.organization-model-budget.guard-path:}") String path,
        @Value("${assistant.organization-model-budget.guard-id:}") String identity) {
        this.identity = identity;
        this.configured = !path.isBlank() || !identity.isBlank();
        if (configured && !path.isBlank() && canonicalId(identity)) {
            open(path);
        }
    }

    Snapshot inspect(String workspace) {
        return new Snapshot(configured, healthy, identity, current.get(workspace));
    }

    synchronized void advance(String workspace, Marker expected, Marker next) {
        if (!healthy || !Objects.equals(expected, current.get(workspace))
            || next.equals(expected)) {
            throw recoveryRequired();
        }
        if (!canonicalId(workspace) || !valid(next)) {
            throw new IllegalArgumentException("Invalid budget watermark.");
        }
        String receipt = workspace + ":" + next.revision() + ":" + next.watermark() + ":" + tip;
        String digest = digest(receipt);
        byte[] entry = (receipt + ":" + digest + "\n").getBytes(StandardCharsets.US_ASCII);
        try {
            if (entry.length > MAX_BYTES - channel.size()) {
                throw new IOException("Budget guard is full.");
            }
            channel.position(channel.size());
            ByteBuffer bytes = ByteBuffer.wrap(entry);
            while (bytes.hasRemaining()) {
                if (channel.write(bytes) <= 0) {
                    throw new IOException("Budget guard write was incomplete.");
                }
            }
            channel.force(true);
            current.put(workspace, next);
            tip = digest;
        } catch (IOException | RuntimeException failure) {
            healthy = false;
            throw recoveryRequired();
        }
    }

    private void open(String path) {
        try {
            channel =
                FileChannel.open(Path.of(path), StandardOpenOption.READ, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            long size = channel.size();
            if (lock == null || size < 1 || size > MAX_BYTES) {
                throw new IOException(
                    "Budget guard cannot be locked or is outside its size limit.");
            }
            ByteBuffer bytes = ByteBuffer.allocate((int) size);
            while (bytes.hasRemaining()) {
                if (channel.read(bytes) <= 0) {
                    throw new IOException("Budget guard read was incomplete.");
                }
            }
            parse(new String(bytes.array(), StandardCharsets.US_ASCII));
            healthy = true;
        } catch (IOException | RuntimeException failure) {
            close();
        }
    }

    private void parse(String input) {
        String header = HEADER + identity;
        String[] lines = input.split("\n", -1);
        if (lines.length < 2 || !header.equals(lines[0]) || !lines[lines.length - 1].isEmpty()) {
            throw new IllegalArgumentException("Invalid budget guard header.");
        }
        tip = digest(header);
        for (int pos = 1; pos < lines.length - 1; pos++) {
            String line = lines[pos];
            String[] fields = line.split(":", -1);
            if (fields.length != 5 || !tip.equals(fields[3]) || !canonicalId(fields[0])) {
                throw new IllegalArgumentException("Invalid budget watermark chain.");
            }
            var marker = new Marker(fields[1], fields[2]);
            String computed = digest(line.substring(0, line.lastIndexOf(':')));
            if (!valid(marker) || !computed.equals(fields[4])
                || marker.equals(current.get(fields[0]))) {
                throw new IllegalArgumentException("Invalid budget watermark receipt.");
            }
            current.put(fields[0], marker);
            tip = computed;
        }
    }

    private static boolean valid(Marker marker) {
        return canonicalId(marker.revision()) && canonicalId(marker.watermark());
    }

    static boolean canonicalId(String value) {
        try {
            return value != null && value.length() == 36
                && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Budget guard checksum unavailable.", failure);
        }
    }

    static AssistantApiException recoveryRequired() {
        return new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
            "ORGANIZATION_BUDGET_RECOVERY_REQUIRED",
            "The organization budget ledger is unavailable or does not match its durable watermark.");
    }

    /**
     * Releases the isolated budget guard lock at shutdown.
     */
    @PreDestroy
    public synchronized void close() {
        healthy = false;
        current.clear();
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // The app never uses this guard again until explicitly opened.
            }
        }
    }

    record Marker(String revision, String watermark) {
    }

    record Snapshot(boolean configured, boolean ready, String identity, Marker marker) {
    }
}
