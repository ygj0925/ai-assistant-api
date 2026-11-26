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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

final class AssistantPinnedJwt {

    private static final Pattern COMPACT =
        Pattern
            .compile("([A-Za-z0-9_-]{1,512})\\.([A-Za-z0-9_-]{1,3072})\\.([A-Za-z0-9_-]{1,683})");

    private AssistantPinnedJwt() {
    }

    static ObjectReader objectReader(ObjectMapper json) {
        return json.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    static RSAPublicKey rsaPin(String pin) throws GeneralSecurityException {
        if (pin.length() > 2_048) {
            throw new GeneralSecurityException("Invalid RSA public key size.");
        }
        var encoded = new X509EncodedKeySpec(Base64.getDecoder().decode(pin));
        var key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(encoded);
        int bits = key.getModulus().bitLength();
        if (bits < 2048 || bits > 4096) {
            throw new GeneralSecurityException("Invalid RSA modulus size.");
        }
        return key;
    }

    static JsonNode verify(ObjectReader json, String compact, String keyId, RSAPublicKey key)
        throws IOException, GeneralSecurityException {
        if (compact == null || compact.length() > 4_269) {
            throw invalid();
        }
        var match = COMPACT.matcher(compact);
        if (!match.matches()) {
            throw invalid();
        }
        var decoder = Base64.getUrlDecoder();
        JsonNode header = object(json.readTree(decoder.decode(match.group(1))));
        if (!"RS256".equals(text(header, "alg")) || !keyId.equals(text(header, "kid"))
            || !"JWT".equals(text(header, "typ")) || header.size() != 3) {
            throw invalid();
        }
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(key);
        signature
            .update((match.group(1) + "." + match.group(2)).getBytes(StandardCharsets.US_ASCII));
        if (!signature.verify(decoder.decode(match.group(3)))) {
            throw invalid();
        }
        return object(json.readTree(decoder.decode(match.group(2))));
    }

    static boolean audienceMatches(JsonNode value, String expected) {
        JsonNode audience = value.path("aud");
        return audience.isTextual() && expected.equals(audience.textValue())
            || audience.isArray() && audience.size() == 1 && audience.path(0).isTextual()
                && expected.equals(audience.path(0).textValue());
    }

    static String text(JsonNode value, String property) {
        return value.path(property).textValue();
    }

    static long seconds(JsonNode value, String property) {
        JsonNode claim = value.path(property);
        return claim.isIntegralNumber() && claim.canConvertToLong() ? claim.longValue() : -1;
    }

    static String issuerFingerprint(String issuer) {
        try {
            byte[] input = issuer.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable.", failure);
        }
    }

    private static JsonNode object(JsonNode input) throws GeneralSecurityException {
        if (input == null || !input.isObject()) {
            throw invalid();
        }
        return input;
    }

    private static GeneralSecurityException invalid() {
        return new GeneralSecurityException("Unrecognized signed token.");
    }
}
