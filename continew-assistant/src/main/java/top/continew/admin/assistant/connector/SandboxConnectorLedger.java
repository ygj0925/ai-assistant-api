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

package top.continew.admin.assistant.connector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Independent, narrowly typed side-effect simulator for a local test deployment.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Component
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class SandboxConnectorLedger {

    private static final String HEADER = "assistant-sandbox-connector-ledger-v1 ";
    private static final int MAX_RECORDS = 2048;
    private static final int MAX_BYTES = 160 * 1024;

    private final Map<String, Instant> receipts = new HashMap<>();
    private final String binding;
    private FileChannel channel;
    private FileLock lock;
    private boolean healthy;

    /**
     * Opens an isolated pre-provisioned simulator ledger for local development and testing.
     *
     * @param environment active Spring profiles
     * @param enabled isolated simulator switch
     * @param path bound ledger path
     * @param binding expected provisioning identifier
     */
    public SandboxConnectorLedger(Environment environment,
        @Value("${assistant.sandbox-connector.enabled:false}") boolean enabled,
        @Value("${assistant.sandbox-connector.ledger-path:}") String path,
        @Value("${assistant.sandbox-connector.ledger-id:}") String binding) {
        this.binding = enabled ? binding : null;
        if (!enabled) {
            return;
        }
        var profiles = Arrays.asList(environment.getActiveProfiles());
        if (profiles.contains("prod")
            || (!profiles.contains("dev") && !profiles.contains("test"))) {
            throw new IllegalStateException(
                "The connector simulator requires a local test profile.");
        }
        open(path, binding);
    }

    String binding() {
        return binding;
    }

    synchronized boolean canRead() {
        return readable();
    }

    synchronized boolean canRecord() {
        return readable() && receipts.size() < MAX_RECORDS;
    }

    synchronized Optional<Instant> find(String operation) {
        if (!readable()) {
            throw new IllegalStateException("The connector simulator cannot verify receipts.");
        }
        return Optional.ofNullable(receipts.get(operation));
    }

    synchronized Instant record(String operation, Instant now) {
        if (!uuid(operation) || now.toEpochMilli() <= 0) {
            throw new IllegalArgumentException("Invalid simulator operation.");
        }
        var existing = find(operation);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        if (!canRecord()) {
            throw new IllegalStateException("The connector simulator receipt limit was reached.");
        }
        byte[] line = (operation + " " + now.toEpochMilli() + "\n")
            .getBytes(StandardCharsets.US_ASCII);
        try {
            if (channel.size() + line.length > MAX_BYTES) {
                healthy = false;
                throw new IllegalStateException(
                    "The connector simulator receipt limit was reached.");
            }
            channel.position(channel.size());
            ByteBuffer bytes = ByteBuffer.wrap(line);
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        } catch (IOException failure) {
            healthy = false;
            throw new IllegalStateException(
                "The connector simulator could not confirm its record.");
        }
        Instant recorded = Instant.ofEpochMilli(now.toEpochMilli());
        receipts.put(operation, recorded);
        return recorded;
    }

    private boolean readable() {
        return healthy && lock != null && lock.isValid();
    }

    private void open(String rawPath, String binding) {
        try {
            Path path = Path.of(rawPath);
            if (!uuid(binding) || !path.isAbsolute()
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Invalid simulator ledger binding.");
            }
            channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null || channel.size() <= 0 || channel.size() > MAX_BYTES) {
                throw new IllegalStateException("Simulator ledger is unavailable.");
            }
            readRecords(binding);
            healthy = true;
        } catch (IOException | RuntimeException error) {
            close();
            throw new IllegalStateException("The bound connector simulator ledger is unavailable.");
        }
    }

    private void readRecords(String binding) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate((int) channel.size());
        while (bytes.hasRemaining() && channel.read(bytes) >= 0) {
            // Read the bounded ledger while holding its exclusive lock.
        }
        String contents = new String(bytes.array(), StandardCharsets.US_ASCII);
        String[] lines = contents.split("\n");
        if (!contents.endsWith("\n") || lines.length == 0 || !lines[0].equals(HEADER + binding)
            || lines.length > MAX_RECORDS + 1) {
            throw new IllegalStateException("Invalid simulator ledger.");
        }
        for (int i = 1; i < lines.length; i++) {
            String[] fields = lines[i].split(" ", -1);
            if (fields.length != 2 || !uuid(fields[0]) || !fields[1].matches("[1-9][0-9]{0,15}")) {
                throw new IllegalStateException("Invalid simulator receipt.");
            }
            Instant time = Instant.ofEpochMilli(Long.parseLong(fields[1]));
            if (receipts.putIfAbsent(fields[0], time) != null) {
                throw new IllegalStateException("Duplicate simulator receipt.");
            }
        }
    }

    static boolean uuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * Closes the process-owned file lock.
     */
    @PreDestroy
    public synchronized void close() {
        healthy = false;
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // Closing is safe after the simulator has stopped accepting writes.
            }
        }
    }
}
