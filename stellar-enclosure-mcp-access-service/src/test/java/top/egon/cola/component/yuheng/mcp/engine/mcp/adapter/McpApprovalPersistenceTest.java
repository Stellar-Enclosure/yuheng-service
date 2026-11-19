package top.egon.cola.component.yuheng.mcp.engine.mcp.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;
import top.egon.cola.component.common.core.validation.ValidationUtils;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationGroups;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationUtils;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpApprovalOwnerVO;
import top.egon.cola.component.yuheng.admin.mcp.service.McpApprovalAdminService;
import top.egon.cola.component.yuheng.admin.mcp.service.McpApprovalAdminService.ApprovalIntentResolver;
import top.egon.cola.component.yuheng.admin.mcp.service.McpApprovalAdminService.ApprovalIntentVO;
import top.egon.cola.component.yuheng.core.mcp.security.McpApprovalPort;
import top.egon.cola.component.yuheng.mcp.engine.config.McpPersistenceContextComponent;
import top.egon.cola.component.yuheng.mcp.engine.config.McpPersistenceProperties;
import top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.security.MpMcpApprovalAdapter;
import top.egon.cola.component.yuheng.mcp.engine.mcp.converter.McpApprovalPersistenceConverter;
import top.egon.cola.component.yuheng.mcp.engine.mcp.dao.McpApprovalDAO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.domain.enums.McpPersistentApprovalStatusEnum;
import top.egon.cola.component.yuheng.mcp.engine.mcp.domain.po.McpApprovalRecordPO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.repository.McpApprovalPersistenceRepository;
import top.egon.cola.component.yuheng.mcp.engine.mcp.repository.McpApprovalPersistenceRepository.IssueCommand;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpRequestObservationComponent;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpRequestObservationComponentTest;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpRequestObservationComponentTest.NativeFixture;
import top.egon.cola.component.yuheng.mcp.server.domain.McpRequestContext;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;

/**
 * 中文说明：用真实 Egon repository、生成转换器及执行上下文验证单一审批存储；DAO 替身不冒充真实数据库证据。
 * English summary: Exercises the real Egon repository, generated converter and execution context. DAO doubles do not
 * constitute PostgreSQL acceptance evidence.
 */
class McpApprovalPersistenceTest {
    private static final long TENANT = 9001L;
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final String TOKEN_DIGEST = "a".repeat(64);
    private static final String ARGUMENT_DIGEST = "b".repeat(64);
    private static final String PROPERTIES_BEAN = "egon.cola.component.mybatis-plus-top.egon.cola.component.common."
        + "mybatis.autoconfigure.EgonColaMybatisPlusProperties";
    private final McpApprovalDAO mapper = mock(McpApprovalDAO.class);
    private final EgonColaMybatisPlusProperties properties = new EgonColaMybatisPlusProperties();
    private final McpApprovalPersistenceConverter converter = new McpApprovalPersistenceConverter();
    private final McpApprovalPersistenceRepository repository =
        new McpApprovalPersistenceRepository(mapper, properties, converter);
    private ValidatorFactory validators;
    private EgonColaModelValidationUtils.Binding validationBinding;

