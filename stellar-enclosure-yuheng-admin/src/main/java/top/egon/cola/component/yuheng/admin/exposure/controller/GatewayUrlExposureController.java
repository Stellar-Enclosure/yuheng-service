package top.egon.cola.component.yuheng.admin.exposure.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.egon.cola.component.yuheng.admin.exposure.domain.command.GatewayUrlExposureStateCommand;
import top.egon.cola.component.yuheng.admin.exposure.domain.dto.GatewayUrlExposureStateRequestDTO;
import top.egon.cola.component.yuheng.admin.exposure.domain.exception.GatewayUrlExposureException;
import top.egon.cola.component.yuheng.admin.exposure.domain.query.GatewayUrlExposureQuery;
import top.egon.cola.component.yuheng.admin.exposure.domain.vo.GatewayUrlExposureVO;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayUrlExposureService;
import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.component.yuheng.admin.shared.domain.AdminActor;
import top.egon.cola.component.yuheng.admin.shared.domain.RequestAuditContext;
import top.egon.cola.component.yuheng.admin.shared.domain.vo.GatewayAdminErrorVO;
import top.egon.cola.component.yuheng.openapi.annotation.EgonApiCatalog;
import top.egon.cola.component.yuheng.openapi.annotation.EgonGatewayPolicy;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.RequiresPermissions;

import java.net.URI;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Typed HTTP adapter for durable desired state and local, owner-filtered observation reads. */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
@RestController("gatewayUrlExposureController")
@RequestMapping("/api/v1/yuheng/admin/exposures")
@Tag(name = "yuheng-admin")
@SecurityRequirement(name = "yuhengBearer")
@EgonApiCatalog(businessDomainCode = "xingyuan", businessDomainName = "平台治理域",
        entityDomainCode = "yuheng-admin", entityDomainName = "Gateway Admin 管理实体域", interfaceGroupCode = "yuheng-admin")
public class GatewayUrlExposureController {

    private static final Pattern STRONG_ETAG = Pattern.compile("\"exposure-([1-9][0-9]{0,18})-v(0|[1-9][0-9]{0,18})\"");

    @Qualifier("gatewayUrlExposureServiceImpl")
    private final GatewayUrlExposureService service;

