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

package top.continew.admin.assistant.commerce;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;

import java.math.BigDecimal;
import java.net.URI;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Projects current facts from an isolated, approved synthetic merchant catalogue.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@Service
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
public class AssistantSyntheticCatalogue {

    private static final String AVAILABLE = "available";
    private static final String OUT_OF_STOCK = "out_of_stock";
    private static final String DELISTED = "delisted";
    private static final String EXPIRED = "expired";
    private static final String UNAVAILABLE = "unavailable";
    private static final String MODE = "synthetic";
    private static final int MAX_PRODUCTS = 128;
    private static final int MAX_QUERY = 120;
    private static final long MAX_MINOR = 99_999_999_999L;
    private static final Pattern ITEM_PATH = Pattern.compile("/products/[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");
    private static final String CURRENT =
        """
            SELECT p.id, p.title, p.description, p.category, p.search_text, s.id AS snapshot_id, s.state,
                s.price_minor, s.price_scale, s.currency, s.merchant_name, s.merchant_url, s.observed_at, s.expires_at
            FROM assistant_synthetic_catalogue_product p
            LEFT JOIN assistant_synthetic_catalogue_offer s ON s.product_id = p.id AND s.observed_at = (
                SELECT MAX(h.observed_at) FROM assistant_synthetic_catalogue_offer h
                WHERE h.product_id = p.id AND h.observed_at <= ?
            )
            ORDER BY p.id LIMIT 129
            """;
    private static final RowMapper<CatalogueRow> ROW = (row, index) -> new CatalogueRow(
        row.getString("id"), row.getString("title"), row.getString("description"),
        row.getString("category"), row.getString("search_text"), row.getString("snapshot_id"),
        row.getString("state"), row.getObject("price_minor", Long.class),
        row.getObject("price_scale", Integer.class),
        row.getString("currency"),
        row.getString("merchant_name"), row.getString("merchant_url"),
        row.getObject("observed_at", Long.class), row.getObject("expires_at", Long.class));

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final Set<String> hosts;
    private final Clock clock;

    /**
     * Connects the read-only catalogue to its explicitly enabled local deployment.
     *
     * @param jdbc assistant database
     * @param environment current deployment profiles
     * @param enabled synthetic catalogue flag
     * @param merchantHosts comma-separated exact reserved fixture hosts
     */
    @Autowired
    public AssistantSyntheticCatalogue(JdbcTemplate jdbc, Environment environment,
        @Value("${assistant.commerce.synthetic-catalogue.enabled:false}") boolean enabled,
        @Value("${assistant.commerce.synthetic-catalogue.merchant-hosts:fixture-shop.example}") String merchantHosts) {
        this(jdbc, environment, enabled, merchantHosts, Clock.systemUTC());
    }

