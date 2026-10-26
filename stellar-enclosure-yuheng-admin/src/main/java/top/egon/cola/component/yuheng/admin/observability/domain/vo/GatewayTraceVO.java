package top.egon.cola.component.yuheng.admin.observability.domain.vo;

import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;


import java.time.Instant;

/**
 * 中文说明：{@code GatewayTraceVO} 是不可变数据载体，位于当前 Gateway 模块的相关包中，负责GatewayTraceVO相关的职责与边界。
 * English summary: {@code GatewayTraceVO} is an immutable data carrier in the current Gateway module; it owns the trace summary-related responsibility and boundary.
 *
 * 用法 / Usage: 通过 Spring 容器或上层组件使用该类型；/ Use this type through the Spring container or an enclosing component; its public contract is the supported extension and invocation boundary.
 * @param eventId 参数 事件Id；parameter event id。
 * @param traceId 参数 traceId；parameter trace id。
 * @param startedAt 参数 startedAt；parameter started at。
 * @param durationMs 参数 durationMs；parameter duration ms。
 * @param protocol 参数 protocol；parameter protocol。
 * @param gatewayGroupId 参数 网关GroupId；parameter gateway group id。
 * @param operationKey 参数 操作键；parameter operation key。
 * @param statusCategory 参数 statusCategory；parameter status category。
 * @param engineInstanceId 参数 引擎InstanceId；parameter engine instance id。
 * @param providerService 参数 提供方服务；parameter provider service。
 */
public record GatewayTraceVO(
        /**
         * 中文说明：保存 事件Id 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by event id; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayTraceVO.eventId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.eventId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String eventId,
        /**
         * 中文说明：保存 traceId 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by trace id; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayTraceVO.traceId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.traceId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String traceId,
        /**
         * 中文说明：保存 startedAt 对应的状态、依赖或配置值；字段类型为 {@code Instant}，由 {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by started at; its type is {@code Instant}, and {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayTraceVO.startedAt", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.startedAt",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        Instant startedAt,
        /**
         * 中文说明：保存 durationMs 对应的状态、依赖或配置值；字段类型为 {@code long}，由 {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by duration ms; its type is {@code long}, and {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayTraceVO.durationMs", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.durationMs",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        long durationMs,
        /**
         * 中文说明：保存 protocol 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by protocol; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayTraceVO.protocol", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.protocol",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String protocol,
        /**
         * 中文说明：保存 网关GroupId 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by gateway group id; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayTraceVO.gatewayGroupId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.gatewayGroupId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String gatewayGroupId,
        /**
         * 中文说明：保存 操作键 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by operation key; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayTraceVO.operationKey", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.operationKey",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String operationKey,
        /**
         * 中文说明：保存 statusCategory 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by status category; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayTraceVO.statusCategory", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.statusCategory",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String statusCategory,
        /**
         * 中文说明：保存 引擎InstanceId 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by engine instance id; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayTraceVO.engineInstanceId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.engineInstanceId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String engineInstanceId,
        /**
         * 中文说明：保存 提供方服务 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by provider service; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.observability.domain.vo.GatewayTraceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayTraceVO.providerService", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.providerService",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String providerService
) {
}
