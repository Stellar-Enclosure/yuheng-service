package top.egon.cola.component.yuheng.admin.runtime.service;

import jakarta.validation.constraints.NotBlank;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.tianshu.model.management.DdcManagementConfigClientInstance;
import top.egon.cola.component.tianshu.model.management.DdcManagementServiceCatalog;
import top.egon.cola.component.yuheng.admin.group.repository.GatewayGroupRepository;
import top.egon.cola.component.yuheng.admin.release.service.GatewayReleaseService;
import top.egon.cola.component.yuheng.admin.runtime.domain.dto.GatewayProviderQueryDTO;
import top.egon.cola.component.yuheng.admin.runtime.domain.vo.*;
import top.egon.cola.component.yuheng.admin.shared.domain.exception.GatewayAdminNotFoundException;
import java.util.List;
import java.util.Objects;

/**
 * Preserves the old projection ABI while making retired physical-registry evidence explicitly unavailable.
 * Current URL evidence remains in release exposureOutcomes/clusterTargets and the Exposure Query API;
 * a Route is never counted as an engine, provider process or physical lease.
 */
@Slf4j
@Validated
@Service("gatewayProjectionService")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class GatewayProjectionService {
    public static final String RETIRED_SOURCE = "RETIRED_ENGINE_REGISTRY";
    public static final String WORKLOAD_SOURCE = "KUBERNETES_OBSERVATION";
    public static final String OBSERVATION_UNAVAILABLE = "KUBERNETES_OBSERVATION_UNAVAILABLE";
    @NonNull @Qualifier("mpGatewayGroupRepository") private final GatewayGroupRepository groups;
    @NonNull @Qualifier("gatewayReleaseService") private final GatewayReleaseService releases;

    public GatewayProjectionEnvelopeVO<List<DdcManagementConfigClientInstance>> engineNodes(@NotBlank String gatewayGroupId) {
        requireGroup(gatewayGroupId);
        return unavailable(List.of(), RETIRED_SOURCE, "ENGINE_REGISTRY_RETIRED");
    }

    public GatewayProjectionEnvelopeVO<DdcManagementServiceCatalog> services(GatewayProviderQueryDTO query) {
        Objects.requireNonNull(query,"query").ddc(); // Retains only the existing selector normalization; no management client is used.
        return unavailable(new DdcManagementServiceCatalog(0,null,List.of()), WORKLOAD_SOURCE, OBSERVATION_UNAVAILABLE);
    }

    public GatewayProjectionEnvelopeVO<List<GatewayProviderInstanceVO>> instances(GatewayProviderQueryDTO query) {
        Objects.requireNonNull(query,"query").ddc();
        return unavailable(List.of(), WORKLOAD_SOURCE, OBSERVATION_UNAVAILABLE);
    }

    public GatewayProjectionEnvelopeVO<List<GatewayProviderInstanceVO>> instances(String bizCode,String appCode,String env,String namespace) {
        return unavailable(List.of(), WORKLOAD_SOURCE, OBSERVATION_UNAVAILABLE);
    }

    /** Target status is read through the current release API; retired engine evidence cannot prove URL convergence. */
    public GatewayRuntimeConsistencyVO runtimeConsistency(@NotBlank String gatewayGroupId) {
        requireGroup(gatewayGroupId);
        var history=releases.history(gatewayGroupId);
        var target=history.isEmpty()?null:history.getFirst();
        return new GatewayRuntimeConsistencyVO(target==null?null:target.releaseId(),target==null?null:target.status().name(),
                0,0,false,null,RETIRED_SOURCE,true,List.of());
    }

    /** The numeric ABI is retained; stale=true distinguishes unavailable counts from a fresh observation of zero. */
    public GatewayProjectionCounts scopeCounts(String bizCode,String appCode,String env,String namespace) {
        return new GatewayProjectionCounts(0,0,0,0,0,true);
    }

    private void requireGroup(String id) {
        groups.findByIdAndDeletedFalse(id).orElseThrow(()->new GatewayAdminNotFoundException("gateway group was not found"));
    }
    private static <T> GatewayProjectionEnvelopeVO<T> unavailable(T placeholder,String source,String reason) {
        return new GatewayProjectionEnvelopeVO<>(placeholder,null,source,true,reason);
    }
}
