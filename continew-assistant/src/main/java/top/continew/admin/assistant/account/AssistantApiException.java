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

package top.continew.admin.assistant.account;

import org.springframework.http.HttpStatus;

import java.io.Serial;

/**
 * Error returned by the consumer API.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
public class AssistantApiException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;
    private final HttpStatus status;
    private final String code;

    /**
     * Creates a consumer API error with a stable error code.
     *
     * @param status HTTP status
     * @param code stable error code
     * @param message public diagnostic
     */
    public AssistantApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * Returns the HTTP status.
     *
     * @return HTTP status
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * Returns the consumer API error code.
     *
     * @return stable error code
     */
    public String code() {
        return code;
    }
}
