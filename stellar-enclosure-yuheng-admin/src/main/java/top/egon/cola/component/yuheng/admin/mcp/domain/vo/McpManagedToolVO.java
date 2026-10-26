package top.egon.cola.component.yuheng.admin.mcp.domain.vo;

import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

/**
 * 中文说明：{@code McpManagedToolVO} 是不可变数据载体，位于当前 Gateway 模块的相关包中，负责Managed工具View相关的职责与边界。
 * English summary: {@code McpManagedToolVO} is an immutable data carrier in the current Gateway module; it owns the managed tool view-related responsibility and boundary.
 *
 * 用法 / Usage: 通过 Spring 容器或上层组件使用该类型；/ Use this type through the Spring container or an enclosing component; its public contract is the supported extension and invocation boundary.
 * @param toolId 参数 工具Id；parameter tool id。
 * @param gatewayGroupId 参数 网关GroupId；parameter gateway group id。
 * @param operationId 参数 操作Id；parameter operation id。
 * @param operationKey 参数 操作键；parameter operation key。
 * @param name 参数 name；parameter name。
 * @param description 参数 description；parameter description。
 * @param operationProtocol 参数 操作Protocol；parameter operation protocol。
 * @param inputSchema 参数 input模式；parameter input schema。
 * @param outputSchema 参数 output模式；parameter output schema。
 * @param codeServerId 参数 code服务器Id；parameter code server id。
 * @param codeServerCode 参数 code服务器Code；parameter code server code。
 * @param serverId 参数 服务器Id；parameter server id。
 * @param serverCode 参数 服务器Code；parameter server code。
 * @param codePermissions 参数 codePermissions；parameter code permissions。
 * @param additionalPermissions 参数 additionalPermissions；parameter additional permissions。
 * @param effectivePermissions 参数 effectivePermissions；parameter effective permissions。
 * @param codeRiskLevel 参数 codeRiskLevel；parameter code risk level。
 * @param minimumRiskLevel 参数 minimumRiskLevel；parameter minimum risk level。
 * @param effectiveRiskLevel 参数 effectiveRiskLevel；parameter effective risk level。
 * @param idempotent 参数 idempotent；parameter idempotent。
 * @param enabled 参数 enabled；parameter enabled。
 * @param overrideRevision 参数 overrideRevision；parameter override revision。
 */
public record McpManagedToolVO(
        /**
         * 中文说明：保存 工具Id 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by tool id; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.toolId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.toolId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String toolId,
        /**
         * 中文说明：保存 网关GroupId 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by gateway group id; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.gatewayGroupId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.gatewayGroupId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String gatewayGroupId,
        /**
         * 中文说明：保存 操作Id 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by operation id; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.operationId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.operationId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String operationId,
        /**
         * 中文说明：保存 操作键 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by operation key; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.operationKey", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.operationKey",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String operationKey,
        /**
         * 中文说明：保存 name 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by name; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.name", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.name",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String name,
        /**
         * 中文说明：保存 description 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by description; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.description", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.description",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String description,
        /**
         * 中文说明：保存 操作Protocol 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by operation protocol; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.operationProtocol", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.operationProtocol",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String operationProtocol,
        /**
         * 中文说明：保存 input模式 对应的状态、依赖或配置值；字段类型为 {@code Object}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by input schema; its type is {@code Object}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.inputSchema", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.inputSchema",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.OBJECT,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        @Schema(type = "object",
                additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
        Object inputSchema,
        /**
         * 中文说明：保存 output模式 对应的状态、依赖或配置值；字段类型为 {@code Object}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by output schema; its type is {@code Object}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.outputSchema", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.outputSchema",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.OBJECT,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        @Schema(type = "object",
                additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
        Object outputSchema,
        /**
         * 中文说明：保存 code服务器Id 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by code server id; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.codeServerId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.codeServerId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String codeServerId,
        /**
         * 中文说明：保存 code服务器Code 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by code server code; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.codeServerCode", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.codeServerCode",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String codeServerCode,
        /**
         * 中文说明：保存 服务器Id 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by server id; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.serverId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.serverId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String serverId,
        /**
         * 中文说明：保存 服务器Code 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by server code; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.serverCode", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.serverCode",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String serverCode,
        /**
         * 中文说明：保存 codePermissions 对应的状态、依赖或配置值；字段类型为 {@code Set<String>}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by code permissions; its type is {@code Set<String>}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.codePermissions", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.codePermissions",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        Set<String> codePermissions,
        /**
         * 中文说明：保存 additionalPermissions 对应的状态、依赖或配置值；字段类型为 {@code Set<String>}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by additional permissions; its type is {@code Set<String>}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.additionalPermissions", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.additionalPermissions",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        Set<String> additionalPermissions,
        /**
         * 中文说明：保存 effectivePermissions 对应的状态、依赖或配置值；字段类型为 {@code Set<String>}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by effective permissions; its type is {@code Set<String>}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.effectivePermissions", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.effectivePermissions",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        Set<String> effectivePermissions,
        /**
         * 中文说明：保存 codeRiskLevel 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by code risk level; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.codeRiskLevel", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.codeRiskLevel",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String codeRiskLevel,
        /**
         * 中文说明：保存 minimumRiskLevel 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by minimum risk level; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.minimumRiskLevel", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.minimumRiskLevel",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String minimumRiskLevel,
        /**
         * 中文说明：保存 effectiveRiskLevel 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by effective risk level; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.effectiveRiskLevel", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.effectiveRiskLevel",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String effectiveRiskLevel,
        /**
         * 中文说明：保存 idempotent 对应的状态、依赖或配置值；字段类型为 {@code boolean}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by idempotent; its type is {@code boolean}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.idempotent", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.idempotent",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        boolean idempotent,
        /**
         * 中文说明：保存 enabled 对应的状态、依赖或配置值；字段类型为 {@code boolean}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by enabled; its type is {@code boolean}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.enabled", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.enabled",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        boolean enabled,
        /**
         * 中文说明：保存 overrideRevision 对应的状态、依赖或配置值；字段类型为 {@code long}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by override revision; its type is {@code long}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpManagedToolVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpManagedToolVO.overrideRevision", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.overrideRevision",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        long overrideRevision
) {
}
