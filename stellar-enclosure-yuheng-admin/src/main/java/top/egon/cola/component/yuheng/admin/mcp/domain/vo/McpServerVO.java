package top.egon.cola.component.yuheng.admin.mcp.domain.vo;

import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;


import java.time.Instant;
import java.util.Set;

/**
 * 中文说明：{@code McpServerVO} 是不可变数据载体，位于当前 Gateway 模块的相关包中，负责服务器View相关的职责与边界。
 * English summary: {@code McpServerVO} is an immutable data carrier in the current Gateway module; it owns the server view-related responsibility and boundary.
 *
 * 用法 / Usage: 通过 Spring 容器或上层组件使用该类型；/ Use this type through the Spring container or an enclosing component; its public contract is the supported extension and invocation boundary.
 * @param id 参数 id；parameter id。
 * @param gatewayGroupId 参数 网关GroupId；parameter gateway group id。
 * @param serverCode 参数 服务器Code；parameter server code。
 * @param displayName 参数 displayName；parameter display name。
 * @param description 参数 description；parameter description。
 * @param instructions 参数 instructions；parameter instructions。
 * @param dialects 参数 dialects；parameter dialects。
 * @param resourceUri 参数 资源Uri；parameter resource uri。
 * @param listCacheTtlSeconds 参数 listCacheTtlSeconds；parameter list cache ttl seconds。
 * @param enabled 参数 enabled；parameter enabled。
 * @param revision 参数 revision；parameter revision。
 * @param createdAt 参数 createdAt；parameter created at。
 * @param updatedAt 参数 updatedAt；parameter updated at。
 */
public record McpServerVO(
        /**
         * 中文说明：保存 id 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by id; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpServerVO.id", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.id",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String id,
        /**
         * 中文说明：保存 网关GroupId 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by gateway group id; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpServerVO.gatewayGroupId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.gatewayGroupId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String gatewayGroupId,
        /**
         * 中文说明：保存 服务器Code 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by server code; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpServerVO.serverCode", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.serverCode",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String serverCode,
        /**
         * 中文说明：保存 displayName 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by display name; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpServerVO.displayName", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.displayName",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String displayName,
        /**
         * 中文说明：保存 description 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by description; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpServerVO.description", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.description",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String description,
        /**
         * 中文说明：保存 instructions 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by instructions; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpServerVO.instructions", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.instructions",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String instructions,
        /**
         * 中文说明：保存 dialects 对应的状态、依赖或配置值；字段类型为 {@code Set<String>}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by dialects; its type is {@code Set<String>}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpServerVO.dialects", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.dialects",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        Set<String> dialects,
        /**
         * 中文说明：保存 资源Uri 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by resource uri; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpServerVO.resourceUri", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.resourceUri",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        String resourceUri,
        /**
         * 中文说明：保存 listCacheTtlSeconds 对应的状态、依赖或配置值；字段类型为 {@code long}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by list cache ttl seconds; its type is {@code long}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpServerVO.listCacheTtlSeconds", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.listCacheTtlSeconds",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        long listCacheTtlSeconds,
        /**
         * 中文说明：保存 enabled 对应的状态、依赖或配置值；字段类型为 {@code boolean}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by enabled; its type is {@code boolean}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpServerVO.enabled", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.enabled",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        boolean enabled,
        /**
         * 中文说明：保存 revision 对应的状态、依赖或配置值；字段类型为 {@code long}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by revision; its type is {@code long}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpServerVO.revision", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.revision",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        long revision,
        /**
         * 中文说明：保存 createdAt 对应的状态、依赖或配置值；字段类型为 {@code Instant}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by created at; its type is {@code Instant}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpServerVO.createdAt", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.createdAt",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        Instant createdAt,
        /**
         * 中文说明：保存 updatedAt 对应的状态、依赖或配置值；字段类型为 {@code Instant}，由 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by updated at; its type is {@code Instant}, and {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.McpServerVO.updatedAt", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.updatedAt",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.MCP_READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.MCP_READ))
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        Instant updatedAt
) {
}
