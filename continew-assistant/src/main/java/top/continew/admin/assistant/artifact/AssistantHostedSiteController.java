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

package top.continew.admin.assistant.artifact;

import cn.dev33.satoken.annotation.SaIgnore;
import com.feiniaojin.gracefulresponse.api.ExcludeFromGracefulResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.assistant.account.AssistantAccountController.ApiError;
import top.continew.admin.assistant.account.AssistantAccounts;
import top.continew.admin.assistant.account.AssistantApiException;
import top.continew.admin.assistant.artifact.AssistantHostedSites.Preview;
import top.continew.admin.assistant.artifact.AssistantHostedSites.Publish;
import top.continew.admin.assistant.artifact.AssistantHostedSites.Release;
import top.continew.admin.assistant.artifact.AssistantHostedSites.ReportTarget;
import top.continew.admin.assistant.artifact.AssistantHostedSites.Status;
import top.continew.starter.extension.tenant.annotation.TenantIgnore;
import top.continew.starter.log.annotation.Log;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;

/**
 * Separates authenticated release management from read-only delivery at the configured serving host.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
@RestController
@ConditionalOnProperty(name = "assistant.enabled", havingValue = "true")
@ExcludeFromGracefulResponse
@SaIgnore
@TenantIgnore
@Log(ignore = true)
public class AssistantHostedSiteController {

    private static final String MANAGEMENT =
        "/api/assistant/v1/projects/{projectId}/artifacts/{artifactId}/hosting";
    private static final String WORKSPACE = "X-Workspace-Id";
    private static final MediaType HTML = new MediaType("text", "html", StandardCharsets.UTF_8);
    private static final byte[] UNAVAILABLE_PAGE = """
        <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>站点暂不可用</title></head>
        <body><main><h1>站点暂不可用</h1><p>此地址当前没有可访问的公开内容。</p></main></body></html>
        """.getBytes(StandardCharsets.UTF_8);

    private final AssistantAccounts accounts;
    private final AssistantHostedSites sites;

    /**
     * Wires the consumer boundary and per-version hosting records.
     *
     * @param accounts authenticates current consumer sessions
     * @param sites active public releases and source authorization
     */
    public AssistantHostedSiteController(AssistantAccounts accounts, AssistantHostedSites sites) {
        this.accounts = accounts;
        this.sites = sites;
    }

    /**
     * Returns the release for the selected owned project webpage.
     *
     * @param authorization consumer bearer
     * @param workspace current workspace header
     * @param projectId owning project
     * @param artifactId private webpage
     * @return owner-only hosting state
     */
    @GetMapping(MANAGEMENT)
    public ResponseEntity<Status> status(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable String projectId, @PathVariable String artifactId) {
        var principal = accounts.authenticate(authorization, workspace);
        return json(sites.status(principal.user(), projectId, artifactId));
    }

    /**
     * Returns the digest of the same structured document shown in the private preview.
     *
     * @param authorization consumer bearer
     * @param workspace current workspace header
     * @param projectId owning project
     * @param artifactId private webpage
     * @param versionId explicitly selected immutable version
     * @return digest and metadata for confirmation
     */
    @GetMapping(MANAGEMENT + "/preview/{versionId}")
    public ResponseEntity<Preview> preview(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable String projectId, @PathVariable String artifactId,
        @PathVariable String versionId) {
        var principal = accounts.authenticate(authorization, workspace);
        return json(sites.preview(principal.user(), projectId, artifactId, versionId));
    }

    /**
     * Publishes the exact version reviewed by the owner.
     *
     * @param authorization consumer bearer
     * @param workspace current workspace header
     * @param projectId owning project
     * @param artifactId private webpage
     * @param input exact preview digest, revision and confirmation
     * @return live publication status and public address
     */
    @PutMapping(value = MANAGEMENT, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Release> publish(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable String projectId, @PathVariable String artifactId,
        @RequestBody Publish input) {
        var principal = accounts.authenticate(authorization, workspace);
        return json(sites.publish(principal, projectId, artifactId, input));
    }

    /**
     * Retires the selected public revision and leaves its source private project versions intact.
     *
     * @param authorization consumer bearer
     * @param workspace current workspace header
     * @param projectId owning project
     * @param artifactId private webpage
     * @param releaseRevision exact published revision
     * @return empty response
     */
    @DeleteMapping(MANAGEMENT + "/{releaseRevision}")
    public ResponseEntity<Void> revoke(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable String projectId, @PathVariable String artifactId,
        @PathVariable String releaseRevision) {
        var principal = accounts.authenticate(authorization, workspace);
        sites.revoke(principal, projectId, artifactId, releaseRevision);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * Provides the canonical site URL and observed public revision to an authenticated reporter.
     *
     * @param authorization consumer bearer
     * @param workspace current workspace header
     * @param siteId opaque public site address
     * @return only public metadata required to confirm a report
     */
    @GetMapping("/api/assistant/v1/hosted-sites/{siteId}/report-target")
    public ResponseEntity<ReportTarget> reportTarget(
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestHeader(value = WORKSPACE, required = false) String workspace,
        @PathVariable String siteId) {
        var principal = accounts.authenticate(authorization, workspace);
        return json(sites.reportableVersion(principal, siteId));
    }

    /**
     * Serves only the reviewed static bytes. Incoming cookies and authorization are irrelevant to this route.
     *
     * @param id opaque public site address
     * @param request transport host metadata
     * @return uncached HTML for the approved site or a generic availability response
     */
    @GetMapping(AssistantHostedSites.PUBLIC_PATH + "/{id}")
    public ResponseEntity<byte[]> open(@PathVariable String id, HttpServletRequest request) {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store, no-transform");
        headers.set("Content-Security-Policy", AssistantWebpage.RESPONSE_POLICY);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Robots-Tag", "noindex, nofollow, noarchive");
        try {
            var page = sites.read(request.getServerName(), id);
            if (page == null) {
                return unavailablePage(HttpStatus.NOT_FOUND, headers);
            }
            headers.set("X-Assistant-Publication-Revision", page.revision());
            return ResponseEntity.ok().headers(headers).contentType(HTML).body(page.html());
        } catch (AssistantApiException failure) {
            return unavailablePage(failure.status(), headers);
        } catch (DataAccessException | TransactionException failure) {
            return unavailablePage(HttpStatus.SERVICE_UNAVAILABLE, headers);
        }
    }

    private static ResponseEntity<byte[]> unavailablePage(HttpStatus status, HttpHeaders headers) {
        return ResponseEntity.status(status).headers(headers).contentType(HTML)
            .body(UNAVAILABLE_PAGE);
    }

    /**
     * Applies the common API error envelope to authenticated management operations.
     *
     * @param failure stable consumer error
     * @return private API error response
     */
    @ExceptionHandler(AssistantApiException.class)
    public ResponseEntity<ApiError> apiError(AssistantApiException failure) {
        return error(failure.status(), failure.code(), failure.getMessage());
    }

    /**
     * Rejects an unreadable management body.
     *
     * @return private validation response
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> invalid() {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
            "A valid site release request is required.");
    }

    /**
     * Maps temporary storage failure to a private retryable response.
     *
     * @return private availability response
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "CAPABILITY_UNAVAILABLE",
            "Site hosting is temporarily unavailable.");
    }

    private static <T> ResponseEntity<T> json(T value) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore()).body(value);
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore()).body(new ApiError(code, message));
    }
}
