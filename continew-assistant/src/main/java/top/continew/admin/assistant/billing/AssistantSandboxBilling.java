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

package top.continew.admin.assistant.billing;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.assistant.account.AssistantAccounts.Principal;
import top.continew.admin.assistant.account.AssistantApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Records local test subscriptions from verified channel notifications.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@Profile("(dev | test) & !prod")
@ConditionalOnProperty(name = {"assistant.enabled", "assistant.billing.sandbox-enabled"},
    havingValue = "true")
public class AssistantSandboxBilling {

    private static final String CHANNEL = "local-test";
    private static final String PRODUCT = "assistant-local-plus";
    private static final String PENDING = "pending";
    private static final String ACTIVE = "active";
    private static final String EXPIRED = "expired";
    private static final String REFUNDED = "refunded";
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._~-]{1,128}");
    private static final String ORDER_COLUMNS = """
        SELECT id, product_id, state, provider_revision, entitled_until
        FROM assistant_subscription_order
        """;
    private static final RowMapper<Order> ORDER = (row, index) -> new Order(row.getString("id"),
        row.getString("product_id"), row.getString("state"), row.getLong("provider_revision"),
        row.getObject("entitled_until", Long.class));

    private final JdbcTemplate jdbc;

    /**
     * Constructs the sandbox ledger.
     *
     * @param jdbc assistant data access
     */
    public AssistantSandboxBilling(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates or retrieves a pending checkout scoped to the authorized consumer.
     *
     * @param principal authenticated consumer and selected workspace
     * @param key caller's idempotency key
     * @return pending or reconciled checkout
     */
    @Transactional
    public Order checkout(Principal principal, String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw failure(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "A valid Idempotency-Key is required.");
        }
        String account = principal.user().id();
        String workspace = principal.user().workspaceId();
        // Serialize duplicate checkout keys and account erasure against this account.
        if (jdbc.queryForList("SELECT id FROM assistant_account WHERE id = ? FOR UPDATE", account)
            .isEmpty() || jdbc.queryForList("""
                SELECT workspace_id FROM assistant_workspace_member
                WHERE workspace_id = ? AND account_id = ?
                """, workspace, account).isEmpty()) {
            throw failure(HttpStatus.FORBIDDEN, "FORBIDDEN", "The workspace is unavailable.");
        }
        String keyHash = digest(key.getBytes(StandardCharsets.US_ASCII));
        var existing = jdbc.query(ORDER_COLUMNS + """
            WHERE channel = ? AND account_id = ? AND idempotency_key = ?
            """, ORDER, CHANNEL, account, keyHash).stream().findFirst();
        if (existing.isPresent()) {
            Order order = existing.orElseThrow();
            return restore(principal, order.orderId());
        }
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update(
            """
                INSERT INTO assistant_subscription_order
                    (id, workspace_id, account_id, channel, idempotency_key, product_id, state, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            id, workspace, account, CHANNEL, keyHash, PRODUCT, PENDING, now, now);
        return new Order(id, PRODUCT, PENDING, 0, null);
    }

    /**
     * Restores the latest known status of the consumer's checkout.
     *
     * @param principal authenticated consumer and selected workspace
     * @param orderId previously created checkout
     * @return current order status
     */
    @Transactional(readOnly = true)
    public Order restore(Principal principal, String orderId) {
        return jdbc.query(ORDER_COLUMNS + """
            WHERE id = ? AND channel = ? AND account_id = ? AND workspace_id = ?
            """, ORDER, orderId, CHANNEL, principal.user().id(), principal.user().workspaceId())
            .stream().findFirst().orElseThrow(AssistantSandboxBilling::notFound)
            .effective(System.currentTimeMillis());
    }

    /**
     * Returns the active sandbox entitlement in the selected workspace.
     *
     * @param principal authenticated consumer and selected workspace
     * @return current synthetic entitlement and its expiration
     */
    @Transactional(readOnly = true)
    public Entitlement entitlement(Principal principal) {
        Long until = jdbc.queryForObject(
            """
                SELECT MAX(entitled_until) FROM assistant_subscription_order
                WHERE channel = ? AND workspace_id = ? AND account_id = ? AND state = ? AND entitled_until > ?
                """,
            Long.class, CHANNEL, principal.user().workspaceId(), principal.user().id(),
            ACTIVE, System.currentTimeMillis());
        return new Entitlement(CHANNEL, PRODUCT, until != null, until);
    }

    /**
     * Commits an already authenticated channel snapshot and its deduplication record together.
     *
     * @param notification verified channel snapshot
     * @param payloadDigest digest of the exact accepted body
     * @return current order and how this delivery was handled
     */
    @Transactional
    public Receipt reconcile(Notification notification, String payloadDigest) {
        long now = System.currentTimeMillis();
        Order order = jdbc.query(ORDER_COLUMNS + """
            WHERE id = ? AND channel = ? FOR UPDATE
            """, ORDER, notification.orderId(), CHANNEL).stream().findFirst()
            .orElseThrow(AssistantSandboxBilling::notFound);
        String delivery = digest(notification.eventId().getBytes(StandardCharsets.US_ASCII));
        Optional<RecordedDelivery> prior = jdbc.query("""
            SELECT order_id, payload_digest, processing_state
            FROM assistant_subscription_event WHERE channel = ? AND delivery_digest = ?
            """, (row, index) -> new RecordedDelivery(row.getString("order_id"),
            row.getString("payload_digest"), row.getString("processing_state")), CHANNEL, delivery)
            .stream().findFirst();
        if (prior.isPresent()) {
            RecordedDelivery saved = prior.orElseThrow();
            if (!order.orderId().equals(saved.orderId()) || !payloadDigest.equals(saved.digest())) {
                throw conflict();
            }
            return new Receipt(saved.processingState(), true, order.effective(now));
        }

        boolean apply = shouldApply(order, notification);
        String processing = apply ? "applied" : "ignored";
        jdbc.update(
            """
                INSERT INTO assistant_subscription_event
                    (id, channel, delivery_digest, payload_digest, order_id, event_type,
                     occurred_at, received_at, processing_state, processed_at, provider_revision, entitled_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            UUID.randomUUID().toString(), CHANNEL, delivery, payloadDigest, order.orderId(),
            notification.state(), notification.occurredAt(), now, processing, now,
            notification.revision(), notification.entitledUntil());

        if (apply) {
            jdbc.update("""
                UPDATE assistant_subscription_order
                SET state = ?, provider_revision = ?, entitled_until = ?, updated_at = ?
                WHERE id = ? AND channel = ?
                """, notification.state(), notification.revision(), notification.entitledUntil(),
                now, order.orderId(), CHANNEL);
            order = new Order(order.orderId(), order.productId(), notification.state(),
                notification.revision(), notification.entitledUntil());
        }
        return new Receipt(processing, false, order.effective(now));
    }

    private static boolean shouldApply(Order order, Notification notification) {
        if (notification.revision() == order.revision()
            && (!order.state().equals(notification.state())
                || !Objects.equals(order.entitledUntil(), notification.entitledUntil()))) {
            throw conflict();
        }
        return notification.revision() > order.revision()
            && (!REFUNDED.equals(order.state()) || REFUNDED.equals(notification.state()));
    }

    static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static AssistantApiException notFound() {
        return failure(HttpStatus.NOT_FOUND, "NOT_FOUND", "The sandbox order is unavailable.");
    }

    private static AssistantApiException conflict() {
        return failure(HttpStatus.CONFLICT, "CONFLICT",
            "The notification conflicts with a recorded delivery.");
    }

    private static AssistantApiException failure(HttpStatus status, String code, String detail) {
        return new AssistantApiException(status, code, detail);
    }

    /**
     * Signed authoritative subscription snapshot from the sandbox merchant.
     *
     * @param eventId unique delivery ID
     * @param orderId checkout ID
     * @param revision merchant snapshot revision
     * @param state authoritative subscription state
     * @param occurredAt merchant event time in epoch milliseconds
     * @param entitledUntil optional period end in epoch milliseconds
     */
    public record Notification(String eventId, String orderId, long revision, String state,
        long occurredAt, Long entitledUntil) {
    }

    /**
     * Visible checkout status.
     *
     * @param orderId checkout ID
     * @param productId sandbox product
     * @param state current subscription state
     * @param revision most recent applied merchant revision
     * @param entitledUntil active period end in epoch milliseconds
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Order(String orderId, String productId, String state, long revision,
        Long entitledUntil) {

        private Order effective(long now) {
            return ACTIVE.equals(state) && entitledUntil != null && entitledUntil <= now
                ? new Order(orderId, productId, EXPIRED, revision, null) : this;
        }
    }

    /**
     * Workspace-scoped sandbox access.
     *
     * @param channel synthetic channel ID
     * @param productId sandbox product
     * @param active whether any order grants access
     * @param entitledUntil latest active period end
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entitlement(String channel, String productId, boolean active,
        Long entitledUntil) {
    }

    /**
     * Idempotent delivery processing result.
     *
     * @param processingState applied or ignored
     * @param duplicate whether an identical delivery had already been recorded
     * @param order current checkout status
     */
    public record Receipt(String processingState, boolean duplicate, Order order) {
    }

    private record RecordedDelivery(String orderId, String digest, String processingState) {
    }
}
