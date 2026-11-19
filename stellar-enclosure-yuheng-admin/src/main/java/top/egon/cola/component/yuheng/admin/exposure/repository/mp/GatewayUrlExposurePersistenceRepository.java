package top.egon.cola.component.yuheng.admin.exposure.repository.mp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.common.mybatis.extension.EgonColaRepository;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationGroups;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationUtils;
import top.egon.cola.component.yuheng.admin.exposure.dao.GatewayUrlExposureDAO;
import top.egon.cola.component.yuheng.admin.exposure.domain.po.GatewayUrlExposurePO;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.BindingVO;

import java.sql.SQLException;
import java.util.List;

/** MP technical boundary; application callers use the owner-scoped repository port. */
@Slf4j
@Repository("gatewayUrlExposurePersistenceRepository")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class GatewayUrlExposurePersistenceRepository
        extends EgonColaRepository<GatewayUrlExposureDAO, GatewayUrlExposurePO> {

    @Qualifier("gatewayUrlExposureDAO")
    private final GatewayUrlExposureDAO mapper;
    @Qualifier("egon.cola.component.mybatis-plus-top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties")
    private final EgonColaMybatisPlusProperties properties;

    @Override
    public GatewayUrlExposureDAO getBaseMapper() { return mapper; }
    @Override
    protected EgonColaMybatisPlusProperties getProperties() { return properties; }

    public void requireTransaction() {
        var environment = getSqlSessionFactory().getConfiguration().getEnvironment();
        Object resource = TransactionSynchronizationManager.getResource(environment.getDataSource());
        if (!(environment.getTransactionFactory() instanceof SpringManagedTransactionFactory)
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !(resource instanceof ConnectionHolder holder)) {
            throw new IllegalStateException("URL_EXPOSURE_CONTROL_TRANSACTION_REQUIRED");
        }
        try {
            if (holder.getConnection().getAutoCommit()) {
                throw new IllegalStateException("URL_EXPOSURE_CONTROL_TRANSACTION_REQUIRED");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("URL_EXPOSURE_CONTROL_TRANSACTION_UNAVAILABLE", failure);
        }
    }

    public int lockPublicationDomain(long lockKey) {
        requireTransaction();
        int backendPid = mapper.lockPublicationDomain(lockKey);
        if (backendPid <= 0) {
            throw new IllegalStateException("URL_EXPOSURE_READ_COMMITTED_REQUIRED");
        }
        return backendPid;
    }

    public GatewayUrlExposurePO findOwned(long owner, long id) {
        return loaded(mapper.findOwned(owner, id));
    }

    public GatewayUrlExposurePO findOwnedRoute(long owner, long groupId, String routeId) {
        return loaded(mapper.findOwnedRoute(owner, groupId, routeId));
    }

    public List<Long> findRecoveryIds(long owner, long afterId, int limit) {
        return List.copyOf(mapper.findRecoveryIds(owner, afterId, Math.min(limit, pageSize())));
    }

    public List<GatewayUrlExposurePO> scanBinding(String cluster, BindingVO binding, long afterId) {
        return mapper.scanBinding(cluster, binding.gatewayNamespace(), binding.gatewayName(),
                binding.listenerName(), afterId, properties.getPagination().getMaxPageSize())
                .stream().map(this::loaded).toList();
    }

    public int pageSize() { return properties.getPagination().getMaxPageSize(); }

    public boolean changeDesired(GatewayUrlExposurePO record, String oldState, long generation) {
        updateModel(record);
        return affected(mapper.compareAndSetDesired(record, oldState, generation));
    }

    public boolean changeContent(GatewayUrlExposurePO record, String oldState, long generation) {
        updateModel(record);
        return affected(mapper.compareAndSetContent(record, oldState, generation));
    }

    public boolean bindUid(GatewayUrlExposurePO record) {
        updateModel(record);
        return affected(mapper.bindRouteUid(record));
    }

    public boolean observe(GatewayUrlExposurePO record, long generation, String verifiedUid) {
        updateModel(record);
        return affected(mapper.recordObservation(record, generation, verifiedUid));
    }

    private void updateModel(GatewayUrlExposurePO record) {
        requireTransaction();
        EgonColaModelValidationUtils.validate(record, EgonColaModelValidationGroups.Operation.UPDATE);
    }

    private GatewayUrlExposurePO loaded(GatewayUrlExposurePO record) {
        return record == null ? null : EgonColaModelValidationUtils.validate(record,
                EgonColaModelValidationGroups.Operation.LOADED);
    }

    private boolean affected(int rows) {
        if (rows < 0 || rows > 1) { throw new IllegalStateException("URL_EXPOSURE_WRITE_COUNT_INVALID"); }
        return rows == 1;
    }
}
