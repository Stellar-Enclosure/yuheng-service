package top.egon.cola.component.yuheng.admin.mcp.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import top.egon.cola.component.yuheng.admin.mcp.domain.dto.McpApprovalRequestDTO;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpApprovalVO;
import top.egon.cola.component.yuheng.admin.mcp.service.McpApprovalAdminService;
import top.egon.cola.component.yuheng.admin.mcp.service.McpApprovalAdminService.Failure;
import top.egon.cola.component.yuheng.mcp.engine.bootstrap.config.McpManagementAccessFilter;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpGatewayIdentityAuthenticator.SecurityFailure;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpGatewayIdentityAuthenticator.VerifiedManagementIdentityVO;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.RequiresPermissions;
import top.egon.cola.platform.tianquan.shoubing.starter.security.IdpSecurityObservationFilter;

/**
 * 中文说明：独立适配器内的一次性审批入口，路径/201/响应保持，身份只来自受信管理过滤器。
 * English summary: The adapter's one-shot approval endpoint. Its path, 201 and response remain stable; only the
 * verified management filter supplies USER identity. The historical package does not introduce an Admin JAR dependency.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/yuheng/admin/mcp/approvals")
@PreAuthorize("hasAuthority('SCOPE_yuheng:mcp:approve') and hasAnyAuthority('CAP_yuheng:mcp:approve','CAP_*')")
@Tag(name = "yuheng-admin")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class McpApprovalController {
    @Qualifier("mcpApprovalAdminServiceImpl") private final McpApprovalAdminService approvalAdminService;

    @PermissionResource(key = "yuheng.api.mcpApproval.issue", type = ResourceType.API,
        labelPage = "gateway.permissions", labelKey = "api.mcpApproval.issue",
        allowedPrincipals = PermissionManifest.PrincipalType.USER, oauthScopes = "yuheng:mcp:approve")
    @RequiresPermissions(codes = "yuheng:mcp:approve")
    @Operation(operationId = "admin.mcpApprovalController.issue")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public McpApprovalVO
    issue(@Valid @RequestBody McpApprovalRequestDTO request,
        @RequestAttribute(McpManagementAccessFilter.VERIFIED_IDENTITY_ATTRIBUTE) VerifiedManagementIdentityVO identity,
        HttpServletResponse response) {
        if (identity == null)
            throw new Failure(401, "YUHENG_ADMIN_AUTHENTICATION_REQUIRED");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return approvalAdminService.issue(request, identity);
    }

    @ExceptionHandler(Failure.class)
    public ResponseEntity<ErrorVO> failure(Failure failure, HttpServletRequest request) {
        return error(failure.status(), failure.code(), List.of(), request);
    }
    @ExceptionHandler(SecurityFailure.class)
    public ResponseEntity<ErrorVO> security(SecurityFailure failure, HttpServletRequest request) {
        return error(failure.httpStatus(),
            failure.httpStatus() == 401       ? "YUHENG_ADMIN_AUTHENTICATION_REQUIRED"
                : failure.httpStatus() == 403 ? "YUHENG_ADMIN_CAPABILITY_REQUIRED"
                                              : "YUHENG_ADMIN_SECURITY_UNAVAILABLE",
            List.of(), request);
    }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorVO> denied(HttpServletRequest request) {
        return error(403, "YUHENG_ADMIN_CAPABILITY_REQUIRED", List.of(), request);
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorVO> invalid(MethodArgumentNotValidException failure, HttpServletRequest request) {
        var fields = failure.getBindingResult()
                         .getFieldErrors()
                         .stream()
                         .map(field -> new FieldErrorVO(field.getField(), "INVALID", "Invalid field"))
                         .distinct()
                         .toList();
        return error(422, "YUHENG_ADMIN_VALIDATION_FAILED", fields, request);
    }
    @ExceptionHandler(
        {HttpMessageNotReadableException.class, ConstraintViolationException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorVO> invalid(HttpServletRequest request) {
        return error(422, "YUHENG_ADMIN_VALIDATION_FAILED", List.of(), request);
    }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorVO> unavailable(HttpServletRequest request) {
        log.warn("MCP_APPROVAL_MANAGEMENT_UNAVAILABLE");
        return error(503, "YUHENG_ADMIN_SECURITY_UNAVAILABLE", List.of(), request);
    }
    private static ResponseEntity<ErrorVO> error(
        int status, String code, List<FieldErrorVO> fields, HttpServletRequest request) {
        boolean audited = status == 401
            ? IdpSecurityObservationFilter.beforeAuthenticationFailure(request)
            : status != 403 || IdpSecurityObservationFilter.beforeAuthorizationDenied(request);
        if (!audited) {
            status = 503;
            code = "OAUTH_AUDIT_DELIVERY_UNAVAILABLE";
            fields = List.of();
        }
        var response = ResponseEntity.status(status).cacheControl(org.springframework.http.CacheControl.noStore());
        if (status == 401)
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return response.body(new ErrorVO(code, code, null, fields, Instant.now()));
    }
    public record FieldErrorVO(String path, String code, String message) {}
    public record ErrorVO(String code, String message, Long currentRevision, List<FieldErrorVO> errors,
        @com.fasterxml.jackson.annotation.JsonFormat(
            shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) Instant timestamp) {}
}
