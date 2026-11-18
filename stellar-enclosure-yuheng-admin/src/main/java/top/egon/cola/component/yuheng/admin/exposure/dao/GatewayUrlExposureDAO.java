package top.egon.cola.component.yuheng.admin.exposure.dao;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Param;
import top.egon.cola.component.common.mybatis.extension.EgonColaExplicitTenantScopeMapper;
import top.egon.cola.component.common.mybatis.extension.EgonColaMapper;
import top.egon.cola.component.yuheng.admin.exposure.domain.po.GatewayUrlExposurePO;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/** Fixed-zero statements; owner predicates are mandatory outside the internal publication-domain scan. */
@InterceptorIgnore(tenantLine = "true")
@EgonColaExplicitTenantScopeMapper(technicalModel = GatewayUrlExposurePO.class,
        technicalTable = "public.gateway_url_exposure_t0")
public interface GatewayUrlExposureDAO extends EgonColaMapper<GatewayUrlExposurePO> {

    GatewayUrlExposurePO findOwned(@Param("owner") long owner, @Param("id") long id);
    GatewayUrlExposurePO findOwnedRoute(@Param("owner") long owner, @Param("groupId") long groupId,
                                       @Param("routeId") String routeId);
    List<GatewayUrlExposurePO> scanBinding(@Param("cluster") String cluster,
            @Param("gatewayNamespace") String gatewayNamespace, @Param("gatewayName") String gatewayName,
            @Param("listenerName") String listenerName, @Param("afterId") long afterId, @Param("limit") int limit);
    int lockPublicationDomain(@Param("lockKey") long lockKey);
    int compareAndSetDesired(@Param("record") GatewayUrlExposurePO record,
            @Param("oldState") String oldState, @Param("generation") long generation);
    int compareAndSetContent(@Param("record") GatewayUrlExposurePO record,
            @Param("oldState") String oldState, @Param("generation") long generation);
    int bindRouteUid(@Param("record") GatewayUrlExposurePO record);
    int recordObservation(@Param("record") GatewayUrlExposurePO record,
            @Param("generation") long generation, @Param("verifiedUid") String verifiedUid);

    @Override
    default GatewayUrlExposurePO selectActiveById(Serializable id) {
        throw new UnsupportedOperationException("URL_EXPOSURE_OWNER_REQUIRED");
    }
    @Override
    default List<GatewayUrlExposurePO> selectActiveByIds(Collection<? extends Serializable> ids) {
        throw new UnsupportedOperationException("URL_EXPOSURE_OWNER_REQUIRED");
    }
    @Override
    default int deleteVersionedById(GatewayUrlExposurePO entity) {
        throw new UnsupportedOperationException("URL_EXPOSURE_PERMANENT_RESERVATION");
    }
    @Override
    default int restoreVersionedById(GatewayUrlExposurePO entity) {
        throw new UnsupportedOperationException("URL_EXPOSURE_PERMANENT_RESERVATION");
    }
}
