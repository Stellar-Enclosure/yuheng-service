package top.egon.cola.component.yuheng.admin.exposure.domain.vo;

import top.egon.cola.component.yuheng.admin.permission.GatewayPermissionCodes;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionManifest;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.ResourceType;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.FieldPermissions;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionGroup;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.annotation.PermissionResource;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;

import java.time.Instant;
import java.util.List;

/** Public control projection; business generations and Kubernetes generations are independent strings. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "GatewayUrlExposure")
public class GatewayUrlExposureVO {

    @PermissionResource(key = "yuheng.field.GatewayUrlExposureVO.exposureId", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.exposureId",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
    @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "810001")
    private String exposureId;

    @PermissionResource(key = "yuheng.field.GatewayUrlExposureVO.gatewayGroupId", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.gatewayGroupId",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
    @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", example = "800001")
    private String gatewayGroupId;

    @PermissionResource(key = "yuheng.field.GatewayUrlExposureVO.routeId", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.routeId",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
    @NotBlank @Size(max = 128)
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String routeId;

    @PermissionResource(key = "yuheng.field.GatewayUrlExposureVO.url", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.url",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
    @NotBlank @Size(max = 4096)
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uri", description = "Display-only managed HTTPS URL.")
    private String url;

    @PermissionResource(key = "yuheng.field.GatewayUrlExposureVO.method", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.method",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
    @NotBlank @Size(max = 16)
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String method;

    @PermissionResource(key = "yuheng.field.GatewayUrlExposureVO.desiredState", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.desiredState",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
    @NotNull
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private GatewayUrlExposureStateEnum desiredState;

    @PermissionResource(key = "yuheng.field.GatewayUrlExposureVO.observedState", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.observedState",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
    @NotBlank @Pattern(regexp = "OPEN|CLOSED|WITHDRAWN|UNKNOWN")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String observedState;

    @PermissionResource(key = "yuheng.field.GatewayUrlExposureVO.desiredGeneration", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.desiredGeneration",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
    @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string")
    private String desiredGeneration;

    @PermissionResource(key = "yuheng.field.GatewayUrlExposureVO.observedGeneration", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.observedGeneration",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
    @NotBlank @Pattern(regexp = "0|[1-9][0-9]{0,18}")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string")
    private String observedGeneration;

    @PermissionResource(key = "yuheng.field.GatewayUrlExposureVO.reconcileStatus", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.reconcileStatus",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
    @NotBlank @Pattern(regexp = "PENDING|RECONCILING|SYNCED|FAILED")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String reconcileStatus;

    @PermissionResource(key = "yuheng.field.GatewayUrlExposureVO.releaseId", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.releaseId",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
    @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string")
    private String releaseId;

    @PermissionResource(key = "yuheng.field.GatewayUrlExposureVO.contentSha256", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.contentSha256",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
    @NotBlank @Pattern(regexp = "[0-9a-f]{64}")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String contentSha256;

    @PermissionResource(key = "yuheng.field.GatewayUrlExposureVO.terminal", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.terminal",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
            plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
    @NotNull
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean terminal;

    @PermissionResource(key = "yuheng.field.GatewayUrlExposureVO.kubernetesRoute", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.kubernetesRoute",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.OBJECT,
            plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
    @Valid
    @Schema(nullable = true)
    private KubernetesRouteVO kubernetesRoute;

    @PermissionResource(key = "yuheng.field.GatewayUrlExposureVO.conditions", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.conditions",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.ARRAY,
            plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
    @NotNull @Size(max = 64)
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private List<@Valid ConditionVO> conditions;

    @PermissionResource(key = "yuheng.field.GatewayUrlExposureVO.lastErrorCode", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.lastErrorCode",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
    @Size(max = 128)
    @Schema(nullable = true)
    private String lastErrorCode;

    @PermissionResource(key = "yuheng.field.GatewayUrlExposureVO.updatedAt", type = ResourceType.FIELD,
            labelPage = "gateway.permissions", labelKey = "field.updatedAt",
            allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
            oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
    @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
            plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
    @NotNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", timezone = "UTC")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date-time")
    private Instant updatedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "GatewayUrlExposureKubernetesRoute")
    public static class KubernetesRouteVO {
        @PermissionResource(key = "yuheng.field.GatewayUrlExposureKubernetesRouteVO.kind", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.kind",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
        @NotBlank @Pattern(regexp = "HTTPRoute")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String kind;

        @PermissionResource(key = "yuheng.field.GatewayUrlExposureKubernetesRouteVO.namespace", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.namespace",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
        @NotBlank @Size(max = 63)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String namespace;

        @PermissionResource(key = "yuheng.field.GatewayUrlExposureKubernetesRouteVO.name", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.name",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
        @NotBlank @Size(max = 253)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String name;

        @PermissionResource(key = "yuheng.field.GatewayUrlExposureKubernetesRouteVO.uid", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.uid",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
        @NotBlank @Size(max = 128)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String uid;

        @PermissionResource(key = "yuheng.field.GatewayUrlExposureKubernetesRouteVO.resourceVersion", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.resourceVersion",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
        @NotBlank @Size(max = 128)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String resourceVersion;

        @PermissionResource(key = "yuheng.field.GatewayUrlExposureKubernetesRouteVO.metadataGeneration", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.metadataGeneration",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string")
        private String metadataGeneration;

        @PermissionResource(key = "yuheng.field.GatewayUrlExposureKubernetesRouteVO.controllerName", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.controllerName",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
        @Size(max = 253)
        @Schema(nullable = true)
        private String controllerName;

        @PermissionResource(key = "yuheng.field.GatewayUrlExposureKubernetesRouteVO.conditionObservedGeneration", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.conditionObservedGeneration",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
        @Pattern(regexp = "0|[1-9][0-9]{0,18}")
        @Schema(nullable = true, type = "string")
        private String conditionObservedGeneration;

        @PermissionResource(key = "yuheng.field.GatewayUrlExposureKubernetesRouteVO.attached", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.attached",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.BOOLEAN,
                plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private Boolean attached;

    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "GatewayUrlExposureCondition")
    public static class ConditionVO {
        @PermissionResource(key = "yuheng.field.GatewayUrlExposureConditionVO.type", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.type",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
        @NotBlank @Size(max = 128)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String type;

        @PermissionResource(key = "yuheng.field.GatewayUrlExposureConditionVO.status", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.status",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
        @NotBlank @Pattern(regexp = "True|False|Unknown")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String status;

        @PermissionResource(key = "yuheng.field.GatewayUrlExposureConditionVO.reason", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.reason",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
        @NotBlank @Size(max = 128)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String reason;

        @PermissionResource(key = "yuheng.field.GatewayUrlExposureConditionVO.message", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.message",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
        @NotNull @Size(max = 1024)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String message;

        @PermissionResource(key = "yuheng.field.GatewayUrlExposureConditionVO.observedGeneration", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.observedGeneration",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
        @NotBlank @Pattern(regexp = "0|[1-9][0-9]{0,18}")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string")
        private String observedGeneration;

        @PermissionResource(key = "yuheng.field.GatewayUrlExposureConditionVO.lastTransitionTime", type = ResourceType.FIELD,
                labelPage = "gateway.permissions", labelKey = "field.lastTransitionTime",
                allowedPrincipals = {PermissionManifest.PrincipalType.USER, PermissionManifest.PrincipalType.SERVICE},
                oauthScopes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE})
        @FieldPermissions(valueType = PermissionManifest.JsonFieldType.STRING,
                plainRead = @PermissionGroup(mode = top.egon.cola.platform.tianquan.jianshen.contract.permission.PermissionMode.ANY,
                    codes = {GatewayPermissionCodes.READ, GatewayPermissionCodes.EXPOSURES_WRITE}))
        @NotNull
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", timezone = "UTC")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date-time")
        private Instant lastTransitionTime;

    }
}
