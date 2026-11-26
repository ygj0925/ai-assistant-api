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

package top.continew.admin.assistant.run;

import org.springframework.http.HttpStatus;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.model.AssistantModels.Turn;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Describes the selected context for a generation without retaining message text in metadata.
 *
 * @param budget maximum Unicode code points
 * @param used retained Unicode code points
 * @param turns retained messages
 * @param trimmed whether older messages were omitted
 * @author yuanguangji
 * @since 4.2.0
 */
public record AssistantRunContext(int budget, int used, int turns, boolean trimmed) {

    static final int MAX_TURNS = 32;

    static AssistantRunContext fit(List<Turn> branch, int budget) {
        return fit(branch, budget, MAX_TURNS);
    }

    static AssistantRunContext fit(List<Turn> branch, int budget, int maximumTurns) {
        int used = 0;
        int selected = 0;
        for (int i = branch.size() - 1; i >= 0 && selected < maximumTurns; i--) {
            String text = branch.get(i).content();
            int size = text.codePointCount(0, text.length());
            if (size > budget - used) {
                break;
            }
            used += size;
            selected++;
        }
        if (used == 0) {
            throw new AssistantApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "The current message exceeds the model's context budget.");
        }
        return new AssistantRunContext(budget, used, selected, selected < branch.size());
    }

    /**
     * Reads persisted context metadata from a run or joined message row.
     *
     * @param row result containing the run context columns
     * @return recorded metadata or null for replies created before tracking
     * @throws SQLException if the result cannot be read
     */
    public static AssistantRunContext read(ResultSet row) throws SQLException {
        int budget = row.getInt("context_budget");
        if (row.wasNull()) {
            return null;
        }
        return new AssistantRunContext(budget, row.getInt("context_used"),
            row.getInt("context_turns"), row.getBoolean("context_trimmed"));
    }
}
