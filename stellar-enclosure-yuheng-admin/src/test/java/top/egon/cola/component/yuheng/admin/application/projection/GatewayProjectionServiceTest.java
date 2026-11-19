package top.egon.cola.component.yuheng.admin.runtime.service;

import org.junit.jupiter.api.Test;
import top.egon.cola.component.yuheng.admin.group.domain.bo.GatewayGroupBO;
import top.egon.cola.component.yuheng.admin.group.repository.GatewayGroupRepository;
import top.egon.cola.component.yuheng.admin.release.service.GatewayReleaseService;
import top.egon.cola.component.yuheng.admin.release.domain.vo.GatewayReleaseVO;
import top.egon.cola.component.yuheng.admin.release.domain.enums.GatewayReleaseStatus;
import top.egon.cola.component.yuheng.admin.runtime.domain.dto.GatewayProviderQueryDTO;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GatewayProjectionServiceTest {
    private final GatewayGroupRepository groups=mock(GatewayGroupRepository.class);
    private final GatewayReleaseService releases=mock(GatewayReleaseService.class);
    private final GatewayProjectionService service=new GatewayProjectionService(groups,releases);
    private static final Instant NOW=Instant.parse("2026-11-19T00:00:00Z");
    @Test void currentProjectionNeverTakesTheRetiredManagementRegistryAsAuthority(){
        assertThat(Arrays.stream(GatewayProjectionService.class.getConstructors()).flatMap(ctor->Arrays.stream(ctor.getGenericParameterTypes()))
                .map(java.lang.reflect.Type::getTypeName)).noneMatch(name->name.contains("DdcManagementClient"));
    }
    @Test void engineRegistryIsUnavailableWithNoInventedTimestampOrFreshEmptySnapshot(){
        ownedGroup();var view=service.engineNodes("group-1");
        assertThat(view.value()).isEmpty();assertThat(view.stale()).isTrue();assertThat(view.observedAt()).isNull();
        assertThat(view.source()).isEqualTo("RETIRED_ENGINE_REGISTRY");assertThat(view.refreshError()).isEqualTo("ENGINE_REGISTRY_RETIRED");
        verifyNoInteractions(releases);
    }
    @Test void physicalServiceCatalogWithoutIndependentKubernetesProofIsExplicitlyUnknown(){
        var view=service.services(query());assertThat(view.stale()).isTrue();assertThat(view.observedAt()).isNull();
        assertThat(view.value().observedAt()).isNull();assertThat(view.value().services()).isEmpty();
        assertThat(view.refreshError()).isEqualTo("KUBERNETES_OBSERVATION_UNAVAILABLE");verifyNoInteractions(groups,releases);
    }
    @Test void providerInstancesNeverTreatLogicalNamespaceAsPhysicalNamespaceOrCreateLeases(){
        var selected=service.instances(query());var aggregate=service.instances("biz","orders","prod","logical-ns");
        for(var view:List.of(selected,aggregate)){assertThat(view.value()).isEmpty();assertThat(view.stale()).isTrue();
            assertThat(view.observedAt()).isNull();assertThat(view.refreshError()).isEqualTo("KUBERNETES_OBSERVATION_UNAVAILABLE");}
        verifyNoInteractions(groups,releases);
    }
    @Test void actualNativeReleaseMetadataIsRetainedButRoutesAreNeverEngineNodes(){
        ownedGroup();var release=new GatewayReleaseVO("native-release","group-1",1,null,null,GatewayReleaseStatus.SUCCESS,false,null,
                Map.of(),Map.of(),"publish",NOW,NOW,List.of(),
                List.of(new GatewayReleaseVO.ExposureOutcomeVO("101","3","3","OPEN","APPLIED",null)),
                List.of(new GatewayReleaseVO.ClusterTargetVO("cluster","HTTPRoute","yuheng-routes","yuheng-exposure-101","uid","2","8",true)));
        when(releases.history("group-1")).thenReturn(List.of(release));var view=service.runtimeConsistency("group-1");
        assertThat(view.targetReleaseId()).isEqualTo("native-release");assertThat(view.targetReleaseStatus()).isEqualTo("SUCCESS");
        assertThat(view.engineNodeCount()).isZero();assertThat(view.readyEngineNodeCount()).isZero();assertThat(view.nodes()).isEmpty();
        assertThat(view.consistent()).isFalse();assertThat(view.stale()).isTrue();assertThat(view.observedAt()).isNull();
        assertThat(release.clusterTargets().getFirst().converged()).isTrue();assertThat(release.exposureOutcomes().getFirst().result()).isEqualTo("APPLIED");
    }
    @Test void archivedReleaseSuccessAlsoCannotProveCurrentEngineReadiness(){
        ownedGroup();when(releases.history("group-1")).thenReturn(List.of(new GatewayReleaseVO("archive","group-1",1,null,null,
                GatewayReleaseStatus.SUCCESS,false,null,Map.of(),Map.of(),"old",NOW,NOW,List.of())));
        assertThat(service.runtimeConsistency("group-1").consistent()).isFalse();assertThat(service.runtimeConsistency("group-1").stale()).isTrue();
    }
    @Test void neverPublishedGroupRetainsUnknownTarget(){ownedGroup();when(releases.history("group-1")).thenReturn(List.of());
        var view=service.runtimeConsistency("group-1");assertThat(view.targetReleaseId()).isNull();assertThat(view.stale()).isTrue();}
    @Test void missingOwnedGroupIsNotFoundBeforeAnyReleaseRead(){
        when(groups.findByIdAndDeletedFalse("foreign")).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.runtimeConsistency("foreign")).isInstanceOf(RuntimeException.class);verifyNoInteractions(releases);
    }
    @Test void unavailableCountsNeverAdvertiseFreshHealthyZero(){var counts=service.scopeCounts("biz","orders","prod","logical-ns");
        assertThat(counts.stale()).isTrue();assertThat(counts.readyEngines()).isZero();assertThat(counts.activeProviders()).isZero();}
    @Test void historicalSelectorNormalizationRemainsPureAndSourceCompatible(){var selector=query().ddc();
        assertThat(selector.namespaceCode()).isEqualTo("logical-ns");assertThat(selector.protocol()).isEqualTo("http");}
    private void ownedGroup(){when(groups.findByIdAndDeletedFalse("group-1")).thenReturn(Optional.of(mock(GatewayGroupBO.class)));}
    private static GatewayProviderQueryDTO query(){return new GatewayProviderQueryDTO("biz","orders","prod","logical-ns","HTTP_PROVIDER","HTTP","orders","default","1");}
}
