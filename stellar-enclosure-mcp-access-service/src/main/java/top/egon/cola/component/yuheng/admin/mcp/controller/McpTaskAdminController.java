package top.egon.cola.component.yuheng.admin.mcp.controller;

import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.RequiresPermissions;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.egon.cola.component.yuheng.admin.mcp.domain.dto.McpTaskCancelRequestDTO;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpTaskCancelResultVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpTaskManagementService;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpGatewayIdentityAuthenticator.VerifiedManagementIdentityVO;
import top.egon.cola.component.yuheng.mcp.engine.bootstrap.config.McpManagementAccessFilter;
import org.springframework.web.bind.annotation.RequestAttribute;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpTaskBO;

/**
 * 中文说明：{@code McpTaskAdminController} 是接口控制器，位于当前 Gateway 模块的相关包中，负责MCP任务管理端控制器相关的职责与边界。
 * English summary: {@code McpTaskAdminController} is a mcp task admin controller controller in the current Gateway module; it owns the mcp task admin controller-related responsibility and boundary.
 *
 * 用法 / Usage: 通过 Spring 容器或上层组件使用该类型；/ Use this type through the Spring container or an enclosing component; its public contract is the supported extension and invocation boundary.
 */
@lombok.extern.slf4j.Slf4j
@lombok.RequiredArgsConstructor
@RestController("mcpTaskAdminController")
@RequestMapping("/api/v1/yuheng/admin/mcp/tasks")
@PreAuthorize("hasAuthority('SCOPE_yuheng:mcp:runtime:read') and (hasAnyAuthority('CAP_yuheng:mcp:runtime:read','CAP_*'))")
@Tag(name = "yuheng-admin")
public class McpTaskAdminController {

    /**
     * 中文说明：保存 服务 对应的状态、依赖或配置值；字段类型为 {@code McpTaskManagementService}，由 {@code McpTaskAdminController} 在其生命周期内读取或更新。
     * English summary: Holds the state, dependency, or configuration represented by service; its type is {@code McpTaskManagementService}, and {@code McpTaskAdminController} reads or updates it during its lifecycle.
     *
     * 用法 / Usage: 该字段通过 {@code McpTaskAdminController} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code McpTaskAdminController}; do not couple callers to its representation when the owning type exposes an API.
     */
    @org.springframework.beans.factory.annotation.Qualifier("mcpTaskManagementService")
    private final McpTaskManagementService service;


    /**
     * 中文说明：执行 list 操作；该方法是 {@code McpTaskAdminController} 的调用入口，负责根据输入完成对应的运行时、管理面或协议处理。
     * English summary: Executes the list operation; this method is the invocation entry point on {@code McpTaskAdminController} and performs the corresponding runtime, management, or protocol work.
     *
     * 用法 / Usage: 调用方式 / Usage: {@code McpTaskAdminController.list(...)}。调用方应准备合法参数并处理返回值或异常；/ Call it with valid arguments and handle the return value or exception according to the owning component's lifecycle.
     * @param tenantId 参数 tenantId；parameter tenant id。
     * @param clientId 参数 客户端Id；parameter client id。
     * @return 返回 list 的处理结果；returns the result of the operation.
     */
    @PermissionResource(key = "yuheng.api.mcpTaskAdmin.list", type = ResourceType.API,
            labelPage = "gateway.permissions", labelKey = "api.mcpTaskAdmin.list",
            allowedPrincipals = PermissionManifest.PrincipalType.USER,
            oauthScopes = "yuheng:mcp:runtime:read")
    @RequiresPermissions(codes = "yuheng:mcp:runtime:read")
    @Operation(operationId = "admin.mcpTaskAdminController.list")
    @GetMapping
    public List<top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpTaskBO> list(
            @RequestParam String tenantId,
            @RequestParam(required = false) String clientId,
            @RequestAttribute(McpManagementAccessFilter.VERIFIED_IDENTITY_ATTRIBUTE) VerifiedManagementIdentityVO identity) {
        return service.tasks(tenantId, clientId, identity);
    }

    /**
     * 中文说明：执行 get 操作；该方法是 {@code McpTaskAdminController} 的调用入口，负责根据输入完成对应的运行时、管理面或协议处理。
     * English summary: Executes the get operation; this method is the invocation entry point on {@code McpTaskAdminController} and performs the corresponding runtime, management, or protocol work.
     *
     * 用法 / Usage: 调用方式 / Usage: {@code McpTaskAdminController.get(...)}。调用方应准备合法参数并处理返回值或异常；/ Call it with valid arguments and handle the return value or exception according to the owning component's lifecycle.
     * @param id 参数 id；parameter id。
     * @return 返回 get 的处理结果；returns the result of the operation.
     */
    @PermissionResource(key = "yuheng.api.mcpTaskAdmin.get", type = ResourceType.API,
            labelPage = "gateway.permissions", labelKey = "api.mcpTaskAdmin.get",
            allowedPrincipals = PermissionManifest.PrincipalType.USER,
            oauthScopes = "yuheng:mcp:runtime:read")
    @RequiresPermissions(codes = "yuheng:mcp:runtime:read")
    @Operation(operationId = "admin.mcpTaskAdminController.get")
    @GetMapping("/{id}")
    public top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpTaskBO get(@PathVariable String id,
            @RequestAttribute(McpManagementAccessFilter.VERIFIED_IDENTITY_ATTRIBUTE) VerifiedManagementIdentityVO identity) {
        return service.task(id, identity);
    }

    /**
     * 中文说明：执行 cancel 操作；该方法是 {@code McpTaskAdminController} 的调用入口，负责根据输入完成对应的运行时、管理面或协议处理。
     * English summary: Executes the cancel operation; this method is the invocation entry point on {@code McpTaskAdminController} and performs the corresponding runtime, management, or protocol work.
     *
     * 用法 / Usage: 调用方式 / Usage: {@code McpTaskAdminController.cancel(...)}。调用方应准备合法参数并处理返回值或异常；/ Call it with valid arguments and handle the return value or exception according to the owning component's lifecycle.
     * @param id 参数 id；parameter id。
     * @param request 参数 请求；parameter request。
     * @param idempotencyKey 参数 idempotency键；parameter idempotency key。
     * @param identity 已验证且仅本请求有效的 USER 身份；the verified USER identity bound to this request.
     * @param http 请求的安全关联信息；the request correlation headers.
     * @return 返回 cancel 的处理结果；returns the result of the operation.
     */
    @PermissionResource(key = "yuheng.api.mcpTaskAdmin.cancel", type = ResourceType.API,
            labelPage = "gateway.permissions", labelKey = "api.mcpTaskAdmin.cancel",
            allowedPrincipals = PermissionManifest.PrincipalType.USER,
            oauthScopes = "yuheng:mcp:write")
    @RequiresPermissions(codes = "yuheng:mcp:write")
    @Operation(operationId = "admin.mcpTaskAdminController.cancel")
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('SCOPE_yuheng:mcp:write') and (hasAnyAuthority('CAP_yuheng:mcp:write','CAP_*'))")
    public McpTaskCancelResultVO cancel(
            @PathVariable String id,
            @Valid @RequestBody McpTaskCancelRequestDTO request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestAttribute(McpManagementAccessFilter.VERIFIED_IDENTITY_ATTRIBUTE) VerifiedManagementIdentityVO identity,
            HttpServletRequest http) {
        return new McpTaskCancelResultVO(service.cancelTask(
                id,
                request.expectedRevision(),
                idempotencyKey,
                identity,
                http.getHeader("X-Egon-Request-Id"),
                http.getHeader("traceparent")
        ));
    }




}