    @BeforeEach
    void installActualValidationAndMapperMetadata() {
        validators = Validation.buildDefaultValidatorFactory();
        validationBinding =
            EgonColaModelValidationUtils.initialize(new ValidationUtils(validators.getValidator()), this);
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "approval-test"), McpApprovalRecordPO.class);
        MDC.put("tenantId", Long.toString(TENANT));
        MDC.put("userId", "approval-fixture");
    }

    @AfterEach
    void restoreOnlyOwnedThreadState() {
        TransactionSynchronizationManager.clear();
        MDC.remove("tenantId");
        MDC.remove("userId");
        if (validationBinding != null) {
            validationBinding.close();
        }
        if (validators != null) {
            validators.close();
        }
    }

    @Test
    void uniqueApprovalStoreHasAnExplicitDigestOnlyIssuanceOperation() {
        assertThat(Arrays.stream(McpApprovalPersistenceRepository.class.getDeclaredMethods())
                       .filter(method -> method.getName().equals("issue")))
            .hasSize(1);
        assertThat(Arrays.stream(IssueCommand.class.getRecordComponents()).map(RecordComponent::getName))
            .containsExactly("approvalId", "tokenDigest", "subjectId", "subjectTenantId", "clientId", "serverCode",
                "toolName", "argumentDigest", "issuedAt", "expiresAt");
    }

    @Test
    void actualGeneratedMappingPersistsOnlyTheAuthorizedDigestIntent() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(mapper.insert(any(McpApprovalRecordPO.class))).thenReturn(1);
        repository.issue(command());
        ArgumentCaptor<McpApprovalRecordPO> saved = ArgumentCaptor.forClass(McpApprovalRecordPO.class);
        verify(mapper).insert(saved.capture());
        McpApprovalRecordPO row = saved.getValue();
        assertThat(row.getApprovalKey()).isEqualTo("approval-opaque-7").isNotEqualTo(row.getTokenDigest());
        assertThat(row.getTokenDigest()).isEqualTo(TOKEN_DIGEST);
        assertThat(row.getArgumentDigest()).isEqualTo(ARGUMENT_DIGEST);
        assertThat(row.getSubjectTenantId()).isEqualTo("1001");
        assertThat(row.getStatus()).isEqualTo(McpPersistentApprovalStatusEnum.PENDING);
        assertThat(row.getRevision()).isZero();
        assertThat(row.getIssuedAt()).isEqualTo(NOW);
        assertThat(row.getExpiresAt()).isEqualTo(NOW.plusSeconds(90));
        assertThat(row.getConsumedAt()).isNull();
        assertThat(row.getTenantId()).as("only the actual Egon insert fill supplies storage tenancy").isNull();
        assertThat(row.getId()).isNull();
        assertThat(row.getCreateUserId()).isNull();
        assertThat(row.getVersion()).isNull();
    }

    @Test
    void issuanceRequiresAnActualWritableTransactionAndNeverClaimsZeroRowSuccess() {
        assertThatThrownBy(() -> repository.issue(command())).hasMessage("MCP_APPROVAL_WRITE_TRANSACTION_REQUIRED");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(() -> repository.issue(command())).hasMessage("MCP_APPROVAL_WRITE_TRANSACTION_REQUIRED");
        verify(mapper, never()).insert(any(McpApprovalRecordPO.class));
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        when(mapper.insert(any(McpApprovalRecordPO.class))).thenReturn(0);
        assertThatThrownBy(() -> repository.issue(command())).hasMessage("MCP_APPROVAL_INSERT_CONFLICT");
    }

    @Test
    void immutableIssuanceRejectsInvalidDigestIdentityAndExpiration() {
        assertThatThrownBy(()
                               -> new IssueCommand("id", "RAW-TOKEN", "s", "t", "c", "server", "tool", ARGUMENT_DIGEST,
                                   NOW, NOW.plusSeconds(1)))
            .hasMessage("MCP_APPROVAL_DIGEST_INVALID");
        assertThatThrownBy(()
                               -> new IssueCommand("id", TOKEN_DIGEST, "s\nsecret", "t", "c", "server", "tool",
                                   ARGUMENT_DIGEST, NOW, NOW.plusSeconds(1)))
            .hasMessage("MCP_APPROVAL_IDENTITY_INVALID");
        assertThatThrownBy(
            () -> new IssueCommand("id", TOKEN_DIGEST, "s", "t", "c", "server", "tool", ARGUMENT_DIGEST, NOW, NOW))
            .hasMessage("MCP_APPROVAL_EXPIRY_INVALID");
        assertThatThrownBy(()
                               -> new IssueCommand("id", TOKEN_DIGEST, "s", "t", "c", "server", "x".repeat(257),
                                   ARGUMENT_DIGEST, NOW, NOW.plusSeconds(1)))
            .hasMessage("MCP_APPROVAL_IDENTITY_INVALID");
    }

    @Test
    void exactDigestReadUsesCurrentStorageTenantAndRejectsAmbiguousRows() {
        McpApprovalRecordPO row = pending();
        when(mapper.selectActiveByTokenDigest(TENANT, TOKEN_DIGEST)).thenReturn(List.of(row));
        assertThat(repository.findActiveByTokenDigest(TOKEN_DIGEST)).contains(row);
        verify(mapper).selectActiveByTokenDigest(TENANT, TOKEN_DIGEST);
        when(mapper.selectActiveByTokenDigest(TENANT, TOKEN_DIGEST)).thenReturn(List.of(row, pending()));
        assertThatThrownBy(() -> repository.findActiveByTokenDigest(TOKEN_DIGEST))
            .hasMessage("MCP_APPROVAL_IDENTITY_CONFLICT");
        assertThatThrownBy(() -> repository.findActiveByTokenDigest("C".repeat(64)))
            .hasMessage("MCP_APPROVAL_DIGEST_INVALID");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void consumptionIncludesTheFullObservedFenceAndEveryBoundIdentity() {
        when(mapper.update(any(McpApprovalRecordPO.class), any(Wrapper.class))).thenReturn(1);
        assertThat(repository.consumePending(pending(), consumption(), NOW)).isTrue();
        ArgumentCaptor<McpApprovalRecordPO> row = ArgumentCaptor.forClass(McpApprovalRecordPO.class);
        ArgumentCaptor<Wrapper<McpApprovalRecordPO>> predicate = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(row.capture(), predicate.capture());
        assertThat(row.getValue().getStatus()).isEqualTo(McpPersistentApprovalStatusEnum.CONSUMED);
        assertThat(row.getValue().getRevision()).isEqualTo(5);
        assertThat(row.getValue().getVersion()).isEqualTo(2);
        assertThat(row.getValue().getConsumedAt()).isEqualTo(NOW);
        assertThat(predicate.getValue().getSqlSegment())
            .contains("id =", "tenant_id =", "version =", "revision =", "status =", "expires_at >",
                "token_digest =", "subject_id =", "subject_tenant_id =", "client_id =", "server_code =", "tool_name =",
                "argument_digest =", "deleted_at IS NULL");
        assertThat(((com.baomidou.mybatisplus.core.conditions.AbstractWrapper) predicate.getValue())
                       .getParamNameValuePairs()
                       .values())
            .contains(
                TENANT, TOKEN_DIGEST, ARGUMENT_DIGEST, "1001", "user-1", "client-1", "billing", "pay_invoice", NOW);
    }

    @Test
    void terminalExpiredWrongIdentityAndWrongStorageTenantNeverWrite() {
        for (McpPersistentApprovalStatusEnum terminal : List.of(McpPersistentApprovalStatusEnum.CONSUMED,
                 McpPersistentApprovalStatusEnum.EXPIRED, McpPersistentApprovalStatusEnum.REVOKED)) {
            assertThat(repository.consumePending(pending().setStatus(terminal), consumption(), NOW)).isFalse();
        }
        assertThat(repository.consumePending(pending().setExpiresAt(NOW), consumption(), NOW)).isFalse();
        assertThat(repository.consumePending(pending().setClientId("another-client"), consumption(), NOW)).isFalse();
        McpApprovalRecordPO foreign = pending();
        foreign.setTenantId(TENANT + 1);
        assertThatThrownBy(() -> repository.consumePending(foreign, consumption(), NOW))
            .hasMessage("TENANT_CONTEXT_MISMATCH");
        assertThatThrownBy(() -> repository.consumePending(pending().setRevision(Long.MAX_VALUE), consumption(), NOW))
            .hasMessage("MCP_APPROVAL_INVALID_VERSION");
        verify(mapper, never()).update(any(McpApprovalRecordPO.class), any(Wrapper.class));
    }

    @Test
    void zeroAffectedRowsRemainFailure() {
        when(mapper.update(any(McpApprovalRecordPO.class), any(Wrapper.class))).thenReturn(0);
        assertThat(repository.consumePending(pending(), consumption(), NOW)).isFalse();
    }

    @Test
    void racedConsumptionIsOnlyReportedForTheSameIdentityAndDigest() {
        McpApprovalPersistenceRepository storage = mock(McpApprovalPersistenceRepository.class);
        when(storage.findActiveByTokenDigest(TOKEN_DIGEST))
            .thenReturn(java.util.Optional.of(adapterPending()),
                java.util.Optional.of(
                    adapterPending().setStatus(McpPersistentApprovalStatusEnum.CONSUMED).setClientId("other-client")));
        when(storage.consumePending(any(), any(), eq(NOW))).thenReturn(false);
        assertThat(decision(storage)).isEqualTo(McpApprovalPort.Result.MISMATCH);
    }

    @Test
    void lostCasRereadsTheSameConsumedApprovalWithoutGrantingItAgain() {
        McpApprovalPersistenceRepository storage = mock(McpApprovalPersistenceRepository.class);
        when(storage.findActiveByTokenDigest(TOKEN_DIGEST))
            .thenReturn(java.util.Optional.of(adapterPending()),
                java.util.Optional.of(adapterPending().setStatus(McpPersistentApprovalStatusEnum.CONSUMED)));
        when(storage.consumePending(any(), any(), eq(NOW))).thenReturn(false);
        assertThat(decision(storage)).isEqualTo(McpApprovalPort.Result.CONSUMED);
    }

    @Test
    void databaseUnavailabilityNeverBecomesPendingOrApproved() {
        McpApprovalPersistenceRepository storage = mock(McpApprovalPersistenceRepository.class);
        when(storage.findActiveByTokenDigest(TOKEN_DIGEST))
            .thenThrow(new DataAccessResourceFailureException("synthetic database unavailable"));
        assertThat(decision(storage)).isEqualTo(McpApprovalPort.Result.UNAVAILABLE);
    }

    @Test
    void knownStableBeanNameInjectsTheActualMapstructBackedConverterExactlyOnce() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("mcpApprovalDAO", McpApprovalDAO.class, () -> mapper);
            context.registerBean(PROPERTIES_BEAN, EgonColaMybatisPlusProperties.class, () -> properties);
            context.register(McpApprovalPersistenceConverter.class, McpApprovalPersistenceRepository.class);
            context.refresh();
            assertThat(context.getBeansOfType(McpApprovalPersistenceConverter.class))
                .containsOnlyKeys("mcpApprovalPersistenceConverter");
            assertThat(context.getBeansOfType(McpApprovalPersistenceRepository.class))
                .containsOnlyKeys("mcpApprovalPersistenceRepository");
            assertThat(context.getBean("mcpApprovalPersistenceConverter", McpApprovalPersistenceConverter.class)
                           .toTarget(command())
                           .getApprovalKey())
                .isEqualTo(command().approvalId());
        }
    }

    @Test
    void rowValidationRetainsDatabaseShapeWithoutRequiringUnchangedColumnsOnCas() {
        McpApprovalRecordPO row = converter.toTarget(command());
        assertThat(validators.getValidator().validate(row, EgonColaModelValidationGroups.Insert.class)).isEmpty();
        row.setClientId(null);
        assertThat(validators.getValidator().validate(row, EgonColaModelValidationGroups.Insert.class))
            .anyMatch(violation -> violation.getPropertyPath().toString().equals("clientId"));
        assertThat(validators.getValidator().validate(converter.consumedRow(pending(), NOW)))
            .as("partial CAS does not re-require every insert-only field")
            .isEmpty();
    }

    @Test
    void actualMapperXmlBindsOnlyTheExactTenantDigestAndActiveScope() {
        String resource = "mybatis/mapper/mcp/McpApprovalDAO.xml";
        MybatisConfiguration configuration = new MybatisConfiguration();
        new XMLMapperBuilder(getClass().getClassLoader().getResourceAsStream(resource), configuration, resource,
            configuration.getSqlFragments())
            .parse();
        BoundSql read = configuration.getMappedStatement(McpApprovalDAO.class.getName() + ".selectActiveByTokenDigest")
                            .getBoundSql(Map.of("tenantId", TENANT, "tokenDigest", TOKEN_DIGEST));
        assertThat(read.getSql().replaceAll("\\s+", " "))
            .contains("FROM gateway_mcp_approval", "tenant_id = ?", "token_digest = ?", "deleted_at IS NULL", "LIMIT 2")
            .doesNotContain("gateway_mcp_approval_t0");
        assertThat(read.getParameterMappings())
            .extracting(mapping -> mapping.getProperty())
            .containsExactly("tenantId", "tokenDigest");
        assertThat(configuration.hasStatement(McpApprovalDAO.class.getName() + ".deleteVersionedById")).isTrue();
    }

    @Test
    void generatedSpringConstructorRetainsExplicitInjectionAndEveryQualifier() {
        var constructor = McpApprovalPersistenceRepository.class.getDeclaredConstructors()[0];
        assertThat(constructor.getAnnotation(Autowired.class)).isNotNull();
        assertThat(Arrays.stream(constructor.getParameters()).map(parameter -> {
            Qualifier qualifier = parameter.getAnnotation(Qualifier.class);
            return qualifier == null ? "<missing>" : qualifier.value();
        })).containsExactly("mcpApprovalDAO", PROPERTIES_BEAN, "mcpApprovalPersistenceConverter");
    }

    private McpApprovalPort.Result decision(McpApprovalPersistenceRepository storage) {
        return decision(storage, intent());
    }

    private McpApprovalPort.Result decision(McpApprovalPersistenceRepository storage, ApprovalIntentVO approved) {
        McpPersistenceProperties identity =
            new McpPersistenceProperties().setTenantId(TENANT).setIdentityTenantId(1001L).setServiceUserId(
                "approval-worker");
        var execution = new McpPersistenceContextComponent(identity, properties);
        ApprovalIntentResolver resolver = mock(ApprovalIntentResolver.class);
        when(resolver.consume(any(), any())).thenReturn(approved);
        IdpStarterProperties idp = new IdpStarterProperties();
        idp.setResourceUri(McpRequestObservationComponentTest.RESOURCE);
        MpMcpApprovalAdapter adapter =
            new MpMcpApprovalAdapter(storage, converter, execution, resolver, idp, Clock.fixed(NOW, ZoneOffset.UTC));
        try (var nativeFixture = new NativeFixture(); var observation = new McpRequestObservationComponent()) {
            nativeFixture.token("opaque-user", false);
            Map<String, Object> attributes = nativeFixture.authenticate(observation, "opaque-user");
            var actual = new McpRequestContext(McpRequestObservationComponentTest.server(),
                top.egon.cola.component.yuheng.contract.mcp.protocol.McpProtocolDialect.RC_2026_07_28, null,
                attributes);
            return Mono.from(adapter.consume(consumption()))
                .contextWrite(context
                    -> context.put(McpRequestContext.class, actual)
                        .put(McpRequestObservationComponent.class, observation))
                .block(Duration.ofSeconds(5));
        }
    }

    private static ApprovalIntentVO intent() {
        return new ApprovalIntentVO(TENANT, "billing", "pay_invoice", "tool-1", "LOCAL_OPERATION", "c".repeat(64));
    }
    private static McpApprovalRecordPO adapterPending() {
        return pending().setArgumentDigest(McpApprovalAdminService.boundDigest(
            new McpApprovalOwnerVO("user-1", "1001", "client-1"), McpRequestObservationComponentTest.ISSUER,
            McpRequestObservationComponentTest.RESOURCE, intent(), ARGUMENT_DIGEST));
    }

    @Test
    void noActualNativeRequestContextCannotReadOrConsumeStoredApprovals() {
        var storage = mock(McpApprovalPersistenceRepository.class);
        var idp = new IdpStarterProperties();
        idp.setResourceUri(McpRequestObservationComponentTest.RESOURCE);
        var execution = new McpPersistenceContextComponent(
            new McpPersistenceProperties().setTenantId(TENANT).setIdentityTenantId(1001L).setServiceUserId(
                "approval-worker"),
            properties);
        var adapter = new MpMcpApprovalAdapter(
            storage, converter, execution, mock(ApprovalIntentResolver.class), idp, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(Mono.from(adapter.consume(consumption())).block()).isEqualTo(McpApprovalPort.Result.UNAVAILABLE);
        verify(storage, never()).findActiveByTokenDigest(any());
    }

    @Test
    void changedFixedToolIntentAndLegacyRawArgumentApprovalsNeverAuthorizeTheSameName() {
        var storage = mock(McpApprovalPersistenceRepository.class);
        when(storage.findActiveByTokenDigest(TOKEN_DIGEST)).thenReturn(java.util.Optional.of(adapterPending()));
        assertThat(
            decision(storage,
                new ApprovalIntentVO(TENANT, "billing", "pay_invoice", "tool-1", "LOCAL_OPERATION", "d".repeat(64))))
            .isEqualTo(McpApprovalPort.Result.MISMATCH);
        when(storage.findActiveByTokenDigest(TOKEN_DIGEST)).thenReturn(java.util.Optional.of(pending()));
        assertThat(decision(storage)).isEqualTo(McpApprovalPort.Result.MISMATCH);
        verify(storage, never()).consumePending(any(), any(), any());
    }

    private static IssueCommand command() {
        return new IssueCommand("approval-opaque-7", TOKEN_DIGEST, "user-1", "1001", "client-1", "billing",
            "pay_invoice", ARGUMENT_DIGEST, NOW, NOW.plusSeconds(90));
    }

    private static McpApprovalPort.ConsumptionRequest consumption() {
        return new McpApprovalPort.ConsumptionRequest(
            TOKEN_DIGEST, "user-1", "1001", "client-1", "billing", "pay_invoice", ARGUMENT_DIGEST);
    }

    private static McpApprovalRecordPO pending() {
        McpApprovalRecordPO row = new McpApprovalPersistenceConverter().toTarget(command());
        row.setId(17L);
        row.setTenantId(TENANT);
        row.setVersion(2L);
        row.setRevision(4L);
        return row;
    }
}
