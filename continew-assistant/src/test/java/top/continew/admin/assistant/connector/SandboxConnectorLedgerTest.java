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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * Verifies the process-independent simulator boundary and receipt identity.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class SandboxConnectorLedgerTest {

    private static final Instant ACCEPTED = Instant.parse("2026-11-24T09:00:00Z");
    private static final String HEADER = "assistant-sandbox-connector-ledger-v1 ";

    @TempDir
    private Path directory;

    @Test
    void confirmsExactlyOneReceiptAfterAnAcknowledgmentIsLostAcrossRestart() throws IOException {
        String binding = UUID.randomUUID().toString();
        String operation = UUID.randomUUID().toString();
        Path path =
            Files.writeString(directory.resolve("isolated-ledger"), HEADER + binding + "\n");
        SandboxConnectorLedger first = ledger(path, binding);
        try {
            assertThat(first.find(operation)).isEmpty();
            assertThat(first.record(operation, ACCEPTED)).isEqualTo(ACCEPTED);
        } finally {
            first.close();
        }
        SandboxConnectorLedger restarted = ledger(path, binding);
        try {
            assertThat(restarted.find(operation)).contains(ACCEPTED);
            assertThat(restarted.record(operation, ACCEPTED.plusSeconds(30))).isEqualTo(ACCEPTED);
            assertThat(restarted.find(UUID.randomUUID().toString())).isEmpty();
        } finally {
            restarted.close();
        }
        assertThat(Files.readAllLines(path)).containsExactly(HEADER + binding,
            operation + " " + ACCEPTED.toEpochMilli());
    }

    @Test
    void rejectsWrongIdentityPartialReceiptAndProductionConfiguration() throws IOException {
        String binding = UUID.randomUUID().toString();
        Path path =
            Files.writeString(directory.resolve("isolated-ledger"), HEADER + binding + "\n");
        assertThatIllegalStateException()
            .isThrownBy(() -> ledger(path, UUID.randomUUID().toString()));
        var deployment = new MockEnvironment();
        deployment.setActiveProfiles("dev", "prod");
        assertThatIllegalStateException().isThrownBy(() -> new SandboxConnectorLedger(deployment,
            true, path.toString(), binding));
        Files.writeString(path, HEADER + binding + "\n" + UUID.randomUUID() + " ");
        assertThatIllegalStateException().isThrownBy(() -> ledger(path, binding));
    }

    private static SandboxConnectorLedger ledger(Path path, String binding) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        return new SandboxConnectorLedger(environment, true, path.toString(), binding);
    }
}
