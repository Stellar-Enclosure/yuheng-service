package top.egon.cola.component.yuheng.admin.exposure.domain.po;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationGroups.Insert;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationGroups.Persisted;
import top.egon.cola.component.common.mybatis.model.EgonModel;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;
import top.egon.cola.component.yuheng.admin.shared.dao.typehandler.GatewayJsonbTypeHandler;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Fixed technical-tenant control row; inherited fields remain owned by EgonModel. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName(value = "gateway_url_exposure_t0", schema = "public", autoResultMap = true)
public class GatewayUrlExposurePO extends EgonModel<GatewayUrlExposurePO> {

    private static final String DNS_LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    private static final String DNS_SUBDOMAIN = DNS_LABEL + "(?:[.]" + DNS_LABEL + ")*";

    @TableField("owner_tenant_id")
    @NotNull(groups = {Insert.class, Persisted.class})
    @Positive
    private Long ownerTenantId;

    @TableField("gateway_group_id")
    @NotNull(groups = {Insert.class, Persisted.class})
    @Positive
    private Long gatewayGroupId;

    @TableField("route_id")
    @NotBlank(groups = {Insert.class, Persisted.class})
    @Size(max = 128)
    private String routeId;

    @TableField("cluster_id")
    @NotBlank(groups = {Insert.class, Persisted.class})
    @Size(max = 128)
    @Pattern(regexp = "[\\x21-\\x7e]+")
    private String clusterId;

    @TableField("gateway_namespace")
    @NotBlank(groups = {Insert.class, Persisted.class})
    @Size(max = 63)
    @Pattern(regexp = DNS_LABEL)
    private String gatewayNamespace;

    @TableField("gateway_name")
    @NotBlank(groups = {Insert.class, Persisted.class})
    @Size(max = 253)
    @Pattern(regexp = DNS_SUBDOMAIN)
    private String gatewayName;

    @TableField("gateway_uid")
    @NotBlank(groups = {Insert.class, Persisted.class})
    @Size(max = 128)
    private String gatewayUid;

    @TableField("listener_name")
    @NotBlank(groups = {Insert.class, Persisted.class})
    @Size(max = 253)
    @Pattern(regexp = "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")
    private String listenerName;

    @TableField("hostname")
    @NotBlank(groups = {Insert.class, Persisted.class})
    @Size(max = 253)
    @Pattern(regexp = "(?:\\*[.])?" + DNS_SUBDOMAIN)
    private String hostname;

    @TableField("http_method")
    @NotBlank(groups = {Insert.class, Persisted.class})
    @Size(max = 16)
    @Pattern(regexp = "\\*|GET|HEAD|POST|PUT|DELETE|CONNECT|OPTIONS|TRACE|PATCH")
    private String httpMethod;

    @TableField("path_match_type")
    @NotBlank(groups = {Insert.class, Persisted.class})
    @Pattern(regexp = "EXACT|PATH_PREFIX")
    private String pathMatchType;

    @TableField("path_value")
    @NotBlank(groups = {Insert.class, Persisted.class})
    @Size(max = 1024)
    @Pattern(regexp = "/[\\x21-\\x7e]*")
    private String pathValue;

    @TableField("route_kind")
    @NotBlank(groups = {Insert.class, Persisted.class})
    @Pattern(regexp = "HTTPRoute")
    private String routeKind;

    @TableField("route_namespace")
    @NotBlank(groups = {Insert.class, Persisted.class})
    @Size(max = 63)
    @Pattern(regexp = DNS_LABEL)
    private String routeNamespace;

    @TableField("route_name")
    @NotBlank(groups = {Insert.class, Persisted.class})
    @Size(max = 63)
    @Pattern(regexp = DNS_LABEL)
    private String routeName;

    @TableField("route_uid")
    @Size(max = 128)
    private String routeUid;

    @TableField("desired_state")
    @NotNull(groups = {Insert.class, Persisted.class})
    private GatewayUrlExposureStateEnum desiredState;

    @TableField("desired_generation")
    @NotNull(groups = {Insert.class, Persisted.class})
    @Min(1)
    private Long desiredGeneration;

    @TableField("observed_state")
    @NotBlank(groups = {Insert.class, Persisted.class})
    @Pattern(regexp = "UNKNOWN|OPEN|CLOSED|WITHDRAWN")
    private String observedState;

    @TableField("observed_generation")
    @NotNull(groups = {Insert.class, Persisted.class})
    @Min(0)
    private Long observedGeneration;

    @TableField("reconcile_status")
    @NotBlank(groups = {Insert.class, Persisted.class})
    @Pattern(regexp = "PENDING|RECONCILING|SYNCED|FAILED")
    private String reconcileStatus;

    @TableField("release_id")
    @NotNull(groups = {Insert.class, Persisted.class})
    @Positive
    private Long releaseId;

    @TableField("content_sha256")
    @NotBlank(groups = {Insert.class, Persisted.class})
    @Pattern(regexp = "[0-9a-f]{64}")
    private String contentSha256;

    @TableField("compiler_profile_id")
    @NotBlank(groups = {Insert.class, Persisted.class})
    @Size(max = 128)
    private String compilerProfileId;

    @TableField(value = "observation", typeHandler = GatewayJsonbTypeHandler.class,
            insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    @NotNull(groups = {Insert.class, Persisted.class})
    private JsonNode observation;

    @TableField(value = "last_error_code", updateStrategy = FieldStrategy.ALWAYS)
    @Size(max = 128)
    private String lastErrorCode;

    @TableField("change_reason")
    @NotBlank(groups = {Insert.class, Persisted.class})
    @Size(max = 1024)
    private String changeReason;

    @TableField("withdrawn_at")
    private Instant withdrawnAt;

    @AssertTrue(message = "URL exposure state and generations are inconsistent")
    public boolean isStateConsistent() {
        if (desiredState != null && (desiredState == GatewayUrlExposureStateEnum.WITHDRAWN) != (withdrawnAt != null)) {
            return false;
        }
        if (desiredGeneration != null && observedGeneration != null && observedGeneration > desiredGeneration) {
            return false;
        }
        return !"SYNCED".equals(reconcileStatus) || desiredState == null || desiredGeneration == null
                || observedGeneration == null || observedState == null
                || desiredGeneration.equals(observedGeneration) && desiredState.wireValue().equals(observedState);
    }

    @AssertTrue(message = "URL exposure observation must be a bounded schema-v1 object")
    public boolean isObservationValid() {
        return observation == null || observation.isObject() && observation.path("schemaVersion").isIntegralNumber()
                && observation.path("schemaVersion").canConvertToInt() && observation.path("schemaVersion").intValue() == 1
                && observation.path("conditions").isArray()
                && observation.has("kubernetesRoute")
                && (observation.get("kubernetesRoute").isNull() || observation.get("kubernetesRoute").isObject())
                && observation.toString().getBytes(StandardCharsets.UTF_8).length <= 65_536;
    }
}
