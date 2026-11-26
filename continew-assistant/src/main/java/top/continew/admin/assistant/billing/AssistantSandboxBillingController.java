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

import cn.dev33.satoken.annotation.SaIgnore;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.feiniaojin.gracefulresponse.api.ExcludeFromGracefulResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.billing.AssistantSandboxBilling.Entitlement;
import top.continew.admin.assistant.billing.AssistantSandboxBilling.Notification;
import top.continew.admin.assistant.billing.AssistantSandboxBilling.Order;
import top.continew.admin.assistant.billing.AssistantSandboxBilling.Receipt;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Exposes the signed local-test merchant callback and consumer sandbox orders.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@RequestMapping("/api/assistant/v1/billing/sandbox")
@Profile("(dev | test) & !prod")
@ConditionalOnProperty(name = {"assistant.enabled", "assistant.billing.sandbox-enabled"},
    havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantSandboxBillingController {

    private static final String WORKSPACE = "X-Workspace-Id";
    private static final String SIGNATURE = "X-Assistant-Sandbox-Signature";
    private static final String SIGNED_AT = "X-Assistant-Sandbox-Signed-At";
    private static final String HMAC = "HmacSHA256";
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern EPOCH = Pattern.compile("[0-9]{13}");
    private static final Pattern EVENT_ID = Pattern.compile("[a-zA-Z0-9._~-]{1,128}");
    private static final Set<String> STATES = Set.of("active", "expired", "refunded");
    private static final int MAX_BYTES = 4096;
    private static final long CLOCK_TOLERANCE = Duration.ofMinutes(5).toMillis();
    private static final long MAX_PERIOD = Duration.ofDays(367).toMillis();
    private static final long MAX_REVISION = 9_007_199_254_740_991L;

    private final AssistantAccounts accounts;
    private final AssistantSandboxBilling billing;
    private final ObjectReader reader;
    private final SecretKeySpec key;

    /**
     * Creates the sandbox callback with an explicitly configured signing key.
     *
     * @param accounts consumer authorization
     * @param billing checkout and ledger reconciliation
     * @param mapper application JSON mapper
     * @param secret 256-bit hexadecimal sandbox signing key
     */
    public AssistantSandboxBillingController(AssistantAccounts accounts,
        AssistantSandboxBilling billing, ObjectMapper mapper,
        @Value("${assistant.billing.sandbox-secret:${ASSISTANT_BILLING_SANDBOX_SECRET:}}") String secret) {
        if (!HEX.matcher(secret).matches()) {
            throw new IllegalStateException(
                "The billing sandbox requires a 256-bit hex signing key.");
        }
        this.accounts = accounts;
        this.billing = billing;
        this.reader = mapper.readerFor(Notification.class)
            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        this.key = new SecretKeySpec(HexFormat.of().parseHex(secret), HMAC);
    }

    /**
     * Opens or resumes an idempotent synthetic checkout.
     *
     * @param authorization consumer session header
     * @param workspace selected workspace
     * @param idempotency creation key
     * @return scoped checkout
     */
    @PostMapping("/checkout")
    public ResponseEntity<Order> checkout(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotency) {
        return ok(billing.checkout(accounts.authenticate(authorization, workspace), idempotency));
    }

    /**
     * Restores the existing checkout status on another authorized device.
     *
     * @param authorization consumer session header
     * @param workspace selected workspace
     * @param orderId previously created order
     * @return current order status
     */
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<Order> restore(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable("orderId") String orderId) {
        return ok(billing.restore(accounts.authenticate(authorization, workspace), orderId));
    }

    /**
     * Reads current sandbox access in the selected workspace.
     *
     * @param authorization consumer session header
     * @param workspace selected workspace
     * @return current synthetic entitlement
     */
    @GetMapping("/entitlement")
    public ResponseEntity<Entitlement> entitlement(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace) {
        return ok(billing.entitlement(accounts.authenticate(authorization, workspace)));
    }

    /**
     * Verifies the exact bytes of a sandbox merchant notification before decoding it.
     *
     * @param signedAt authentication timestamp in epoch milliseconds
     * @param signature hex HMAC of the timestamp and body
     * @param request bounded raw HTTP body
     * @return reconciliation receipt
     */
    @PostMapping(value = "/notifications", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Receipt> notification(
        @RequestHeader(value = SIGNED_AT, required = false) String signedAt,
        @RequestHeader(value = SIGNATURE, required = false) String signature,
        HttpServletRequest request) {
        if (signedAt == null || !EPOCH.matcher(signedAt).matches()
            || signature == null || !HEX.matcher(signature).matches()) {
            throw unauthenticated();
        }
        long timestamp = Long.parseLong(signedAt);
        long now = System.currentTimeMillis();
        if (timestamp < now - CLOCK_TOLERANCE || timestamp > now + CLOCK_TOLERANCE) {
            throw unauthenticated();
        }

        byte[] body;
        try {
            body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        } catch (IOException e) {
            throw invalid();
        }
        if (body.length > MAX_BYTES) {
            throw new AssistantApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                "The sandbox notification is too large.");
        }
        if (!MessageDigest.isEqual(HexFormat.of().parseHex(signature), hmac(signedAt, body))) {
            throw unauthenticated();
        }
        Notification event;
        try {
            event = reader.readValue(body);
        } catch (IOException e) {
            throw invalid();
        }
        validate(event, now);
        return ok(billing.reconcile(event, AssistantSandboxBilling.digest(body)));
    }

    private static void validate(Notification event, long now) {
        if (event == null || event.eventId() == null || !EVENT_ID.matcher(event.eventId()).matches()
            || !isOrderId(event.orderId()) || event.state() == null
            || !STATES.contains(event.state())
            || event.revision() < 1 || event.revision() > MAX_REVISION
            || event.occurredAt() < 1 || event.occurredAt() > now + CLOCK_TOLERANCE) {
            throw invalid();
        }
        Long end = event.entitledUntil();
        if ("active".equals(event.state())) {
            if (end == null || end <= event.occurredAt() || end - event.occurredAt() > MAX_PERIOD) {
                throw invalid();
            }
        } else if (end != null) {
            throw invalid();
        }
    }

    private static boolean isOrderId(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private byte[] hmac(String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The sandbox signature check is unavailable.", e);
        }
    }

    private static AssistantApiException unauthenticated() {
        return new AssistantApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
            "The sandbox notification signature is invalid.");
    }

    private static AssistantApiException invalid() {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "The sandbox notification is invalid.");
    }

    /**
     * Maps ledger and authorization errors to the consumer error envelope.
     *
     * @param error bounded public error
     * @return response without caching
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> handle(AssistantApiException error) {
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore())
            .body(new ApiError(error.code(), error.getMessage()));
    }

    /**
     * Maps a concurrent delivery-key collision to an HTTP conflict.
     *
     * @return conflict response
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiError> duplicate() {
        return handle(new AssistantApiException(HttpStatus.CONFLICT, "CONFLICT",
            "The sandbox delivery has already been assigned."));
    }

    /**
     * Suppresses database details in an unsuccessful checkout.
     *
     * @return safe service error
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> storage() {
        return handle(new AssistantApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "The sandbox ledger is temporarily unavailable."));
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
