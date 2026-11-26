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

package top.continew.admin.assistant.attachment;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Keeps original leases valid while preventing old builds from redeeming new leases.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class AssistantAttachmentGrantTest {

    @Test
    void legacyLookupCannotRedeemTheNewVersionSpecificGrant() throws Exception {
        String token = "a".repeat(43);
        String legacy = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(token.getBytes(StandardCharsets.US_ASCII)));
        String first = AssistantAttachments.grantDigest(token,
            "artifact:c65d10f1-6fad-4e74-87ea-ad4967be31a5", null);
        String second = AssistantAttachments.grantDigest(token,
            "artifact:c65d10f1-6fad-4e74-87ea-ad4967be31a6", null);
        Assertions.assertEquals(legacy,
            AssistantAttachments.grantDigest(token, "attachment", null));
        Assertions.assertNotEquals(legacy, first);
        Assertions.assertNotEquals(first, second);
    }

    @Test
    void aNewCollaborationGrantCannotRedeemAnEarlierRoleGeneration() {
        String token = "b".repeat(43);
        String purpose = "publication:c65d10f1-6fad-4e74-87ea-ad4967be31a5";
        String first = AssistantAttachments.grantDigest(token, purpose,
            "6af8e971-a355-4824-8583-52c1f7f1e001");
        String second = AssistantAttachments.grantDigest(token, purpose,
            "6af8e971-a355-4824-8583-52c1f7f1e002");
        Assertions.assertNotEquals(first, second);
        Assertions.assertNotEquals(first,
            AssistantAttachments.grantDigest(token, purpose, "owner"));
        Assertions.assertNotEquals(first,
            AssistantAttachments.grantDigest(token, "attachment", null));
    }
}
