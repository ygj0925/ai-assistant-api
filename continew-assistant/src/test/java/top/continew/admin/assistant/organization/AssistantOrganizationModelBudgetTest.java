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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.organization.AssistantOrganizationModelBudgets.Month;
import top.continew.admin.assistant.organization.AssistantOrganizationModelBudgets.Totals;
import top.continew.admin.assistant.organization.OrganizationBudgetGuard.Marker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Checks the exact local budget bounds and durable persistence boundary.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantOrganizationModelBudgetTest {

    private static final String GUARD_ID = "dfc34ae4-359a-4aa0-b49d-c31bb912147f";
    private static final String FIRST = "61c37daa-a0b0-4ad3-8824-7f85936b6a17";
    private static final String SECOND = "f825b966-bf17-4011-b3a5-d0c2b357e265";
    private static final String REVIEW = "bf418dcd-a826-4a39-9b71-c785f0b3409d";
    private static final String ADVANCE = "38a498c4-5ef4-4caa-991d-289a8ce979d5";
    private static final String RECOVERY = "ORGANIZATION_BUDGET_RECOVERY_REQUIRED";

    @TempDir
    Path temp;

    @Test
    void defaultOffNeedsNoFileAndAnExplicitMissingGuardFailsClosed() {
        var defaultOff = new OrganizationBudgetGuard("", "");
        assertFalse(defaultOff.inspect(FIRST).configured());
        assertNull(defaultOff.inspect(FIRST).marker());

        Path missing = temp.resolve("missing.guard");
        var configuredMissing = new OrganizationBudgetGuard(missing.toString(), GUARD_ID);
        assertTrue(configuredMissing.inspect(FIRST).configured());
        assertFalse(configuredMissing.inspect(FIRST).ready());
        assertFalse(Files.exists(missing));
        var refused = assertThrows(AssistantApiException.class,
            () -> configuredMissing.advance(FIRST, null, new Marker(REVIEW, ADVANCE)));
        assertEquals(RECOVERY, refused.code());
    }

    @Test
    void readsCommittedWatermarksAfterOpenAndRejectsBusyOrStaleAttempts() throws Exception {
        Path path = guardFile();
        var first = new Marker(REVIEW, FIRST);
        var second = new Marker(REVIEW, ADVANCE);
        var original = new OrganizationBudgetGuard(path.toString(), GUARD_ID);
        try {
            assertTrue(original.inspect(FIRST).ready());
            original.advance(FIRST, null, first);
            original.advance(SECOND, null, first);
            original.advance(FIRST, first, second);
            long length = Files.size(path);
            assertEquals(RECOVERY, assertThrows(AssistantApiException.class,
                () -> original.advance(FIRST, first, new Marker(REVIEW, SECOND))).code());
            assertEquals(length, Files.size(path));
            var duplicate = new OrganizationBudgetGuard(path.toString(), GUARD_ID);
            assertFalse(duplicate.inspect(FIRST).ready());
            assertTrue(original.inspect(FIRST).ready());
        } finally {
            original.close();
        }
        var reopen = new OrganizationBudgetGuard(path.toString(), GUARD_ID);
        try {
            assertEquals(second, reopen.inspect(FIRST).marker());
            assertEquals(first, reopen.inspect(SECOND).marker());
        } finally {
            reopen.close();
        }
    }

    @Test
    void refusesWrongIdentityAndTruncatedReceiptsWithoutCreatingState() throws Exception {
        Path path = guardFile();
        var guard = new OrganizationBudgetGuard(path.toString(), GUARD_ID);
        guard.advance(FIRST, null, new Marker(REVIEW, ADVANCE));
        guard.close();
        var wrong = new OrganizationBudgetGuard(path.toString(), FIRST);
        assertFalse(wrong.inspect(FIRST).ready());
        String contents = Files.readString(path);
        Files.writeString(path, contents.substring(0, contents.length() - 2));
        var truncated = new OrganizationBudgetGuard(path.toString(), GUARD_ID);
        assertFalse(truncated.inspect(FIRST).ready());
        assertNull(truncated.inspect(FIRST).marker());
    }

    @Test
    void validatesExplicitNullIntegerAndExactlyThreeFields() throws Exception {
        var reader = new ObjectMapper();
        var open = AssistantOrganizationModelBudgetController.parse(reader.readTree(
            "{\"baseRevision\":\"" + REVIEW + "\",\"confirm\":true,\"monthlyLimitUnits\":null}"));
        assertNull(open.limit());
        var zero = AssistantOrganizationModelBudgetController.parse(reader.readTree(
            "{\"baseRevision\":\"" + REVIEW + "\",\"confirm\":true,\"monthlyLimitUnits\":0}"));
        assertEquals(0L, zero.limit().longValue());
        for (String unsafe : new String[] {"false", "\"2\"", "2.4", "9223372036854775808"}) {
            var bad = reader.readTree("{\"baseRevision\":\"" + REVIEW
                + "\",\"confirm\":true,\"monthlyLimitUnits\":" + unsafe + "}");
            assertEquals("VALIDATION_ERROR", assertThrows(AssistantApiException.class,
                () -> AssistantOrganizationModelBudgetController.parse(bad)).code());
        }
        var missing = reader
            .readTree("{\"baseRevision\":\"" + REVIEW + "\",\"confirm\":true,\"wrong\":null}");
        assertThrows(AssistantApiException.class,
            () -> AssistantOrganizationModelBudgetController.parse(missing));
    }

    @Test
    void crossesUtcYearsDeterministicallyAndRefusesOverflowOrNegativeLedger() {
        var december = Month.at(Instant.parse("2026-12-31T23:59:59.999Z"));
        var january = Month.at(Instant.parse("2027-01-01T00:00:00Z"));
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), december.start());
        assertEquals(december.endExclusive(), january.start());
        assertEquals(Instant.parse("2027-02-01T00:00:00Z"), january.endExclusive());
        assertEquals(8193L, new Totals(8192, 1).occupied());
        assertEquals(RECOVERY, assertThrows(AssistantApiException.class,
            () -> new Totals(Long.MAX_VALUE, 1).occupied()).code());
        assertEquals(RECOVERY,
            assertThrows(AssistantApiException.class, () -> new Totals(-1, 0)).code());
    }

    private Path guardFile() throws Exception {
        return Files.writeString(temp.resolve("organization.guard"),
            "assistant-org-model-budget-v1 " + GUARD_ID + "\n", StandardCharsets.US_ASCII);
    }
}
