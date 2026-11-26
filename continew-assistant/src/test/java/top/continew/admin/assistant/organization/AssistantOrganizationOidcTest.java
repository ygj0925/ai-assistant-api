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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import top.continew.admin.assistant.account.AssistantApiException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

class AssistantOrganizationOidcTest {

    private static final String ISSUER = "http://127.0.0.1:18169/realms/assistant-oidc-qa";
    private static final String AUTHORIZE = ISSUER + "/protocol/openid-connect/auth";
    private static final String EXCHANGE = ISSUER + "/protocol/openid-connect/token";
    private static final String CALLBACK = "http://127.0.0.1:3100/organization-sso/callback";
    private static final String SUBJECT = "936174ad-1cfd-44db-919d-a55663f911e8";
    private static final String CLIENT = "synthetic-org-browser";
    private static final String NONCE = "one-verified-nonce";
    private static final long NOW = Instant.parse("2026-11-24T11:00:00Z").getEpochSecond();
    private static final String HEAD = "{\"alg\":\"RS256\",\"kid\":\"qa-key\",\"typ\":\"JWT\"}";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static KeyPair key;
    private static AssistantOrganizationOidc oidc;

    @BeforeAll
    static void fixture() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        key = generator.generateKeyPair();
        oidc = provider(EXCHANGE, CALLBACK, true);
    }

    @Test
    void requiresThePinnedAuthorizationCodeConfiguration() {
        assertTrue(oidc.available());
        assertFalse(provider(EXCHANGE, CALLBACK, false).available());
        assertFalse(provider(EXCHANGE + "?redirect=true", CALLBACK, true).available());
        assertFalse(provider("https://other.example.invalid/token", CALLBACK, true).available());
        assertFalse(provider(EXCHANGE, CALLBACK + "/extra", true).available());
        var url = URI.create(oidc.authorize("random-state", NONCE, "s256-challenge", true));
        assertEquals("/realms/assistant-oidc-qa/protocol/openid-connect/auth", url.getPath());
        assertTrue(url.getRawQuery().contains("response_type=code"));
        assertTrue(url.getRawQuery().contains("code_challenge_method=S256"));
        assertTrue(url.getRawQuery().contains("prompt=login"));
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(AssistantApiException.class,
            () -> oidc.exchange("local-code", ISSUER + "/other", "verifier", NONCE)).status());
    }

    @Test
    void validatesOnlyTheCurrentIdTokenAndBrowserNonce() throws Exception {
        assertEquals(SUBJECT, oidc.verifyIdentity(sign(HEAD, claims()), NONCE, NOW));
        rejects(sign(HEAD, claims().put("typ", "Bearer")), NONCE);
        rejects(sign(HEAD, claims().put("iss", ISSUER + "-other")), NONCE);
        rejects(sign(HEAD, claims().put("aud", "different-client")), NONCE);
        rejects(sign(HEAD, claims().put("azp", "different-client")), NONCE);
        rejects(sign(HEAD, claims().put("exp", NOW)), NONCE);
        rejects(sign(HEAD, claims().put("sub", "same-email@example.invalid")), NONCE);
        rejects(sign(HEAD.replace("qa-key", "other-key"), claims()), NONCE);
        rejects(sign(HEAD.replace("RS256", "HS256"), claims()), NONCE);
        rejects(sign(HEAD, claims()), "wrong-browser-nonce");
        var audience = claims();
        audience.putArray("aud").add(CLIENT).add("different-client");
        rejects(sign(HEAD, audience), NONCE);
    }

    private static AssistantOrganizationOidc provider(String exchange, String callback,
        boolean allow) {
        String pin = Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
        return new AssistantOrganizationOidc(MAPPER, ISSUER, AUTHORIZE, exchange, callback, CLIENT,
            "isolated-client-fixture-only", "qa-key", pin, allow);
    }

    private static ObjectNode claims() {
        return MAPPER.createObjectNode().put("iss", ISSUER).put("aud", CLIENT).put("azp", CLIENT)
            .put("sub", SUBJECT).put("typ", "ID").put("nonce", NONCE)
            .put("iat", NOW - 20).put("exp", NOW + 100);
    }

    private static String sign(String header, ObjectNode claims) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String pair = encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
            + encoder.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key.getPrivate());
        signature.update(pair.getBytes(StandardCharsets.US_ASCII));
        return pair + "." + encoder.encodeToString(signature.sign());
    }

    private static void rejects(String token, String nonce) {
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(AssistantApiException.class,
            () -> oidc.verifyIdentity(token, nonce, NOW)).status());
    }
}
