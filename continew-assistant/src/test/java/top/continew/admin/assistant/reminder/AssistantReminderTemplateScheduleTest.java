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

package top.continew.admin.assistant.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantAccounts.User;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.privacy.AssistantDeletionJournal;
import top.continew.admin.assistant.privacy.AssistantErasure;
import top.continew.admin.assistant.reminder.AssistantReminders.Create;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Tests that applying a stale reviewed occurrence cannot schedule its successor.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantReminderTemplateScheduleTest {

    @Test
    void reviewedRecurringDateIsNotSilentlyAdvancedAtCopyTime() {
        String account = "b2c20452-218b-498d-a0af-bbb7777c476a";
        String workspace = "fae351a5-4763-4c27-a95a-347782939925";
        User owner = new User(account, "synthetic", workspace, null);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AssistantDeletionJournal journal = mock(AssistantDeletionJournal.class);
        when(journal.enabled()).thenReturn(true);
        when(journal.acceptsErasure()).thenReturn(true);
        when(jdbc.queryForList(contains("FROM assistant_workspace_member"), eq(String.class),
            eq(account), eq(workspace))).thenReturn(List.of(account));
        AssistantReminders service = new AssistantReminders(jdbc,
            mock(PlatformTransactionManager.class), mock(AssistantAccounts.class), journal,
            mock(AssistantErasure.class), true);
        Instant reviewed = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);
        String local = ReminderCalendar.format(LocalDateTime.ofInstant(reviewed, ZoneOffset.UTC));
        var input = new Create("Synthetic title", local, "UTC", "daily", null);

        assertThatThrownBy(() -> service.createFromTemplate(owner,
            "ceb29f29-d48b-4bbe-88f3-5c58902e3fa1", input, reviewed))
            .isInstanceOfSatisfying(AssistantApiException.class, failure -> {
                assertThat(failure.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(failure.code()).isEqualTo("SHARE_PREVIEW_STALE");
            });
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
