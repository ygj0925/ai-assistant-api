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

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

class AssistantDirectoryTokensTest {

    private static final Instant NOW = Instant.parse("2026-11-24T08:00:00Z");
    private static final String ISSUER = "http://127.0.0.1:18169/realms/assistant-directory-qa";
    private static final String AUDIENCE = "assistant-scim";
    private static final String KEY_ID = "fixture-key";
    private static final String CLIENT = "fixture-client";
    private static final String SUBJECT = "fixture-subject";
    private static final String HEADER =
        "{\"typ\":\"JWT\",\"alg\":\"RS256\",\"kid\":\"fixture-key\"}";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static KeyPair signing;
    private static KeyPair unrelated;
    private static AssistantDirectoryTokens verifier;

    @BeforeAll
    static void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        signing = generator.generateKeyPair();
        unrelated = generator.generateKeyPair();
        verifier = new AssistantDirectoryTokens(MAPPER, ISSUER, AUDIENCE, CLIENT, SUBJECT, KEY_ID,
            publicKey(), CLOCK);
    }

    @Test
    void verifiesOnlyThePinnedMachinePurpose() throws Exception {
        assertTrue(verifier.available());
        verifier.verify(signed(HEADER, claims().toString(), signing));
        rejects(signed(HEADER, claims().put("azp", "other-client").toString(), signing));
        rejects(signed(HEADER, claims().put("sub", "ordinary-consumer").toString(), signing));
        rejects(signed(HEADER, claims().put("scope", "assistant.other").toString(), signing));
        rejects(signed(HEADER, claims().put("typ", "ID").toString(), signing));
        rejects(signed(HEADER, claims().put("iss", ISSUER + "/other").toString(), signing));
        rejects(signed(HEADER, claims().put("aud", "assistant-other").toString(), signing));
        var multiple = claims();
        multiple.putArray("aud").add(AUDIENCE).add("assistant-other");
        rejects(signed(HEADER, multiple.toString(), signing));
    }

    @Test
    void rejectsWrongKeyAlgorithmsClaimsAndExpiry() throws Exception {
        rejects(signed(HEADER, claims().toString(), unrelated));
        rejects(signed(HEADER.replace("RS256", "HS256"), claims().toString(), signing));
        rejects(signed(HEADER.replace("fixture-key", "foreign-key"), claims().toString(), signing));
        rejects(signed(HEADER, claims().put("exp", NOW.getEpochSecond()).toString(), signing));
        rejects(signed(HEADER,
            claims().put("exp", NOW.plusSeconds(302).getEpochSecond()).toString(), signing));
        rejects(signed(HEADER, claims().put("nbf", NOW.plusSeconds(30).getEpochSecond()).toString(),
            signing));
        rejects(signed(HEADER, "{\"scope\":\"assistant.other\"," + claims().toString().substring(1),
            signing));
        rejects("Bearer as_test_consumer_token");
        var noTrust =
            new AssistantDirectoryTokens(MAPPER, "http://untrusted.example.invalid", AUDIENCE,
                CLIENT, SUBJECT, KEY_ID, publicKey(), CLOCK);
        assertFalse(noTrust.available());
    }

    private static String publicKey() {
        return Base64.getEncoder().encodeToString(signing.getPublic().getEncoded());
    }

    private static ObjectNode claims() {
        return MAPPER.createObjectNode().put("azp", CLIENT).put("sub", SUBJECT).put("iss", ISSUER)
            .put("aud", AUDIENCE).put("scope", "assistant.scim").put("typ", "Bearer")
            .put("iat", NOW.getEpochSecond()).put("exp", NOW.plusSeconds(120).getEpochSecond());
    }

    private static String signed(String header, String claims, KeyPair pair) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String input = encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
            + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        var signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(pair.getPrivate());
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return "Bearer " + input + "." + encoder.encodeToString(signature.sign());
    }

    private static void rejects(String value) {
        var thrown = assertThrows(AssistantApiException.class, () -> verifier.verify(value));
        assertEquals(HttpStatus.UNAUTHORIZED, thrown.status());
    }
}
