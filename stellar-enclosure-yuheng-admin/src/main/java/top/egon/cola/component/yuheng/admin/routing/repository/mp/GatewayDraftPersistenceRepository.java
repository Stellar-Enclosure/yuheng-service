package top.egon.cola.component.yuheng.admin.routing.repository.mp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.common.mybatis.business.EgonColaTenantIdProvider;
import top.egon.cola.component.common.mybatis.extension.EgonColaRepository;
import top.egon.cola.component.yuheng.admin.routing.dao.GatewayDraftDAO;
import top.egon.cola.component.yuheng.admin.routing.domain.po.GatewayDraftRecordPO;

import java.util.Objects;
import java.util.Optional;

/**
 * gateway_draft 的受守卫持久化边界，复用 EgonColaRepository 的租户过滤、活跃读取与乐观锁 CAS 语义。
 * Guarded persistence boundary for gateway_draft; reuses EgonColaRepository tenant filtering, active reads and CAS writes.
 * 用法 / Usage: 业务代码仅经继承的 save/updateById/removeById 及具名查询访问，禁止裸 Wrapper 绕过守卫。
 */
@Slf4j
@Repository("gatewayDraftPersistenceRepository")
@RequiredArgsConstructor
public class GatewayDraftPersistenceRepository extends EgonColaRepository<GatewayDraftDAO, GatewayDraftRecordPO> {

    @Qualifier("gatewayDraftDAO")
    private final GatewayDraftDAO mapper;

    @Qualifier("egon.cola.component.mybatis-plus-top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties")
    private final EgonColaMybatisPlusProperties properties;

    @Override
    public GatewayDraftDAO getBaseMapper() {
        return mapper;
    }

    @Override
    protected EgonColaMybatisPlusProperties getProperties() {
        return properties;
    }

    /** Uses the ordinary guarded mapper; explicit predicates additionally bind this lock to the captured tenant. */
    public Optional<GatewayDraftRecordPO> lockForPublication(long gatewayGroupId) {
        Long tenant = EgonColaTenantIdProvider.currentTenantId();
        if (tenant <= 0L) throw new IllegalStateException("YUHENG_DRAFT_PUBLICATION_TENANT_REQUIRED");
        var row = mapper.selectForPublication(tenant, gatewayGroupId);
        if (!tenant.equals(EgonColaTenantIdProvider.currentTenantId())) {
            throw new IllegalStateException("YUHENG_DRAFT_PUBLICATION_TENANT_CHANGED");
        }
        if (row != null && (!Objects.equals(row.getTenantId(), tenant)
                || !Objects.equals(row.getGatewayGroupId(), gatewayGroupId) || row.getDeletedAt() != null)) {
            throw new IllegalStateException("YUHENG_DRAFT_PUBLICATION_ROW_INVALID");
        }
        return Optional.ofNullable(row);
    }
}
