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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Set;

/**
 * Checks the exact currency and outbound link projection boundary.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantSyntheticCatalogueTest {

    @Test
    void rendersRecordedPricesWithTheirOriginalCurrencyAndPrecision() {
        assertThat(AssistantSyntheticCatalogue.price(1_230L, 2, "CNY"))
            .contains(new AssistantSyntheticCatalogue.Price("12.30", "CNY"));
        assertThat(AssistantSyntheticCatalogue.price(1_230L, 0, "JPY"))
            .contains(new AssistantSyntheticCatalogue.Price("1230", "JPY"));
        assertThat(AssistantSyntheticCatalogue.price(123L, 3, "KWD"))
            .contains(new AssistantSyntheticCatalogue.Price("0.123", "KWD"));
        assertThat(AssistantSyntheticCatalogue.price(-1L, 2, "CNY")).isEmpty();
        assertThat(AssistantSyntheticCatalogue.price(12L, 0, "CNY")).isEmpty();
        assertThat(AssistantSyntheticCatalogue.price(12L, 2, "XXX")).isEmpty();
    }

    @Test
    void acceptsOnlyExactReservedFixtureMerchantUrls() {
        var hosts = Set.of("fixture-shop.example");
        assertThat(AssistantSyntheticCatalogue.allowedMerchant(
            "https://fixture-shop.example/products/a-123", hosts)).isTrue();
        for (String poisoned : List.of(
            "https://fixture-shop.example.other.example/products/a",
            "https://fixture-shop.example@other.example/products/a",
            "https://fixture-shop.example:443/products/a",
            "https://fixture-shop.example/products/a?tracking=1",
            "https://fixture-shop.example/products/a#fragment",
            "https://fixture-shop.example/products/%61",
            "https://fixture-shop.example/alternate/a",
            "https://one..example/products/a",
            "http://fixture-shop.example/products/a",
            "javascript:alert(1)")) {
            assertThat(AssistantSyntheticCatalogue.allowedMerchant(poisoned, hosts)).isFalse();
        }
    }

    @Test
    void requiresLocalProfilesAndReservedConfiguredHosts() {
        var environment = new MockEnvironment();
        var jdbc = mock(JdbcTemplate.class);
        environment.setActiveProfiles("dev", "prod");
        assertThatIllegalStateException().isThrownBy(() -> new AssistantSyntheticCatalogue(jdbc,
            environment, true, "fixture-shop.example"));
        environment.setActiveProfiles("test");
        assertThatIllegalStateException().isThrownBy(() -> new AssistantSyntheticCatalogue(jdbc,
            environment, true, "fixture-shop.com"));
    }
}
