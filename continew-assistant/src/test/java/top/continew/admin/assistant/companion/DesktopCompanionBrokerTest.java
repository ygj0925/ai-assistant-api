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

package top.continew.admin.assistant.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import top.continew.admin.assistant.companion.DesktopCompanionBroker.Actor;
import top.continew.admin.assistant.companion.DesktopCompanionBroker.Binding;
import top.continew.admin.assistant.companion.DesktopCompanionBroker.Failure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

class DesktopCompanionBrokerTest {

    private final MutableClock clock = new MutableClock();
    private final Set<Actor> revoked = new HashSet<>();
    private final DesktopCompanionBroker broker =
        new DesktopCompanionBroker(clock, (actor) -> !revoked.contains(actor),
            Duration.ofSeconds(30), Duration.ofSeconds(80), Duration.ofSeconds(15),
            Duration.ofSeconds(8));
    private final String account = id();
    private final String workspace = id();
    private final String membership = id();
    private final Actor desktop = device();
    private final Actor mobile = device();
    private final Actor unpaired = device();

    @Test
    void challengeRequiresSameOwnerDifferentSessionAndSeparateConfirmation() {
        var offer = broker.offer(desktop);
        assertEquals(12, offer.code().length());
        assertThrows(Failure.class, () -> broker.claim(desktop, offer.code()));
        var otherWorkspace = new Actor(account, id(), id(), membership);
        assertEquals(404,
            assertThrows(Failure.class, () -> broker.claim(otherWorkspace, offer.code())).status);
        var otherUser = new Actor(id(), workspace, id(), membership);
        assertEquals(404,
            assertThrows(Failure.class, () -> broker.claim(otherUser, offer.code())).status);
        var pending = broker.claim(mobile, offer.code());
        assertEquals("awaiting_desktop_confirmation", pending.pair().state());
        assertTrue(pending.audit().isEmpty());
        assertEquals(404,
            assertThrows(Failure.class, () -> broker.claim(unpaired, offer.code())).status);
        assertThrows(Failure.class, () -> broker.confirm(mobile, pending.pair().id(), true));
        assertThrows(Failure.class, () -> broker.confirm(desktop, pending.pair().id(), false));
        assertEquals("active", broker.confirm(desktop, pending.pair().id(), true).pair().state());
        assertNull(broker.state(unpaired).pair());
    }

    @Test
    void throttlesAttemptsAndExpiresOldChallenges() {
        var grant = broker.offer(desktop);
        for (int attempt = 0; attempt < 6; attempt++) {
            assertEquals(404,
                assertThrows(Failure.class, () -> broker.claim(mobile, "not valid")).status);
        }
        assertEquals(429,
            assertThrows(Failure.class, () -> broker.claim(unpaired, grant.code())).status);
        clock.advance(61);
        assertEquals(404,
            assertThrows(Failure.class, () -> broker.claim(mobile, grant.code())).status);
        assertNull(broker.state(mobile).pair());
    }

    @Test
    void replacesPriorDirectoryOrRevisionAndConsumesOnlyExactApprovalOnce() {
        String pair = link();
        Binding first = new Binding(id(), 1, "a".repeat(64));
        String stale = broker.publish(desktop, pair, first).task().id();
        assertThrows(Failure.class, () -> broker.decide(desktop, pair, stale, first, "approve"));
        broker.decide(mobile, pair, stale, first, "approve");
        Binding moved = new Binding(id(), 2, "b".repeat(64));
        var replacement = broker.publish(desktop, pair, moved).task();
        assertEquals("pending", replacement.status());
        assertEquals(404,
            assertThrows(Failure.class, () -> broker.consume(desktop, pair, stale, first)).status);
        assertThrows(Failure.class,
            () -> broker.decide(mobile, pair, replacement.id(), first, "approve"));
        assertThrows(Failure.class,
            () -> broker.decide(unpaired, pair, replacement.id(), moved, "approve"));
        broker.decide(mobile, pair, replacement.id(), moved, "approve");
        assertThrows(Failure.class, () -> broker.consume(mobile, pair, replacement.id(), moved));
        broker.consume(desktop, pair, replacement.id(), moved);
        assertEquals(409, assertThrows(Failure.class,
            () -> broker.consume(desktop, pair, replacement.id(), moved)).status);
        assertEquals("consumed", broker.state(mobile).task().status());
        assertTrue(broker.state(desktop).audit().stream()
            .anyMatch((entry) -> "task_consumed".equals(entry.event())));
        var newReview = broker.publish(desktop, pair, moved).task();
        assertEquals("pending", newReview.status());
        assertFalse(replacement.id().equals(newReview.id()));
    }

    @Test
    void observesLostDeviceAuthorityAfterCredentialResolutionAndKeepsDesktopAudit() {
        String pair = link();
        Binding binding = new Binding(id(), 1, "c".repeat(64));
        var task = broker.publish(desktop, pair, binding).task();
        Actor earlierResolvedBearer = mobile;
        revoked.add(mobile);
        assertThrows(Failure.class,
            () -> broker.decide(earlierResolvedBearer, pair, task.id(), binding, "approve"));
        assertThrows(Failure.class, () -> broker.consume(desktop, pair, task.id(), binding));
        var after = broker.state(desktop);
        assertNull(after.pair());
        assertFalse(
            after.audit().stream().anyMatch((entry) -> "task_approved".equals(entry.event())));
        assertTrue(
            after.audit().stream().anyMatch((entry) -> "device_unavailable".equals(entry.event())));
    }

    @Test
    void observesExpiredHostLeaseAndUnpairPreventsOldMobileActions() {
        String pair = link();
        Binding binding = new Binding(id(), 1, "d".repeat(64));
        var task = broker.publish(desktop, pair, binding).task();
        clock.advance(9);
        assertNull(broker.state(mobile).task());
        assertThrows(Failure.class,
            () -> broker.decide(mobile, pair, task.id(), binding, "approve"));
        var next = broker.publish(desktop, pair, binding).task();
        broker.revoke(mobile, pair);
        assertNull(broker.state(mobile).pair());
        assertThrows(Failure.class,
            () -> broker.decide(mobile, pair, next.id(), binding, "approve"));
        assertEquals("pair_revoked", broker.state(desktop).audit().getLast().event());
        assertEquals(List.of(), broker.state(unpaired).audit());
    }

    @Test
    void brokerRestartDoesNotReviveAnApprovalOrCompanionAudit() {
        String pair = link();
        Binding binding = new Binding(id(), 1, "e".repeat(64));
        var task = broker.publish(desktop, pair, binding).task();
        broker.decide(mobile, pair, task.id(), binding, "approve");
        var restarted = new DesktopCompanionBroker(clock, (actor) -> true, Duration.ofSeconds(30),
            Duration.ofSeconds(80), Duration.ofSeconds(15), Duration.ofSeconds(8));
        assertNull(restarted.state(desktop).pair());
        assertEquals(List.of(), restarted.state(desktop).audit());
        assertThrows(Failure.class, () -> restarted.consume(desktop, pair, task.id(), binding));
    }

    private Actor device() {
        return new Actor(account, workspace, id(), membership);
    }

    private String link() {
        var offer = broker.offer(desktop);
        String pair = broker.claim(mobile, offer.code()).pair().id();
        broker.confirm(desktop, pair, true);
        return pair;
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-11-24T00:00:00Z");

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