    @PermissionResource(key = "yuheng.api.gatewayUrlExposure.putState", type = ResourceType.API,
            labelPage = "gateway.permissions", labelKey = "api.gatewayUrlExposure.putState",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.EXPOSURES_WRITE)
    @RequiresPermissions(codes = GatewayPermissionCodes.EXPOSURES_WRITE)
    @PreAuthorize("hasAuthority('SCOPE_yuheng:exposures:write') and (hasAuthority('CAP_yuheng:exposures:write') or hasAuthority('CAP_*'))")
    @EgonGatewayPolicy(exposure = EgonGatewayPolicy.Exposure.EXTERNAL)
    @Operation(operationId = "replaceYuhengExposureState",
            summary = "Replace a URL exposure's desired state",
            description = "Requires OAuth scope yuheng:exposures:write and the matching CAP permission. OPEN additionally requires yuheng:exposures:reopen. This commits local intent; 202 is pending convergence.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Unchanged desired state is already converged",
                    content = @Content(schema = @Schema(implementation = GatewayUrlExposureVO.class)),
                    headers = {@Header(name = "ETag", schema = @Schema(type = "string")),
                            @Header(name = "Location", schema = @Schema(type = "string")),
                            @Header(name = "Cache-Control", schema = @Schema(type = "string")),
                            @Header(name = "x-egon-request-id", schema = @Schema(type = "string"))}),
            @ApiResponse(responseCode = "202", description = "Committed intent awaits convergence",
                    content = @Content(schema = @Schema(implementation = GatewayUrlExposureVO.class)),
                    headers = {@Header(name = "ETag", schema = @Schema(type = "string")),
                            @Header(name = "Location", schema = @Schema(type = "string")),
                            @Header(name = "Retry-After", schema = @Schema(type = "string", allowableValues = "2")),
                            @Header(name = "Cache-Control", schema = @Schema(type = "string")),
                            @Header(name = "x-egon-request-id", schema = @Schema(type = "string"))}),
            @ApiResponse(responseCode = "400", description = "Malformed path, headers or JSON", content = @Content(schema = @Schema(implementation = GatewayAdminErrorVO.class))),
            @ApiResponse(responseCode = "401", description = "Bearer authentication required", content = @Content(schema = @Schema(implementation = GatewayAdminErrorVO.class))),
            @ApiResponse(responseCode = "403", description = "Scope, capability or owner denied", content = @Content(schema = @Schema(implementation = GatewayAdminErrorVO.class))),
            @ApiResponse(responseCode = "404", description = "Missing or foreign-owned exposure", content = @Content(schema = @Schema(implementation = GatewayAdminErrorVO.class))),
            @ApiResponse(responseCode = "409", description = "Withdrawn, idempotency or binding conflict", content = @Content(schema = @Schema(implementation = GatewayAdminErrorVO.class))),
            @ApiResponse(responseCode = "412", description = "Stale strong ETag; reload current revision", content = @Content(schema = @Schema(implementation = GatewayAdminErrorVO.class))),
            @ApiResponse(responseCode = "415", description = "JSON media type required", content = @Content(schema = @Schema(implementation = GatewayAdminErrorVO.class))),
            @ApiResponse(responseCode = "422", description = "Field validation failed", content = @Content(schema = @Schema(implementation = GatewayAdminErrorVO.class))),
            @ApiResponse(responseCode = "428", description = "If-Match is required", content = @Content(schema = @Schema(implementation = GatewayAdminErrorVO.class))),
            @ApiResponse(responseCode = "429", description = "Rate limited", content = @Content(schema = @Schema(implementation = GatewayAdminErrorVO.class))),
            @ApiResponse(responseCode = "503", description = "Authoritative control store unavailable", content = @Content(schema = @Schema(implementation = GatewayAdminErrorVO.class)))
    })
    @PutMapping(value = "/{exposureId}/state", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GatewayUrlExposureVO> putState(
            @PathVariable("exposureId") @Parameter(required = true, schema = @Schema(type = "string", pattern = "[1-9][0-9]{0,18}")) String exposureId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) @Parameter(required = true, description = "One strong, quoted exposure ETag; for example exposure-810001-v12 in double quotes.") String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) @Parameter(required = true, schema = @Schema(pattern = "[A-Za-z0-9._-]{1,128}")) String idempotencyKey,
            @Valid @RequestBody GatewayUrlExposureStateRequestDTO body,
            @Parameter(hidden = true) AdminActor actor, HttpServletRequest request) {
        rejectQuery(request);
        long id = positiveId(exposureId);
        checkIfMatch(id, ifMatch, request);
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._-]{1,128}")
                || Collections.list(request.getHeaders("Idempotency-Key")).size() != 1) {
            throw GatewayUrlExposureException.requestInvalid("Idempotency-Key");
        }
        var result = service.putState(actor, new GatewayUrlExposureStateCommand(
                id, ifMatch, idempotencyKey, body.getState(), body.getChangeReason()));
        var response = ResponseEntity.status(result.status())
                .location(URI.create("/api/v1/yuheng/admin/exposures/" + id))
                .eTag(result.etag()).cacheControl(CacheControl.noStore())
                .header("x-egon-request-id", RequestAuditContext.current(request.getHeader("x-egon-request-id")).requestId());
        if (result.status() == 202) {
            response.header(HttpHeaders.RETRY_AFTER, "2");
        }
        return response.body(result.view());
    }

    @PermissionResource(key = "yuheng.api.gatewayUrlExposure.get", type = ResourceType.API,
            labelPage = "gateway.permissions", labelKey = "api.gatewayUrlExposure.get",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = GatewayPermissionCodes.READ)
    @RequiresPermissions(codes = GatewayPermissionCodes.READ)
    @PreAuthorize("hasAuthority('SCOPE_yuheng:read') and (hasAuthority('CAP_yuheng:read') or hasAuthority('CAP_*'))")
    @EgonGatewayPolicy(exposure = EgonGatewayPolicy.Exposure.EXTERNAL)
    @Operation(operationId = "getYuhengExposure", summary = "Read committed URL exposure state",
            description = "Requires OAuth scope yuheng:read and its matching CAP permission. Reads local owner-filtered state without querying Kubernetes. No query parameters or request body.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current committed exposure",
                    content = @Content(schema = @Schema(implementation = GatewayUrlExposureVO.class)),
                    headers = {@Header(name = "ETag", schema = @Schema(type = "string")),
                            @Header(name = "Cache-Control", schema = @Schema(type = "string")),
                            @Header(name = "x-egon-request-id", schema = @Schema(type = "string"))}),
            @ApiResponse(responseCode = "400", description = "Malformed path or unexpected input", content = @Content(schema = @Schema(implementation = GatewayAdminErrorVO.class))),
            @ApiResponse(responseCode = "401", description = "Bearer authentication required", content = @Content(schema = @Schema(implementation = GatewayAdminErrorVO.class))),
            @ApiResponse(responseCode = "403", description = "Scope or capability denied", content = @Content(schema = @Schema(implementation = GatewayAdminErrorVO.class))),
            @ApiResponse(responseCode = "404", description = "Missing or foreign-owned exposure", content = @Content(schema = @Schema(implementation = GatewayAdminErrorVO.class))),
            @ApiResponse(responseCode = "503", description = "Authoritative control store unavailable", content = @Content(schema = @Schema(implementation = GatewayAdminErrorVO.class)))
    })
    @GetMapping(value = "/{exposureId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GatewayUrlExposureVO> get(
            @PathVariable("exposureId") @Parameter(required = true, schema = @Schema(type = "string", pattern = "[1-9][0-9]{0,18}")) String exposureId,
            @Parameter(hidden = true) AdminActor actor, HttpServletRequest request) {
        rejectQuery(request);
        if (request.getContentLengthLong() > 0 || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
            throw GatewayUrlExposureException.requestInvalid("body");
        }
        var result = service.get(actor, new GatewayUrlExposureQuery(positiveId(exposureId)));
        return ResponseEntity.ok().eTag(result.etag()).cacheControl(CacheControl.noStore())
                .header("x-egon-request-id", RequestAuditContext.current(request.getHeader("x-egon-request-id")).requestId())
                .body(result.view());
    }

    private void rejectQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw GatewayUrlExposureException.requestInvalid("query");
        }
    }

    private long positiveId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw GatewayUrlExposureException.requestInvalid("exposureId");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            throw GatewayUrlExposureException.requestInvalid("exposureId");
        }
    }

    private void checkIfMatch(long id, String value, HttpServletRequest request) {
        if (value == null) {
            throw GatewayUrlExposureException.preconditionRequired();
        }
        Matcher matcher = STRONG_ETAG.matcher(value);
        if (Collections.list(request.getHeaders(HttpHeaders.IF_MATCH)).size() != 1 || !matcher.matches()) {
            throw GatewayUrlExposureException.requestInvalid("If-Match");
        }
        try {
            if (Long.parseLong(matcher.group(1)) != id) {
                throw GatewayUrlExposureException.requestInvalid("If-Match");
            }
            Long.parseLong(matcher.group(2));
        } catch (NumberFormatException ignored) {
            throw GatewayUrlExposureException.requestInvalid("If-Match");
        }
    }
}
