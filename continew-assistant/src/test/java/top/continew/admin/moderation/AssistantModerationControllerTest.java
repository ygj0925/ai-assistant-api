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

package top.continew.admin.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.moderation.AssistantReports;
import top.continew.admin.assistant.moderation.AssistantReports.Operator;
import top.continew.admin.common.context.UserContextHolder;
import top.continew.admin.common.context.UserExtraContext;

import java.util.List;

/**
 * Tests the administrator authentication boundary and the source of the tenant identity.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantModerationControllerTest {

    private static final String WORKSPACE_ID = "f45065fc-5b7e-4fe4-a083-b0f1d0f7d039";
    private final AssistantReports reports = mock(AssistantReports.class);
    private final AssistantModerationController controller =
        new AssistantModerationController(reports);

    @Test
    void usesAdminAuthenticationAndTheTokenTenantRegardlessOfHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "333");
        request.addHeader("X-Workspace-Id", "another workspace");
        UserExtraContext extra = new UserExtraContext();
        extra.setTenantId(7L);
        try (var stp = mockStatic(StpUtil.class); var user = mockStatic(UserContextHolder.class)) {
            stp.when(StpUtil::getTokenValue).thenReturn("synthetic-administration-session");
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(42L);
            user.when(() -> UserContextHolder.getExtraContext("synthetic-administration-session"))
                .thenReturn(extra);
            when(reports.pending(new Operator(42, 7), WORKSPACE_ID, 25)).thenReturn(List.of());

            assertThat(controller.list(WORKSPACE_ID, 25, request).getBody())
                .containsEntry("items", List.of());
            stp.verify(StpUtil::checkLogin);
            stp.verify(() -> StpUtil.checkPermission("assistant:report:review"));
            verify(reports).pending(new Operator(42, 7), WORKSPACE_ID, 25);
        }
    }

    @Test
    void rejectsAppSignatureBeforeConsultingOperatorPermissions() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("sign", "synthetic");
        try (var stp = mockStatic(StpUtil.class)) {
            assertThatThrownBy(() -> controller.list(WORKSPACE_ID, 25, request))
                .isInstanceOfSatisfying(AssistantApiException.class,
                    failure -> assertThat(failure.status().value()).isEqualTo(401));
            stp.verifyNoInteractions();
            verifyNoInteractions(reports);
        }
    }
}
