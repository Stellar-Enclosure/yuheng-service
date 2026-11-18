package top.egon.cola.component.yuheng.admin.exposure.domain.bo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import top.egon.cola.component.yuheng.admin.exposure.domain.enums.GatewayUrlExposureStateEnum;

import java.time.Instant;
import java.time.LocalDateTime;

/** Current control facts; management generations never represent Kubernetes generation. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Accessors(chain = true)
public class GatewayUrlExposureBO {

    private Long id;
    private Long tenantId;
    private String createUserId;
    private Instant createTime;
    private String updateUserId;
    private Instant updateTime;
    private LocalDateTime deletedAt;
    private Long version;
    private Long ownerTenantId;
    private Long gatewayGroupId;
    private String routeId;
    private String clusterId;
    private String gatewayNamespace;
    private String gatewayName;
    private String gatewayUid;
    private String listenerName;
    private String hostname;
    private String httpMethod;
    private String pathMatchType;
    private String pathValue;
    private String routeKind;
    private String routeNamespace;
    private String routeName;
    private String routeUid;
    private GatewayUrlExposureStateEnum desiredState;
    private Long desiredGeneration;
    private String observedState;
    private Long observedGeneration;
    private String reconcileStatus;
    private Long releaseId;
    private String contentSha256;
    private String compilerProfileId;
    private JsonNode observation;
    private String lastErrorCode;
    private String changeReason;
    private Instant withdrawnAt;
}
