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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import top.continew.admin.assistant.account.AssistantApiException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Exchanges a one-use OIDC code with explicitly pinned endpoints and signing authority.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Component
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
class AssistantOrganizationOidc {

    private static final Pattern ATOM = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.~-]{1,1024}");
    private static final Duration DEADLINE = Duration.ofSeconds(5);
    private static final int MAX_RESPONSE_BYTES = 32_768;
    private final ObjectReader json;
    private final HttpClient client;
    private final Trust trust;

    AssistantOrganizationOidc(ObjectMapper json,
        @Value("${assistant.organization-sso.issuer:}") String issuer,
        @Value("${assistant.organization-sso.authorization-endpoint:}") String authorization,
        @Value("${assistant.organization-sso.token-endpoint:}") String token,
        @Value("${assistant.organization-sso.redirect-uri:}") String redirect,
        @Value("${assistant.organization-sso.client-id:}") String clientId,
        @Value("${assistant.organization-sso.client-secret:}") String secret,
        @Value("${assistant.organization-sso.key-id:}") String keyId,
        @Value("${assistant.organization-sso.public-key:}") String pin,
        @Value("${assistant.organization-sso.allow-loopback-http:false}") boolean allowLoopback) {
        this.json = AssistantPinnedJwt.objectReader(json);
        this.trust = configure(issuer, authorization, token, redirect, clientId, secret, keyId, pin,
            allowLoopback);
        this.client = HttpClient.newBuilder().connectTimeout(DEADLINE)
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1)
            .build();
    }

    boolean available() {
        return trust != null;
    }

    String issuer() {
        return trust == null ? "" : trust.issuer();
    }

    String authorize(String state, String nonce, String challenge, boolean link) {
        return trust.authorization() + "?response_type=code&response_mode=query&scope=openid"
            + "&client_id=" + form(trust.clientId()) + "&redirect_uri=" + form(trust.redirect())
            + "&state=" + form(state) + "&nonce=" + form(nonce)
            + "&code_challenge=" + form(challenge) + "&code_challenge_method=S256"
            + (link ? "&prompt=login" : "");
    }

    String exchange(String code, String responseIssuer, String verifier, String nonce) {
        if (!supportsResponseIssuer(responseIssuer) || code == null
            || !CODE.matcher(code).matches()) {
            throw denied();
        }
        String credentials = Base64.getEncoder().encodeToString(
            (form(trust.clientId()) + ":" + form(trust.secret()))
                .getBytes(StandardCharsets.US_ASCII));
        byte[] body = ("grant_type=authorization_code&code=" + form(code)
            + "&redirect_uri=" + form(trust.redirect()) + "&code_verifier=" + form(verifier))
            .getBytes(StandardCharsets.US_ASCII);
        var request = HttpRequest.newBuilder(trust.token()).timeout(DEADLINE)
            .header("Authorization", "Basic " + credentials).header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var output = new ByteArrayOutputStream();
        var pending =
            client.sendAsync(request, info -> HttpResponse.BodySubscribers.ofByteArrayConsumer(
                part -> part.ifPresent(fragment -> {
                    if (fragment.length > MAX_RESPONSE_BYTES - output.size()) {
                        throw unavailable();
                    }
                    output.writeBytes(fragment);
                })));
        HttpResponse<Void> response;
        try {
            response = pending.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException | TimeoutException failure) {
            pending.cancel(true);
            throw unavailable();
        }
        if (response.statusCode() == 400 || response.statusCode() == 401) {
            throw denied();
        }
        String type =
            response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (response.statusCode() != 200
            || !(type.equals("application/json") || type.startsWith("application/json;"))
            || response.headers().firstValue("Content-Encoding").isPresent()) {
            throw unavailable();
        }
        try {
            JsonNode result = json.readTree(output.toByteArray());
            if (result == null || !result.isObject()) {
                throw denied();
            }
            return verifyIdentity(AssistantPinnedJwt.text(result, "id_token"), nonce,
                Instant.now().getEpochSecond());
        } catch (IOException failure) {
            throw denied();
        }
    }

    boolean supportsResponseIssuer(String issuer) {
        return trust != null && trust.issuer().equals(issuer);
    }

    String verifyIdentity(String signed, String nonce, long now) {
        try {
            JsonNode identity = AssistantPinnedJwt.verify(json, signed, trust.keyId(), trust.key());
            if (!trust.issuer().equals(AssistantPinnedJwt.text(identity, "iss"))
                || !AssistantPinnedJwt.audienceMatches(identity, trust.clientId())
                || identity.has("azp")
                    && !trust.clientId().equals(AssistantPinnedJwt.text(identity, "azp"))
                || !"ID".equals(AssistantPinnedJwt.text(identity, "typ"))
                || !nonce.equals(AssistantPinnedJwt.text(identity, "nonce"))
                || !validTimes(identity, now)) {
                throw denied();
            }
            String subject = AssistantPinnedJwt.text(identity, "sub");
            if (!AssistantOrganizationDirectory.validId(subject)) {
                throw denied();
            }
            return subject;
        } catch (IOException | GeneralSecurityException | IllegalArgumentException failure) {
            throw denied();
        }
    }

    private static boolean validTimes(JsonNode identity, long now) {
        long issued = AssistantPinnedJwt.seconds(identity, "iat");
        long expires = AssistantPinnedJwt.seconds(identity, "exp");
        return issued >= now - 300 && issued <= now + 15 && expires > now && expires > issued
            && expires - issued <= 300 && (!identity.has("nbf")
                || AssistantPinnedJwt.seconds(identity, "nbf") > 0
                    && AssistantPinnedJwt.seconds(identity, "nbf") <= now);
    }

    private static Trust configure(String issuer, String authorization, String token,
        String redirect,
        String clientId, String secret, String keyId, String pin, boolean allowLoopback) {
        if (!ATOM.matcher(clientId).matches() || !ATOM.matcher(keyId).matches()
            || secret.isBlank() || secret.length() > 256
            || List.of(issuer, authorization, token, redirect).stream()
                .anyMatch(value -> value.length() > 512)) {
            return null;
        }
        try {
            URI issuerUri = URI.create(issuer);
            URI authUri = URI.create(authorization);
            URI tokenUri = URI.create(token);
            URI redirectUri = URI.create(redirect);
            if (!safe(issuerUri, allowLoopback) || !safe(authUri, allowLoopback)
                || !safe(tokenUri, allowLoopback) || !safe(redirectUri, allowLoopback)
                || !sameOrigin(issuerUri, authUri) || !sameOrigin(issuerUri, tokenUri)
                || authUri.equals(tokenUri)
                || !"/organization-sso/callback".equals(redirectUri.getRawPath())) {
                return null;
            }
            return new Trust(issuer, authUri, tokenUri, redirect, clientId, secret, keyId,
                AssistantPinnedJwt.rsaPin(pin));
        } catch (IllegalArgumentException | GeneralSecurityException failure) {
            return null;
        }
    }

    private static boolean safe(URI url, boolean allowLoopback) {
        return url.getHost() != null && ("https".equals(url.getScheme())
            || allowLoopback && "http".equals(url.getScheme())
                && List.of("127.0.0.1", "localhost", "[::1]").contains(url.getHost()))
            && url.getRawUserInfo() == null && url.getRawQuery() == null
            && url.getRawFragment() == null
            && url.normalize().equals(url);
    }

    private static boolean sameOrigin(URI left, URI right) {
        return Objects.equals(left.getScheme(), right.getScheme())
            && Objects.equals(left.getHost(), right.getHost()) && left.getPort() == right.getPort();
    }

    private static String form(String input) {
        return URLEncoder.encode(input, StandardCharsets.UTF_8);
    }

    static AssistantApiException denied() {
        return new AssistantApiException(HttpStatus.UNAUTHORIZED, "ORGANIZATION_SSO_DENIED",
            "The organization sign-in could not be verified.");
    }

    private static AssistantApiException unavailable() {
        return new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
            "ORGANIZATION_SSO_UNAVAILABLE",
            "The organization identity provider is temporarily unavailable.");
    }

    private record Trust(String issuer, URI authorization, URI token, String redirect,
        String clientId,
        String secret, String keyId, RSAPublicKey key) {
    }
}
