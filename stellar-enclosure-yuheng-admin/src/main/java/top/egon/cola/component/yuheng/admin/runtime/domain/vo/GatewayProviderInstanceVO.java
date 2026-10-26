package top.egon.cola.component.yuheng.admin.runtime.domain.vo;

import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;


import java.time.Instant;
import java.util.Map;

/**
 * 中文说明：{@code GatewayProviderInstanceVO} 是不可变数据载体，位于当前 Gateway 模块的相关包中，负责提供方Instance投影相关的职责与边界。
 * English summary: {@code GatewayProviderInstanceVO} is an immutable data carrier in the current Gateway module; it owns the provider instance projection-related responsibility and boundary.
 *
 * 用法 / Usage: 通过 Spring 容器或上层组件使用该类型；/ Use this type through the Spring container or an enclosing component; its public contract is the supported extension and invocation boundary.
 * @param serviceKey 参数 服务键；parameter service key。
 * @param protocol 参数 protocol；parameter protocol。
 * @param serviceName 参数 服务Name；parameter service name。
 * @param group 参数 group；parameter group。
 * @param version 参数 version；parameter version。
 * @param instanceId 参数 instanceId；parameter instance id。
 * @param leaseId 参数 租约Id；parameter lease id。
 * @param host 参数 host；parameter host。
 * @param port 参数 port；parameter port。
 * @param region 参数 region；parameter region。
 * @param zone 参数 zone；parameter zone。
 * @param weight 参数 weight；parameter weight。
 * @param tags 参数 tags；parameter tags。
 * @param definitionSetId 参数 定义SetId；parameter definition set id。
 * @param status 参数 status；parameter status。
 * @param expireAt 参数 expireAt；parameter expire at。
 * @param observedAt 参数 observedAt；parameter observed at。
 */
public record GatewayProviderInstanceVO(
        /**
         * 中文说明：保存 服务键 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by service key; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayProviderInstanceVO.serviceKey", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.serviceKey",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String serviceKey,
        /**
         * 中文说明：保存 protocol 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by protocol; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayProviderInstanceVO.protocol", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.protocol",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String protocol,
        /**
         * 中文说明：保存 服务Name 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by service name; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayProviderInstanceVO.serviceName", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.serviceName",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String serviceName,
        /**
         * 中文说明：保存 group 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by group; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayProviderInstanceVO.group", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.group",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String group,
        /**
         * 中文说明：保存 version 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by version; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayProviderInstanceVO.version", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.version",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String version,
        /**
         * 中文说明：保存 instanceId 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by instance id; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayProviderInstanceVO.instanceId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.instanceId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String instanceId,
        /**
         * 中文说明：保存 租约Id 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by lease id; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayProviderInstanceVO.leaseId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.leaseId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String leaseId,
        /**
         * 中文说明：保存 host 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by host; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayProviderInstanceVO.host", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.host",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String host,
        /**
         * 中文说明：保存 port 对应的状态、依赖或配置值；字段类型为 {@code int}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by port; its type is {@code int}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayProviderInstanceVO.port", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.port",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        int port,
        /**
         * 中文说明：保存 region 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by region; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayProviderInstanceVO.region", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.region",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String region,
        /**
         * 中文说明：保存 zone 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by zone; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayProviderInstanceVO.zone", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.zone",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String zone,
        /**
         * 中文说明：保存 weight 对应的状态、依赖或配置值；字段类型为 {@code Integer}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by weight; its type is {@code Integer}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayProviderInstanceVO.weight", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.weight",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        Integer weight,
        /**
         * 中文说明：保存 tags 对应的状态、依赖或配置值；字段类型为 {@code Map<String, String>}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by tags; its type is {@code Map<String, String>}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayProviderInstanceVO.tags", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.tags",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.OBJECT,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        Map<String, String> tags,
        /**
         * 中文说明：保存 定义SetId 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by definition set id; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayProviderInstanceVO.definitionSetId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.definitionSetId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String definitionSetId,
        /**
         * 中文说明：保存 status 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by status; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayProviderInstanceVO.status", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.status",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String status,
        /**
         * 中文说明：保存 expireAt 对应的状态、依赖或配置值；字段类型为 {@code Instant}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by expire at; its type is {@code Instant}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayProviderInstanceVO.expireAt", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.expireAt",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        Instant expireAt,
        /**
         * 中文说明：保存 observedAt 对应的状态、依赖或配置值；字段类型为 {@code Instant}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by observed at; its type is {@code Instant}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayProviderInstanceVO.observedAt", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.observedAt",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        Instant observedAt
) {

    /**
     * 中文说明：创建 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} 实例，并接收构建该实例所需的依赖或初始数据；构造器参数定义了实例建立时必须满足的输入契约。
     * English summary: Creates an instance of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayProviderInstanceVO} from the dependencies or initial data required at construction time; its parameters define the initialization contract.
     *
     * 用法 / Usage: 由 Spring 容器、工厂或上层组件调用；/ Call it from the Spring container, a factory, or an enclosing component after validating the supplied dependencies.
     * @param serviceKey 参数 服务键；parameter service key。
     * @param protocol 参数 protocol；parameter protocol。
     * @param serviceName 参数 服务Name；parameter service name。
     * @param group 参数 group；parameter group。
     * @param version 参数 version；parameter version。
     * @param instanceId 参数 instanceId；parameter instance id。
     * @param leaseId 参数 租约Id；parameter lease id。
     * @param host 参数 host；parameter host。
     * @param port 参数 port；parameter port。
     * @param region 参数 region；parameter region。
     * @param zone 参数 zone；parameter zone。
     * @param weight 参数 weight；parameter weight。
     * @param tags 参数 tags；parameter tags。
     * @param definitionSetId 参数 定义SetId；parameter definition set id。
     * @param status 参数 status；parameter status。
     * @param expireAt 参数 expireAt；parameter expire at。
     * @param observedAt 参数 observedAt；parameter observed at。
     */
    public GatewayProviderInstanceVO {
        tags = Map.copyOf(tags);
    }
}