    AssistantSyntheticCatalogue(JdbcTemplate jdbc, Environment environment, boolean enabled,
        String merchantHosts, Clock clock) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.clock = clock;
        if (!enabled) {
            hosts = Set.of();
            return;
        }
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        if (profiles.contains("prod")
            || (!profiles.contains("dev") && !profiles.contains("test"))) {
            throw new IllegalStateException(
                "The synthetic catalogue requires a local test profile.");
        }
        hosts = Arrays.stream(merchantHosts.split(",", -1)).map(String::strip)
            .collect(Collectors.toUnmodifiableSet());
        if (hosts.isEmpty() || hosts.size() > 8
            || !hosts.stream().allMatch(AssistantSyntheticCatalogue::fixtureHost)) {
            throw new IllegalStateException(
                "Choose exact reserved merchant hosts for the synthetic catalogue.");
        }
    }

    /**
     * Reports whether the local synthetic catalogue is available to a signed-in consumer.
     *
     * @param user consumer or null
     * @return current feature availability
     */
    public boolean available(User user) {
        return enabled && user != null;
    }

    Page search(String query, int limit, String cursor) {
        requireEnabled();
        List<String> terms = terms(query);
        if (limit < 1 || limit > 48 || cursor != null && !uuid(cursor)) {
            throw invalid("Choose a valid catalogue cursor and a limit between 1 and 48.");
        }
        Instant now = clock.instant();
        List<Product> products = catalogue(now).stream()
            .filter(row -> cursor == null || row.id().compareTo(cursor) > 0)
            .filter(row -> matches(row, terms))
            .map(row -> project(row, now))
            .filter(product -> AVAILABLE.equals(product.status())
                || OUT_OF_STOCK.equals(product.status()))
            .limit(limit + 1L).toList();
        List<Product> items = List.copyOf(products.subList(0, Math.min(limit, products.size())));
        return new Page(MODE, items, products.size() <= limit ? null : items.get(limit - 1).id());
    }

    Product get(String id) {
        requireEnabled();
        requireUuid(id);
        Instant now = clock.instant();
        return current(id, now);
    }

    Comparison compare(List<String> ids) {
        requireEnabled();
        if (ids == null || ids.size() < 2 || ids.size() > 4
            || !ids.stream().allMatch(AssistantSyntheticCatalogue::uuid)
            || ids.stream().distinct().count() != ids.size()) {
            throw invalid("Choose two to four different catalogue products.");
        }
        Instant now = clock.instant();
        Map<String, CatalogueRow> rows = catalogue(now).stream()
            .collect(Collectors.toMap(CatalogueRow::id, Function.identity()));
        List<Product> products = ids.stream().map(id -> {
            CatalogueRow row = rows.get(id);
            if (row == null) {
                throw missing();
            }
            return project(row, now);
        }).toList();
        return new Comparison(MODE, products);
    }

    ProductSource source(String id, String snapshot) {
        requireEnabled();
        requireUuid(id);
        requireUuid(snapshot);
        ProductSource source = current(id, clock.instant()).source();
        if (source == null || !source.snapshotId().equals(snapshot)) {
            throw new AssistantApiException(HttpStatus.CONFLICT, "CATALOGUE_CHANGED",
                "The current catalogue source has changed. Refresh this product.");
        }
        return source;
    }

    private Product current(String id, Instant now) {
        return catalogue(now).stream().filter(row -> row.id().equals(id)).findFirst()
            .map(row -> project(row, now)).orElseThrow(AssistantSyntheticCatalogue::missing);
    }

    private List<CatalogueRow> catalogue(Instant now) {
        List<CatalogueRow> rows = jdbc.query(CURRENT, ROW, now.toEpochMilli());
        if (rows.size() > MAX_PRODUCTS) {
            throw new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "CAPABILITY_UNAVAILABLE",
                "The isolated catalogue exceeded its configured fixture limit.");
        }
        return rows;
    }

    private Product project(CatalogueRow row, Instant now) {
        if (row.snapshot() == null || row.observed() == null) {
            return row.product(UNAVAILABLE, null, null, null);
        }
        Instant observed = Instant.ofEpochMilli(row.observed());
        if (DELISTED.equals(row.state())) {
            return row.product(DELISTED, observed, null, null);
        }
        if (row.expires() == null || row.expires() <= now.toEpochMilli()) {
            return row.product(EXPIRED, observed, null, null);
        }
        var price = price(row.priceMinor(), row.priceScale(), row.currency());
        if (!(AVAILABLE.equals(row.state()) || OUT_OF_STOCK.equals(row.state())) || price.isEmpty()
            || !allowedMerchant(row.merchantUrl(), hosts) || !plainMerchantName(row.merchant())) {
            return row.product(UNAVAILABLE, observed, null, null);
        }
        var source = new ProductSource(row.snapshot(), row.merchant(), row.merchantUrl(), observed,
            Instant.ofEpochMilli(row.expires()));
        return row.product(row.state(), observed, price.orElseThrow(), source);
    }

    static Optional<Price> price(Long minor, Integer scale, String currency) {
        if (minor == null || minor < 0 || minor > MAX_MINOR || scale == null || scale < 0
            || scale > 3 || currency == null
            || !CURRENCY_CODE.matcher(currency).matches()) {
            return Optional.empty();
        }
        try {
            if (Currency.getInstance(currency).getDefaultFractionDigits() != scale) {
                return Optional.empty();
            }
            return Optional
                .of(new Price(BigDecimal.valueOf(minor, scale).toPlainString(), currency));
        } catch (IllegalArgumentException invalidCurrency) {
            return Optional.empty();
        }
    }

    static boolean allowedMerchant(String value, Set<String> exactHosts) {
        if (value == null || value.length() > 256) {
            return false;
        }
        try {
            URI parsed = URI.create(value);
            String host = parsed.getHost();
            return "https".equals(parsed.getScheme()) && host != null && exactHosts.contains(host)
                && parsed.getRawUserInfo() == null && parsed.getPort() == -1
                && parsed.getRawQuery() == null && parsed.getRawFragment() == null
                && parsed.getRawPath() != null && ITEM_PATH.matcher(parsed.getRawPath()).matches();
        } catch (IllegalArgumentException invalidUrl) {
            return false;
        }
    }

    private static boolean fixtureHost(String host) {
        if (host.length() > 253 || !host.endsWith(".example")
            || !host.equals(host.toLowerCase(Locale.ROOT))) {
            return false;
        }
        return allowedMerchant("https://" + host + "/products/catalogue", Set.of(host));
    }

    private static boolean plainMerchantName(String value) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= 120
            && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean matches(CatalogueRow product, List<String> terms) {
        if (terms.isEmpty()) {
            return true;
        }
        String text = normalize(product.title() + " " + product.description() + " "
            + product.category() + " " + product.searchText());
        return terms.stream().allMatch(text::contains);
    }

    private static List<String> terms(String query) {
        if (query == null || query.isEmpty()) {
            return List.of();
        }
        if (query.codePointCount(0, query.length()) > MAX_QUERY
            || query.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("Enter a catalogue query of up to 120 characters.");
        }
        String normalized = normalize(query);
        if (normalized.codePointCount(0, normalized.length()) > MAX_QUERY) {
            throw invalid("Enter a catalogue query of up to 120 characters.");
        }
        List<String> words = Arrays.stream(normalized.split("\\p{javaWhitespace}+"))
            .filter(word -> !word.isEmpty()).toList();
        if (words.size() > 8) {
            throw invalid("Enter up to eight search terms.");
        }
        return words;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new AssistantApiException(HttpStatus.FORBIDDEN, "CAPABILITY_UNAVAILABLE",
                "The synthetic catalogue is unavailable.");
        }
    }

    private static void requireUuid(String id) {
        if (!uuid(id)) {
            throw invalid("Choose a valid catalogue product or snapshot identifier.");
        }
    }

    private static boolean uuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException invalidId) {
            return false;
        }
    }

    private static AssistantApiException invalid(String text) {
        return new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", text);
    }

    private static AssistantApiException missing() {
        return new AssistantApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
            "The catalogue product was not found.");
    }

    /**
     * One current or withdrawn approved product observation.
     *
     * @param id stable product ID
     * @param title catalogue title
     * @param description bounded approved description
     * @param category approved classification
     * @param status current source and availability state
     * @param observedAt latest applicable observation time
     * @param price exact quoted price when available
     * @param source current approved merchant provenance
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Product(String id, String title, String description, String category,
        String status,
        Instant observedAt, Price price, ProductSource source) {
    }

    /**
     * Exact source currency and its decimal representation.
     *
     * @param amount current decimal amount
     * @param currency ISO currency code given by the approved source
     */
    public record Price(String amount, String currency) {
    }

    /**
     * One approved merchant observation tied to the displayed price.
     *
     * @param snapshotId exact immutable observation
     * @param merchantName approved synthetic merchant label
     * @param merchantUrl exact allowlisted fixture address
     * @param observedAt approved observation time
     * @param expiresAt observation expiration
     */
    public record ProductSource(String snapshotId, String merchantName, String merchantUrl,
        Instant observedAt, Instant expiresAt) {
    }

    /**
     * Bounded catalogue page.
     *
     * @param mode explicit synthetic catalogue label
     * @param items matching product observations
     * @param nextCursor continuation or null
     */
    public record Page(String mode, List<Product> items,
        @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {
    }

    /**
     * One consistent current snapshot of the selected comparison.
     *
     * @param mode explicit synthetic catalogue label
     * @param items selected products in input order
     */
    public record Comparison(String mode, List<Product> items) {
    }

    private record CatalogueRow(String id, String title, String description, String category,
        String searchText, String snapshot, String state, Long priceMinor, Integer priceScale,
        String currency, String merchant, String merchantUrl, Long observed, Long expires) {

        Product product(String status, Instant observedAt, Price price, ProductSource source) {
            return new Product(id, title, description, category, status, observedAt, price, source);
        }
    }
}
