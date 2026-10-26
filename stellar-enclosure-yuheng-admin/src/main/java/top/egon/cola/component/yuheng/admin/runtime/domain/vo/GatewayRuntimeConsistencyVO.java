package top.egon.cola.component.yuheng.admin.runtime.domain.vo;

import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;


import java.time.Instant;
import java.util.List;

/**
 * 中文说明：{@code GatewayRuntimeConsistencyVO} 是不可变数据载体，位于当前 Gateway 模块的相关包中，负责运行时Consistency相关的职责与边界。
 * English summary: {@code GatewayRuntimeConsistencyVO} is an immutable data carrier in the current Gateway module; it owns the runtime consistency-related responsibility and boundary.
 *
 * 用法 / Usage: 通过 Spring 容器或上层组件使用该类型；/ Use this type through the Spring container or an enclosing component; its public contract is the supported extension and invocation boundary.
 * @param targetReleaseId 参数 target发布Id；parameter target release id。
 * @param targetReleaseStatus 参数 target发布Status；parameter target release status。
 * @param engineNodeCount 参数 引擎NodeCount；parameter engine node count。
 * @param readyEngineNodeCount 参数 ready引擎NodeCount；parameter ready engine node count。
 * @param consistent 参数 consistent；parameter consistent。
 * @param observedAt 参数 observedAt；parameter observed at。
 * @param source 参数 source；parameter source。
 * @param stale 参数 stale；parameter stale。
 * @param nodes 参数 nodes；parameter nodes。
 */
public record GatewayRuntimeConsistencyVO(
        /**
         * 中文说明：保存 target发布Id 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by target release id; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayRuntimeConsistencyVO.targetReleaseId", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.targetReleaseId",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String targetReleaseId,
        /**
         * 中文说明：保存 target发布Status 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by target release status; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayRuntimeConsistencyVO.targetReleaseStatus", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.targetReleaseStatus",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String targetReleaseStatus,
        /**
         * 中文说明：保存 引擎NodeCount 对应的状态、依赖或配置值；字段类型为 {@code int}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by engine node count; its type is {@code int}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayRuntimeConsistencyVO.engineNodeCount", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.engineNodeCount",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        int engineNodeCount,
        /**
         * 中文说明：保存 ready引擎NodeCount 对应的状态、依赖或配置值；字段类型为 {@code long}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by ready engine node count; its type is {@code long}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayRuntimeConsistencyVO.readyEngineNodeCount", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.readyEngineNodeCount",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.NUMBER,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        long readyEngineNodeCount,
        /**
         * 中文说明：保存 consistent 对应的状态、依赖或配置值；字段类型为 {@code boolean}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by consistent; its type is {@code boolean}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayRuntimeConsistencyVO.consistent", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.consistent",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        boolean consistent,
        /**
         * 中文说明：保存 observedAt 对应的状态、依赖或配置值；字段类型为 {@code Instant}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by observed at; its type is {@code Instant}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayRuntimeConsistencyVO.observedAt", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.observedAt",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        Instant observedAt,
        /**
         * 中文说明：保存 source 对应的状态、依赖或配置值；字段类型为 {@code String}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by source; its type is {@code String}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayRuntimeConsistencyVO.source", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.source",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        String source,
        /**
         * 中文说明：保存 stale 对应的状态、依赖或配置值；字段类型为 {@code boolean}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by stale; its type is {@code boolean}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayRuntimeConsistencyVO.stale", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.stale",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        boolean stale,
        /**
         * 中文说明：保存 nodes 对应的状态、依赖或配置值；字段类型为 {@code List<GatewayEngineNodeConsistencyVO>}，由 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} 在其生命周期内读取或更新。
         * English summary: Holds the state, dependency, or configuration represented by nodes; its type is {@code List<GatewayEngineNodeConsistencyVO>}, and {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} reads or updates it during its lifecycle.
         *
         * 用法 / Usage: 该字段通过 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} 的构造、初始化或业务方法使用；/ Access it through the construction, initialization, or business methods of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO}; do not couple callers to its representation when the owning type exposes an API.
         */
        @PermissionResource(key = "yuheng.field.GatewayRuntimeConsistencyVO.nodes", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.nodes",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = GatewayPermissionCodes.READ)
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
                plainRead = @PermissionGroup(codes = GatewayPermissionCodes.READ))
        List<GatewayEngineNodeConsistencyVO> nodes
) {

    /**
     * 中文说明：创建 {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} 实例，并接收构建该实例所需的依赖或初始数据；构造器参数定义了实例建立时必须满足的输入契约。
     * English summary: Creates an instance of {@code top.egon.cola.component.yuheng.admin.runtime.domain.vo.GatewayRuntimeConsistencyVO} from the dependencies or initial data required at construction time; its parameters define the initialization contract.
     *
     * 用法 / Usage: 由 Spring 容器、工厂或上层组件调用；/ Call it from the Spring container, a factory, or an enclosing component after validating the supplied dependencies.
     * @param targetReleaseId 参数 target发布Id；parameter target release id。
     * @param targetReleaseStatus 参数 target发布Status；parameter target release status。
     * @param engineNodeCount 参数 引擎NodeCount；parameter engine node count。
     * @param readyEngineNodeCount 参数 ready引擎NodeCount；parameter ready engine node count。
     * @param consistent 参数 consistent；parameter consistent。
     * @param observedAt 参数 observedAt；parameter observed at。
     * @param source 参数 source；parameter source。
     * @param stale 参数 stale；parameter stale。
     * @param nodes 参数 nodes；parameter nodes。
     */
    public GatewayRuntimeConsistencyVO {
        nodes = List.copyOf(nodes);
    }
}
