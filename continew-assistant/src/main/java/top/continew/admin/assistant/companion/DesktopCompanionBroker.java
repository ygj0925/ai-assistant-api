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

import com.fasterxml.jackson.annotation.JsonInclude;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Bounded, process-local pairing authority for isolated development deployments.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
final class DesktopCompanionBroker {

    private static final int MAX_ROWS = 128;
    private static final int MAX_AUDIT = 24;
    private static final int MAX_TRIES = 6;
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(1);
    private static final Duration RETAIN_AUDIT = Duration.ofMinutes(10);
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final Pattern CODE = Pattern.compile("[0-9A-HJKMNP-TV-Z]{12}");
    private static final Pattern UUID_TEXT =
        Pattern.compile("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    private static final Pattern SHA = Pattern.compile("[a-f0-9]{64}");
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Predicate<Actor> authorized;
    private final Duration offerTtl;
    private final Duration pairTtl;
    private final Duration taskTtl;
    private final Duration hostLease;
    private final Map<String, StoredOffer> offers = new LinkedHashMap<>();
    private final Map<String, StoredPair> pairs = new LinkedHashMap<>();
    private final Map<String, Attempts> attempts = new LinkedHashMap<>();
    private final LinkedHashMap<Actor, AuditTrail> audits = new LinkedHashMap<>();

    DesktopCompanionBroker(Clock clock, Predicate<Actor> authorized, Duration offerTtl,
        Duration pairTtl,
        Duration taskTtl, Duration hostLease) {
        this.clock = clock;
        this.authorized = authorized;
        this.offerTtl = offerTtl;
        this.pairTtl = pairTtl;
        this.taskTtl = taskTtl;
        this.hostLease = hostLease;
    }

    synchronized void clearAuthorities() {
        offers.clear();
        pairs.clear();
    }

    synchronized Offer offer(Actor actor) {
        Instant now = enter(actor);
        if (activePair(actor, now) != null) {
            throw conflict();
        }
        offers.values().removeIf((stored) -> stored.desktop().equals(actor));
        if (offers.size() >= MAX_ROWS) {
            throw limited();
        }
        String code;
        String hash;
        do {
            var builder = new StringBuilder(12);
            for (int index = 0; index < 12; index++) {
                builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            code = builder.toString();
            hash = hash(code);
        } while (offers.containsKey(hash));
        Instant expires = now.plus(offerTtl);
        offers.put(hash, new StoredOffer(actor, expires));
        log(actor, "offer_created", now);
        return new Offer(code, expires);
    }

    synchronized State claim(Actor actor, String suppliedCode) {
        Instant now = enter(actor);
        takeAttempt(actor.account(), now);
        String code = suppliedCode == null || suppliedCode.length() != 12 ? ""
            : suppliedCode.toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw missing();
        }
        String hash = hash(code);
        StoredOffer grant = offers.get(hash);
        if (grant == null || grant.desktop().session().equals(actor.session())
            || !grant.desktop().sameScope(actor)
            || !authorized.test(grant.desktop())) {
            throw missing();
        }
        if (pairs.size() >= MAX_ROWS || activePair(actor, now) != null
            || activePair(grant.desktop(), now) != null) {
            throw conflict();
        }
        offers.remove(hash);
        var pair =
            new StoredPair(UUID.randomUUID().toString(), grant.desktop(), actor, grant.expiresAt());
        pairs.put(pair.id, pair);
        log(pair.desktop, "mobile_claimed", now);
        return projection(actor, pair);
    }

    synchronized State confirm(Actor actor, String id, boolean confirmed) {
        Instant now = enter(actor);
        StoredPair pair = requirePair(actor, id, now);
        if (!pair.desktop.equals(actor) || pair.active || !confirmed) {
            throw conflict();
        }
        pair.active = true;
        pair.expiresAt = now.plus(pairTtl);
        pair.hostSeenAt = now;
        log(actor, "paired", now);
        return projection(actor, pair);
    }

    synchronized void revoke(Actor actor, String id) {
        Instant now = enter(actor);
        StoredPair pair = requirePair(actor, id, now);
        pairs.remove(pair.id);
        log(pair.desktop, "pair_revoked", now);
    }

    synchronized State state(Actor actor) {
        Instant now = enter(actor);
        StoredPair pair = activePair(actor, now);
        if (pair != null && pair.active && pair.desktop.equals(actor)) {
            pair.hostSeenAt = now;
        }
        return projection(actor, pair);
    }

    synchronized State publish(Actor actor, String id, Binding binding) {
        Instant now = enter(actor);
        StoredPair pair = requireSide(actor, id, now, true);
        validate(binding);
        pair.hostSeenAt = now;
        if (pair.task == null || !pair.task.binding().equals(binding)
            || "declined".equals(pair.task.status()) || "consumed".equals(pair.task.status())) {
            if (pair.task != null) {
                log(actor, "task_cancelled", now);
            }
            Instant expiration = now.plus(taskTtl);
            if (pair.expiresAt.isBefore(expiration)) {
                expiration = pair.expiresAt;
            }
            pair.task =
                new StoredTask(UUID.randomUUID().toString(), binding, "pending", expiration);
            log(actor, "task_offered", now);
        }
        return projection(actor, pair);
    }

    synchronized void withdraw(Actor actor, String id, String taskId) {
        Instant now = enter(actor);
        StoredPair pair = requireSide(actor, id, now, true);
        requireTask(pair, taskId);
        pair.task = null;
        log(actor, "task_cancelled", now);
    }

    synchronized State decide(Actor actor, String id, String taskId, Binding binding,
        String decision) {
        Instant now = enter(actor);
        StoredPair pair = requireSide(actor, id, now, false);
        StoredTask task = requireTask(pair, taskId);
        validate(binding);
        if (!Objects.equals(binding, task.binding()) || !"pending".equals(task.status())) {
            throw conflict();
        }
        String next = switch (decision == null ? "" : decision) {
            case "approve" -> "approved";
            case "decline" -> "declined";
            default -> throw new Failure(400, "Choose a review decision.");
        };
        pair.task = task.withStatus(next);
        log(pair.desktop, "task_" + next, now);
        return projection(actor, pair);
    }

    synchronized void consume(Actor actor, String id, String taskId, Binding binding) {
        Instant now = enter(actor);
        StoredPair pair = requireSide(actor, id, now, true);
        StoredTask task = requireTask(pair, taskId);
        validate(binding);
        if (!Objects.equals(binding, task.binding()) || !"approved".equals(task.status())) {
            throw conflict();
        }
        pair.task = task.withStatus("consumed");
        pair.hostSeenAt = now;
        log(actor, "task_consumed", now);
    }

    private Instant enter(Actor actor) {
        if (actor == null || !authorized.test(actor)) {
            throw missing();
        }
        Instant now = clock.instant();
        offers.values().removeIf((offer) -> !offer.expiresAt().isAfter(now));
        attempts.values().removeIf((row) -> !row.until().isAfter(now));
        audits.values().removeIf((row) -> !row.until().isAfter(now));
        for (var iterator = pairs.values().iterator(); iterator.hasNext();) {
            StoredPair pair = iterator.next();
            if (!pair.expiresAt.isAfter(now)) {
                iterator.remove();
                log(pair.desktop, "expired", now);
            } else if (pair.task != null && (!pair.task.expiresAt().isAfter(now)
                || !pair.hostSeenAt.plus(hostLease).isAfter(now))) {
                pair.task = null;
                log(pair.desktop, "expired", now);
            }
        }
        return now;
    }

    private void takeAttempt(String account, Instant now) {
        Attempts bucket = attempts.get(account);
        if (bucket == null && attempts.size() >= MAX_ROWS
            || bucket != null && bucket.count() >= MAX_TRIES) {
            throw limited();
        }
        attempts.put(account, bucket == null ? new Attempts(1, now.plus(ATTEMPT_WINDOW))
            : new Attempts(bucket.count() + 1, bucket.until()));
    }

    private StoredPair activePair(Actor actor, Instant now) {
        return pairs.values().stream().filter((pair) -> pair.belongsTo(actor)).findFirst()
            .map((pair) -> live(pair, now)).orElse(null);
    }

    private StoredPair live(StoredPair pair, Instant now) {
        if (authorized.test(pair.desktop) && authorized.test(pair.mobile)) {
            return pair;
        }
        pairs.remove(pair.id);
        log(pair.desktop, "device_unavailable", now);
        return null;
    }

    private StoredPair requirePair(Actor actor, String id, Instant now) {
        StoredPair pair = pairs.get(id);
        if (pair == null || !pair.belongsTo(actor) || live(pair, now) == null) {
            throw missing();
        }
        return pair;
    }

    private StoredPair requireSide(Actor actor, String id, Instant now, boolean desktop) {
        StoredPair pair = requirePair(actor, id, now);
        if (!pair.active || (desktop ? !pair.desktop.equals(actor) : !pair.mobile.equals(actor))) {
            throw missing();
        }
        return pair;
    }

    private static StoredTask requireTask(StoredPair pair, String id) {
        if (pair.task == null || !pair.task.id().equals(id)) {
            throw missing();
        }
        return pair.task;
    }

    private State projection(Actor actor, StoredPair value) {
        AuditTrail trail = audits.get(actor);
        List<AuditEvent> audit = trail == null ? List.of() : List.copyOf(trail.items());
        if (value == null) {
            return new State(null, null, audit);
        }
        boolean desktop = value.desktop.equals(actor);
        Actor peer = desktop ? value.mobile : value.desktop;
        var pair = new Pair(value.id, value.active ? "active" : "awaiting_desktop_confirmation",
            desktop ? "desktop" : "mobile",
            peer.session().substring(0, 8), value.expiresAt);
        if (!desktop) {
            audit = List.of();
        }
        StoredTask task = value.task;
        return new State(pair,
            task == null ? null
                : new Task(task.id(), task.binding().scopeId(), task.binding().revision(),
                    task.binding().sourceSha256(), task.status(), task.expiresAt()),
            audit);
    }

    private void log(Actor actor, String event, Instant now) {
        var previous = audits.remove(actor);
        if (previous == null && audits.size() >= MAX_ROWS) {
            audits.pollFirstEntry();
        }
        ArrayDeque<AuditEvent> records = previous == null ? new ArrayDeque<>() : previous.items();
        if (records.size() >= MAX_AUDIT) {
            records.removeFirst();
        }
        records.addLast(new AuditEvent(event, now));
        audits.put(actor, new AuditTrail(records, now.plus(RETAIN_AUDIT)));
    }

    private static void validate(Binding binding) {
        if (binding == null || binding.scopeId() == null
            || !UUID_TEXT.matcher(binding.scopeId()).matches()
            || binding.revision() < 1 || binding.revision() > 24 || binding.sourceSha256() == null
            || !SHA.matcher(binding.sourceSha256()).matches()) {
            throw new Failure(400, "Invalid local revision scope.");
        }
    }

    private static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(code.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static Failure missing() {
        return new Failure(404, "Companion scope unavailable.");
    }

    private static Failure conflict() {
        return new Failure(409, "Companion revision or pairing changed.");
    }

    private static Failure limited() {
        return new Failure(429, "Companion request limit reached.");
    }

    record Actor(String account, String workspace, String session, String membership) {

        boolean sameScope(Actor other) {
            return account.equals(other.account) && workspace.equals(other.workspace)
                && membership.equals(other.membership);
        }
    }

    record Offer(String code, Instant expiresAt) {
    }

    record Pair(String id, String state, String role, String peerDevice, Instant expiresAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record State(Pair pair, Task task, List<AuditEvent> audit) {
    }

    record Task(String id, String scopeId, int revision, String sourceSha256, String status,
        Instant expiresAt) {
    }

    record AuditEvent(String event, Instant at) {
    }

    record Binding(String scopeId, int revision, String sourceSha256) {
    }

    static final class Failure extends RuntimeException {

        final int status;

        Failure(int status, String detail) {
            super(detail);
            this.status = status;
        }
    }

    private record StoredOffer(Actor desktop, Instant expiresAt) {
    }

    private record Attempts(int count, Instant until) {
    }

    private record AuditTrail(ArrayDeque<AuditEvent> items, Instant until) {
    }

    private record StoredTask(String id, Binding binding, String status, Instant expiresAt) {

        StoredTask withStatus(String next) {
            return new StoredTask(id, binding, next, expiresAt);
        }
    }

    private static final class StoredPair {

        final String id;
        final Actor desktop;
        final Actor mobile;
        Instant expiresAt;
        Instant hostSeenAt;
        boolean active;
        StoredTask task;

        StoredPair(String id, Actor desktop, Actor mobile, Instant expiresAt) {
            this.id = id;
            this.desktop = desktop;
            this.mobile = mobile;
            this.expiresAt = expiresAt;
        }

        boolean belongsTo(Actor actor) {
            return desktop.equals(actor) || mobile.equals(actor);
        }
    }
}
