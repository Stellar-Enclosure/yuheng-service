package top.egon.cola.component.yuheng.admin.routing.repository.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.egon.cola.component.yuheng.admin.routing.dao.GatewayDraftDAO;
import org.mockito.ArgumentCaptor;
import top.egon.cola.component.yuheng.admin.routing.converter.GatewayDraftPersistenceConverter;
import top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayDraftBO;
import top.egon.cola.component.yuheng.admin.routing.domain.po.GatewayDraftRecordPO;
import top.egon.cola.component.yuheng.admin.routing.repository.GatewayDraftJpaRepository;
import top.egon.cola.component.yuheng.admin.routing.repository.mp.GatewayDraftPersistenceRepository;
import top.egon.cola.component.yuheng.admin.shared.domain.exception.GatewayAdminRevisionConflictException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MpGatewayDraftMetadataRepositoryTest {

    private final GatewayDraftPersistenceRepository persistence = mock(GatewayDraftPersistenceRepository.class);
    private final GatewayDraftJpaRepository repository = new MpGatewayDraftMetadataRepository(
            persistence, new GatewayDraftPersistenceConverter());

    @Test
    void publicationLockHasAnExactTenantActivePredicateAndMandatoryNamedTransaction() throws Exception {
        var configuration = new MybatisConfiguration();
        String resource = "mybatis/mapper/routing/GatewayDraftDAO.xml";
        try (var stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
        }
        String statement = GatewayDraftDAO.class.getName() + ".selectForPublication";
        assertThat(configuration.hasStatement(statement)).isTrue();
        var sql = configuration.getMappedStatement(statement).getBoundSql(Map.of("tenantId", 101L, "groupId", 91L));
        assertThat(sql.getSql().replaceAll("\\s+", " ").trim())
                .endsWith("FROM gateway_draft WHERE tenant_id = ? AND gateway_group_id = ? AND deleted_at IS NULL FOR UPDATE");
        assertThat(sql.getParameterMappings()).extracting(parameter -> parameter.getProperty())
                .containsExactly("tenantId", "groupId");
        assertThat(GatewayDraftJpaRepository.class.getMethod("lockForPublication", String.class).getReturnType())
                .isEqualTo(Optional.class);
        var transaction = MpGatewayDraftMetadataRepository.class.getMethod("lockForPublication", String.class)
                .getAnnotation(Transactional.class);
        assertThat(transaction).isNotNull();
        assertThat(transaction.transactionManager()).isEqualTo("gatewayTransactionManager");
        assertThat(transaction.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(transaction.readOnly()).isFalse();
    }

    @AfterEach
    void clearTenantContext() { MDC.clear(); }

    @Test
    void publicationLockJoinsOnlyTheNamedWritableTransaction() throws Exception {
        MDC.put("tenantId", "101");
        try (var fixture = new LockFixture()) {
            var row = row();
            row.setTenantId(101L);
            when(fixture.mapper.selectForPublication(101L, 91L)).thenReturn(row);
            assertThatThrownBy(() -> fixture.repository.lockForPublication("91"))
                    .isInstanceOf(IllegalTransactionStateException.class);
            var unrelated = new TransactionTemplate(new DataSourceTransactionManager(source()));
            assertThatThrownBy(() -> unrelated.execute(status -> fixture.repository.lockForPublication("91")))
                    .isInstanceOf(IllegalTransactionStateException.class);
            var readOnly = new TransactionTemplate(fixture.manager);
            readOnly.setReadOnly(true);
            assertThatThrownBy(() -> readOnly.execute(status -> fixture.repository.lockForPublication("91")))
                    .isInstanceOf(IllegalStateException.class).hasMessage("YUHENG_DRAFT_PUBLICATION_TRANSACTION_REQUIRED");
            verifyNoInteractions(fixture.mapper);

            var locked = fixture.transaction.execute(status -> fixture.repository.lockForPublication("91"));
            assertThat(locked).isPresent().get().extracting(GatewayDraftBO::getGatewayGroupId, GatewayDraftBO::getRevision)
                    .containsExactly("91", 4L);
            verify(fixture.mapper).selectForPublication(101L, 91L);
        }
    }

    @Test
    void publicationLockRejectsMissingMalformedOrSystemTenantBeforeQuerying() throws Exception {
        try (var fixture = new LockFixture()) {
            assertThatThrownBy(() -> fixture.transaction.execute(status -> fixture.repository.lockForPublication("91")))
                    .isInstanceOf(IllegalStateException.class).hasMessage("TENANT_CONTEXT_MISSING");
            for (String invalid : new String[]{"0", "-1", "not-a-tenant"}) {
                MDC.put("tenantId", invalid);
                assertThatThrownBy(() -> fixture.transaction.execute(status -> fixture.repository.lockForPublication("91")))
                        .isInstanceOf(IllegalStateException.class);
            }
            verifyNoInteractions(fixture.mapper);
        }
    }

    @Test
    void publicationLockRejectsNoncanonicalGroupIdentifiersBeforeQuerying() throws Exception {
        MDC.put("tenantId", "101");
        try (var fixture = new LockFixture()) {
            for (String invalid : new String[]{null, "", "0", "-1", "091", " 91", "91 ", "x", "9223372036854775808"}) {
                assertThatThrownBy(() -> fixture.transaction.execute(status -> fixture.repository.lockForPublication(invalid)))
                        .isInstanceOf(IllegalArgumentException.class).hasMessage("YUHENG_DRAFT_PUBLICATION_GROUP_INVALID");
            }
            verifyNoInteractions(fixture.mapper);
        }
    }

    @Test
    void publicationLockDoesNotExposeAbsentWrongTenantWrongGroupOrDeletedRows() throws Exception {
        MDC.put("tenantId", "101");
        try (var fixture = new LockFixture()) {
            var absent = fixture.transaction.execute(status -> fixture.repository.lockForPublication("91"));
            assertThat(absent).isEmpty();
            var row = row();
            row.setTenantId(202L);
            when(fixture.mapper.selectForPublication(101L, 91L)).thenReturn(row);
            assertInvalidLoadedRow(fixture);
            row.setTenantId(101L);
            row.setGatewayGroupId(92L);
            assertInvalidLoadedRow(fixture);
            row.setGatewayGroupId(91L);
            row.setDeletedAt(java.time.LocalDateTime.ofInstant(Instant.EPOCH, java.time.ZoneOffset.UTC));
            assertInvalidLoadedRow(fixture);
        }
    }

    @Test
    void publicationLockChecksTenantContextAgainAfterTheMapperReturns() throws Exception {
        MDC.put("tenantId", "101");
        try (var fixture = new LockFixture()) {
            when(fixture.mapper.selectForPublication(101L, 91L)).thenAnswer(call -> {
                MDC.put("tenantId", "202");
                return null;
            });
            assertThatThrownBy(() -> fixture.transaction.execute(status -> fixture.repository.lockForPublication("91")))
                    .isInstanceOf(IllegalStateException.class).hasMessage("YUHENG_DRAFT_PUBLICATION_TENANT_CHANGED");
        }
    }

    private static void assertInvalidLoadedRow(LockFixture fixture) {
        assertThatThrownBy(() -> fixture.transaction.execute(status -> fixture.repository.lockForPublication("91")))
                .isInstanceOf(IllegalStateException.class).hasMessage("YUHENG_DRAFT_PUBLICATION_ROW_INVALID");
    }

    private static DataSource source() throws Exception {
        var source = mock(DataSource.class);
        when(source.getConnection()).thenAnswer(call -> {
            var connection = mock(Connection.class);
            when(connection.getAutoCommit()).thenReturn(true);
            return connection;
        });
        return source;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class LockTransactions { }

    private static final class LockFixture implements AutoCloseable {
        final GatewayDraftDAO mapper = mock(GatewayDraftDAO.class);
        final DataSourceTransactionManager manager;
        final TransactionTemplate transaction;
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final GatewayDraftJpaRepository repository;

        LockFixture() throws Exception {
            manager = new DataSourceTransactionManager(source());
            transaction = new TransactionTemplate(manager);
            var persistence = new GatewayDraftPersistenceRepository(mapper, new EgonColaMybatisPlusProperties());
            context.register(LockTransactions.class);
            context.registerBean("gatewayTransactionManager", DataSourceTransactionManager.class, () -> manager);
            context.registerBean("mpGatewayDraftMetadataRepository", MpGatewayDraftMetadataRepository.class,
                    () -> new MpGatewayDraftMetadataRepository(persistence, new GatewayDraftPersistenceConverter()));
            context.refresh();
            repository = context.getBean(GatewayDraftJpaRepository.class);
        }

        @Override public void close() { context.close(); }
    }

    @Test
    void existingDraftPortUsesTheGuardedCasAndReturnsTheStoredRevision() {
        GatewayDraftRecordPO row = row();
        when(persistence.getOneOpt(any())).thenReturn(Optional.of(row));
        when(persistence.updateById(any(GatewayDraftRecordPO.class))).thenReturn(true);
        GatewayDraftBO draft = repository.findById("91").orElseThrow();
        draft.touch("route updated", "service:test", Instant.parse("2026-10-18T00:00:00Z"));

        GatewayDraftBO saved = repository.save(draft);

        assertThat(saved.getRevision()).isEqualTo(5L);
        assertThat(saved.getChangeSummary()).isEqualTo("route updated");
        ArgumentCaptor<GatewayDraftRecordPO> changed = ArgumentCaptor.forClass(GatewayDraftRecordPO.class);
        verify(persistence).updateById(changed.capture());
        assertThat(changed.getValue().getId()).isEqualTo(99L);
        assertThat(changed.getValue().getVersion()).isEqualTo(12L);
        assertThat(changed.getValue().getRevision()).isEqualTo(5L);
    }

    @Test
    void staleBusinessRevisionCannotReachTheWriteBoundary() {
        when(persistence.getOneOpt(any())).thenReturn(Optional.of(row()));
        GatewayDraftBO stale = new GatewayDraftBO("91", "service:test", Instant.EPOCH).setRevision(3L);

        assertThatThrownBy(() -> repository.save(stale)).isInstanceOf(GatewayAdminRevisionConflictException.class);
        verify(persistence, never()).updateById(any(GatewayDraftRecordPO.class));
    }

    @Test
    void lostPersistenceCasDoesNotReportSuccess() {
        when(persistence.getOneOpt(any())).thenReturn(Optional.of(row()));
        when(persistence.updateById(any(GatewayDraftRecordPO.class))).thenReturn(false);
        GatewayDraftBO current = repository.findById("91").orElseThrow();

        assertThatThrownBy(() -> repository.save(current)).isInstanceOf(GatewayAdminRevisionConflictException.class);
    }

    private GatewayDraftRecordPO row() {
        GatewayDraftRecordPO row = new GatewayDraftRecordPO();
        row.setId(99L);
        row.setVersion(12L);
        row.setGatewayGroupId(91L);
        row.setRevision(4L);
        row.setStatus("EDITABLE");
        return row;
    }
}
