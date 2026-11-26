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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import top.continew.admin.assistant.account.AssistantApiException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Verifies signed machine credentials for one deployment-pinned directory.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Component
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
class AssistantDirectoryTokens {

    private static final Pattern ATOM = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Pattern COMPACT =
        Pattern.compile(
            "Bearer ([A-Za-z0-9_-]{1,512})\\.([A-Za-z0-9_-]{1,3072})\\.([A-Za-z0-9_-]{1,683})");
    private static final long MAX_TOKEN_SECONDS = 300;
    private final ObjectReader reader;
    private final Trust trust;
    private final Clock clock;

    @Autowired
    AssistantDirectoryTokens(ObjectMapper json,
        @Value("${assistant.organization-directory.issuer:}") String issuer,
        @Value("${assistant.organization-directory.audience:}") String audience,
        @Value("${assistant.organization-directory.client-id:}") String client,
        @Value("${assistant.organization-directory.service-subject:}") String subject,
        @Value("${assistant.organization-directory.key-id:}") String keyId,
        @Value("${assistant.organization-directory.public-key:}") String pin) {
        this(json, issuer, audience, client, subject, keyId, pin, Clock.systemUTC());
    }

    AssistantDirectoryTokens(ObjectMapper json, String issuer, String audience, String client,
        String subject, String keyId, String pin, Clock clock) {
        this.reader = json.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.trust = configure(issuer, audience, client, subject, keyId, pin);
        this.clock = clock;
    }

    boolean available() {
        return trust != null;
    }

    boolean trustsIssuer(String issuer) {
        return trust != null && trust.issuer().equals(issuer);
    }

    String issuerFingerprint() {
        return AssistantPinnedJwt.issuerFingerprint(trust.issuer());
    }

    void verify(String authorization) {
        if (authorization == null || authorization.length() > 4_280 || trust == null) {
            throw unauthorized();
        }
        var match = COMPACT.matcher(authorization);
        if (!match.matches()) {
            throw unauthorized();
        }
        var decoder = Base64.getUrlDecoder();
        try {
            JsonNode header = reader.readTree(decoder.decode(match.group(1)));
            if (header == null || !header.isObject() || !"RS256".equals(text(header, "alg"))
                || !trust.keyId().equals(text(header, "kid"))
                || !"JWT".equals(text(header, "typ")) || header.size() != 3) {
                throw unauthorized();
            }
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(trust.key());
            signature.update((match.group(1) + "." + match.group(2))
                .getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(decoder.decode(match.group(3)))) {
                throw unauthorized();
            }
            JsonNode value = reader.readTree(decoder.decode(match.group(2)));
            if (value == null || !value.isObject() || !validClaims(value)) {
                throw unauthorized();
            }
        } catch (IOException | GeneralSecurityException | IllegalArgumentException failure) {
            throw unauthorized();
        }
    }

    private boolean validClaims(JsonNode value) {
        long now = clock.instant().getEpochSecond();
        long issued = seconds(value, "iat");
        long expires = seconds(value, "exp");
        JsonNode audience = value.path("aud");
        boolean exactAudience =
            audience.isTextual() && trust.audience().equals(audience.textValue())
                || audience.isArray() && audience.size() == 1 && audience.path(0).isTextual()
                    && trust.audience().equals(audience.path(0).textValue());
        return trust.issuer().equals(text(value, "iss")) && exactAudience
            && trust.client().equals(text(value, "azp"))
            && trust.subject().equals(text(value, "sub"))
            && "Bearer".equals(text(value, "typ")) && "assistant.scim".equals(text(value, "scope"))
            && issued > 0 && issued <= now + 15 && expires > now && expires > issued
            && expires - issued <= MAX_TOKEN_SECONDS && (!value.has("nbf")
                || seconds(value, "nbf") > 0 && seconds(value, "nbf") <= now);
    }

    private static Trust configure(String issuer, String audience, String client, String subject,
        String keyId, String pin) {
        if (issuer.length() > 250 || pin.length() > 2_048
            || List.of(audience, client, subject, keyId)
                .stream().anyMatch(input -> !ATOM.matcher(input).matches())) {
            return null;
        }
        try {
            URI uri = URI.create(issuer);
            boolean trustedAddress =
                "https".equals(uri.getScheme()) || "http".equals(uri.getScheme())
                    && List.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost());
            if (!trustedAddress || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getFragment() != null) {
                return null;
            }
            var encoded = new X509EncodedKeySpec(Base64.getDecoder().decode(pin));
            var key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(encoded);
            int bits = key.getModulus().bitLength();
            return bits >= 2048 && bits <= 4096
                ? new Trust(issuer, audience, client, subject, keyId, key)
                : null;
        } catch (IllegalArgumentException | GeneralSecurityException failure) {
            return null;
        }
    }

    private static String text(JsonNode value, String property) {
        return value.path(property).textValue();
    }

    private static long seconds(JsonNode value, String property) {
        JsonNode claim = value.path(property);
        return claim.isIntegralNumber() && claim.canConvertToLong() ? claim.longValue() : -1;
    }

    private static AssistantApiException unauthorized() {
        return new AssistantApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
            "A current directory service credential is required.");
    }

    private record Trust(String issuer, String audience, String client, String subject,
        String keyId,
        RSAPublicKey key) {
    }
}
