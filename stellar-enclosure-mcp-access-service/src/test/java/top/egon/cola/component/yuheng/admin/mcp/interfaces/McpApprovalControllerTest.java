package top.egon.cola.component.yuheng.admin.mcp.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.yuheng.admin.mcp.domain.dto.McpApprovalRequestDTO;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.*;
import top.egon.cola.component.yuheng.admin.mcp.service.McpApprovalAdminService;
import top.egon.cola.component.yuheng.admin.mcp.service.McpApprovalAdminService.*;
import top.egon.cola.component.yuheng.admin.mcp.service.impl.McpApprovalAdminServiceImpl;
import top.egon.cola.component.yuheng.mcp.common.security.McpSecurityDigests;
import top.egon.cola.component.yuheng.mcp.engine.bootstrap.config.McpManagementAccessFilter;
import top.egon.cola.component.yuheng.mcp.engine.config.*;
import top.egon.cola.component.yuheng.mcp.engine.mcp.repository.*;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.*;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpGatewayIdentityAuthenticator.VerifiedManagementIdentityVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpRequestObservationComponentTest.NativeFixture;
import top.egon.cola.platform.tianquan.jianshen.contract.authorization.SystemAuthorizationSnapshot;
import top.egon.cola.platform.tianquan.jianshen.contract.permission.EffectivePermissionSnapshot;
import top.egon.cola.platform.tianquan.jianshen.starter.permission.PermissionExecutionGuard;
import top.egon.cola.platform.tianquan.jianshen.starter.security.*;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;

/**
 * Real native identity proof, real transaction orchestration; remote policy/storage fixtures are not PostgreSQL proof.
 */
class McpApprovalControllerTest {
    private static final String PATH = "/api/v1/yuheng/admin/mcp/approvals";
    private static final String APPROVE = McpApprovalAdminService.MCP_APPROVE;
    private static final Instant NOW = McpRequestObservationComponentTest.NOW;
    private static final URI ISSUER = McpRequestObservationComponentTest.ISSUER;
    private static final URI RESOURCE = McpRequestObservationComponentTest.RESOURCE;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void clearThreadContext() {
        SecurityContextHolder.clearContext();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void ordinarySpringPrincipalWithoutSignedClientProofCannotIssueAnApproval() {
        var service = mock(McpApprovalAdminService.class);
        var controller = new McpApprovalController(service);
        assertThatThrownBy(() -> controller.issue(request(), null, new MockHttpServletResponse()))
            .isInstanceOf(Failure.class)
            .hasMessage("YUHENG_ADMIN_AUTHENTICATION_REQUIRED");
        verifyNoInteractions(service);
    }

    @Test
    void wirePathCreatedStatusAndBareThreeFieldResponseRemainStable() throws Exception {
        try (var h = new Harness()) {
            var mvc = MockMvcBuilders.standaloneSetup(new McpApprovalController(h.service))
                          .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                          .build();
            var result = mvc.perform(post(PATH)
                                         .requestAttr(McpManagementAccessFilter.VERIFIED_IDENTITY_ATTRIBUTE, h.identity)
                                         .contentType("application/json")
                                         .content(JSON.writeValueAsBytes(request())))
                             .andExpect(status().isCreated())
                             .andExpect(header().string("Cache-Control", "no-store"))
                             .andExpect(jsonPath("$.approvalId").isString())
                             .andExpect(jsonPath("$.approvalToken").isString())
                             .andExpect(jsonPath("$.expiresAt").isString())
                             .andReturn();
            var tree = JSON.readTree(result.getResponse().getContentAsString());
            assertThat(tree.size()).isEqualTo(3);
            assertThat(tree.path("approvalToken").asText()).matches("[A-Za-z0-9_-]{43}");
            assertThat(h.tx.commits).isOne();
            var issued = ArgumentCaptor.forClass(McpApprovalPersistenceRepository.IssueCommand.class);
            verify(h.storage).issue(issued.capture());
            assertThat(issued.getValue().clientId()).isEqualTo("client-1").isNotEqualTo(RESOURCE.toASCIIString());
            assertThat(issued.getValue().tokenDigest())
                .isEqualTo(McpSecurityDigests.token(tree.path("approvalToken").asText()));
            assertThat(issued.getValue().argumentDigest()).isEqualTo(boundDigest(h.intent));
            assertThat(issued.getValue().argumentDigest())
                .isNotEqualTo(McpSecurityDigests.arguments(JSON, request().arguments()));
            var audit = ArgumentCaptor.forClass(McpManagementAuditRepository.AuditCommand.class);
            verify(h.audit).record(audit.capture());
            assertThat(audit.getValue().clientId()).isEqualTo("client-1");
            assertThat(audit.getValue().resource()).isEqualTo(RESOURCE);
            assertThat(audit.getValue().argumentDigest()).isEqualTo(issued.getValue().argumentDigest());
            assertThat(audit.getValue().toString())
                .doesNotContain(tree.path("approvalToken").asText(), "password-value");
        }
    }

    @Test
    void rawCredentialNeverAppearsInResponseObjectDiagnostics() {
        assertThat(new McpApprovalVO("a", "secret-clear-token", NOW).toString())
            .contains("<redacted>")
            .doesNotContain("secret-clear-token");
    }

    @Test
    void invalidTtlBlankIdentityAndNullArgumentsRemain422BeforeWrites() throws Exception {
        try (var h = new Harness()) {
            var mvc = MockMvcBuilders.standaloneSetup(new McpApprovalController(h.service))
                          .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                          .build();
            for (var request : List.of(new McpApprovalRequestDTO("billing", "pay_invoice", Map.of(), 0),
                     new McpApprovalRequestDTO("billing", "pay_invoice", Map.of(), 301),
                     new McpApprovalRequestDTO("", "pay_invoice", Map.of(), 90),
                     new McpApprovalRequestDTO("billing", "pay_invoice", null, 90))) {
                mvc.perform(post(PATH)
                                .requestAttr(McpManagementAccessFilter.VERIFIED_IDENTITY_ATTRIBUTE, h.identity)
                                .contentType("application/json")
                                .content(JSON.writeValueAsBytes(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("YUHENG_ADMIN_VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors").isArray());
            }
            verifyNoInteractions(h.storage, h.audit);
        }
    }

    @Test
    void currentPermissionDenialNeverReachesCatalogOrStorage() {
        try (var h = new Harness()) {
            doThrow(new org.springframework.security.authorization.AuthorizationDeniedException("FIELD_ACCESS_DENIED"))
                .when(h.permissions)
                .require(McpApprovalAdminService.RESOURCE_KEY);
            assertThatThrownBy(() -> h.service.issue(request(), h.identity))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            verifyNoInteractions(h.resolver, h.storage, h.audit);
        }
    }

    @Test
    void wrongManagementResourceAndSpringProofMismatchNeverWrite() {
        try (var h = new Harness()) {
            h.idp.setResourceUri(URI.create("https://resource.example/foreign"));
            assertThatThrownBy(() -> h.service.issue(request(), h.identity)).isInstanceOf(Failure.class);
            h.idp.setResourceUri(RESOURCE);
            ((Rbac3AuthenticationToken) SecurityContextHolder.getContext().getAuthentication()).setDetails(null);
            assertThatThrownBy(() -> h.service.issue(request(), h.identity)).isInstanceOf(Failure.class);
            verifyNoInteractions(h.storage, h.audit);
        }
    }

    @Test
    void currentToolOwnerOrNameMismatchAndUnavailableCatalogFailClosed() {
        try (var h = new Harness()) {
            doReturn(new ApprovalIntentVO(9002, "billing", "pay_invoice", "tool-1", "LOCAL_OPERATION", "c".repeat(64)))
                .when(h.resolver)
                .issue(any(), any(), any());
            assertThatThrownBy(() -> h.service.issue(request(), h.identity)).isInstanceOf(Failure.class);
            doReturn(new ApprovalIntentVO(9001, "billing", "other", "tool-1", "LOCAL_OPERATION", "c".repeat(64)))
                .when(h.resolver)
                .issue(any(), any(), any());
            assertThatThrownBy(() -> h.service.issue(request(), h.identity)).isInstanceOf(Failure.class);
            doThrow(McpGatewayIdentityAuthenticator.SecurityFailure.unavailable())
                .when(h.resolver)
                .issue(any(), any(), any());
            assertThatThrownBy(() -> h.service.issue(request(), h.identity))
                .isInstanceOf(McpGatewayIdentityAuthenticator.SecurityFailure.class);
            verifyNoInteractions(h.storage, h.audit);
        }
    }

    @Test
    void expiredOrClosedRequestProofDoesNotSurviveToIssuance() {
        try (var h = new Harness()) {
            h.observation.close();
            assertThatThrownBy(() -> h.service.issue(request(), h.identity))
                .isInstanceOf(McpGatewayIdentityAuthenticator.SecurityFailure.class);
            verifyNoInteractions(h.storage, h.audit);
        }
    }

    @Test
    void auditFailureRollsBackAndCommitUnknownNeverReturnsTheClearToken() {
        try (var h = new Harness()) {
            doThrow(new IllegalStateException("sensitive-database-message")).when(h.audit).record(any());
            assertThatThrownBy(() -> h.service.issue(request(), h.identity))
                .isInstanceOf(Failure.class)
                .hasMessage("YUHENG_ADMIN_SECURITY_UNAVAILABLE")
                .hasNoCause();
            assertThat(h.tx.rollbacks).isOne();
            assertThat(h.tx.commits).isZero();
        }
        try (var h = new Harness()) {
            h.tx.failCommit = true;
            assertThatThrownBy(() -> h.service.issue(request(), h.identity))
                .isInstanceOf(Failure.class)
                .hasMessage("YUHENG_ADMIN_SECURITY_UNAVAILABLE")
                .hasNoCause();
            verify(h.storage).issue(any());
            verify(h.audit).record(any());
        }
    }

    @Test
    void ambientTransactionCannotLeakApprovalBeforeItsOuterCommit() {
        try (var h = new Harness()) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThatThrownBy(() -> h.service.issue(request(), h.identity)).isInstanceOf(Failure.class);
            verifyNoInteractions(h.storage, h.audit);
            assertThat(h.tx.commits).isZero();
        }
    }

    @Test
    void bothSourceKindsAndEveryFrozenIntentCoordinateAffectDigest() {
        var first = new ApprovalIntentVO(9001, "billing", "pay_invoice", "tool-1", "LOCAL_OPERATION", "c".repeat(64));
        for (var changed :
            List.of(new ApprovalIntentVO(9001, "billing", "pay_invoice", "tool-2", "LOCAL_OPERATION", "c".repeat(64)),
                new ApprovalIntentVO(9001, "billing", "pay_invoice", "tool-1", "REMOTE_MCP", "c".repeat(64)),
                new ApprovalIntentVO(9001, "billing", "pay_invoice", "tool-1", "LOCAL_OPERATION", "d".repeat(64)))) {
            assertThat(boundDigest(first)).isNotEqualTo(boundDigest(changed));
        }
        assertThatThrownBy(
            () -> new ApprovalIntentVO(9001, "billing", "pay_invoice", "tool-1", "REMOTE", "c".repeat(64)))
            .isInstanceOf(IllegalArgumentException.class);
        var owner = new McpApprovalOwnerVO("user-1", "1001", "client-1");
        assertThat(boundDigest(first))
            .isNotEqualTo(
                McpApprovalAdminService.boundDigest(owner, ISSUER, URI.create("https://resource.example/other-adapter"),
                    first, McpSecurityDigests.arguments(JSON, request().arguments())));
    }

    @Test
    void sharedAuditUsesExactExistingTableAndStoresOnlyFixedDigestFactsInWritableTransaction() {
        var mapper = mock(top.egon.cola.component.yuheng.mcp.engine.mcp.dao.McpManagementAuditDAO.class);
        var properties = new EgonColaMybatisPlusProperties();
        var repository = new McpManagementAuditRepository(mapper, properties);
        var command =
            new McpManagementAuditRepository.AuditCommand("user-1", "1001", "client-1", ISSUER, RESOURCE, "request-1",
                "trace-1", "MCP_APPROVAL", "approval-1", "ISSUE", "billing", "pay_invoice", "d".repeat(64), null, NOW);
        assertThatThrownBy(() -> repository.record(command)).hasMessage("MCP_MANAGEMENT_WRITE_TRANSACTION_REQUIRED");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(() -> repository.record(command)).hasMessage("MCP_MANAGEMENT_WRITE_TRANSACTION_REQUIRED");
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory();
            var binding = top.egon.cola.component.common.mybatis.model.EgonColaModelValidationUtils.initialize(
                new top.egon.cola.component.common.core.validation.ValidationUtils(factory.getValidator()), this)) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                    new com.baomidou.mybatisplus.core.MybatisConfiguration(), "management-audit-test"),
                top.egon.cola.component.yuheng.mcp.engine.mcp.domain.po.McpManagementAuditRecordPO.class);
            var context = new McpPersistenceContextComponent(
                new McpPersistenceProperties().setTenantId(9001L).setIdentityTenantId(1001L).setServiceUserId(
                    "audit-worker"),
                properties);
            when(mapper.insert(
                     any(top.egon.cola.component.yuheng.mcp.engine.mcp.domain.po.McpManagementAuditRecordPO.class)))
                .thenReturn(1);
            try {
                context.call("1001", () -> {
                    repository.record(command);
                    return null;
                });
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            var row = ArgumentCaptor.forClass(
                top.egon.cola.component.yuheng.mcp.engine.mcp.domain.po.McpManagementAuditRecordPO.class);
            verify(mapper).insert(row.capture());
            assertThat(row.getValue().getActorType()).isEqualTo("USER");
            assertThat(row.getValue().getSource()).isEqualTo("MANAGEMENT_API");
            assertThat(row.getValue().getAction()).isEqualTo("ISSUE");
            assertThat(row.getValue().getSuccessful()).isTrue();
            assertThat(row.getValue().getAfterSummary().path("argumentDigest").asText()).isEqualTo("d".repeat(64));
            assertThat(row.getValue().getAfterSummary().toString())
                .doesNotContain("approvalToken", "arguments", "Bearer", "password");
            when(mapper.insert(
                     any(top.egon.cola.component.yuheng.mcp.engine.mcp.domain.po.McpManagementAuditRecordPO.class)))
                .thenReturn(0);
            assertThatThrownBy(() -> context.call("1001", () -> {
                repository.record(command);
                return null;
            })).hasMessage("MCP_MANAGEMENT_AUDIT_UNAVAILABLE");
        }
    }

    @Test
    void managementFailureRenderingRetainsStableEnvelopeAndStrictDenialAudit() throws Exception {
        try (var h = new Harness()) {
            var service = mock(McpApprovalAdminService.class);
            when(service.issue(any(), any())).thenThrow(Failure.denied());
            var mvc = MockMvcBuilders.standaloneSetup(new McpApprovalController(service))
                          .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                          .build();
            mvc.perform(post(PATH)
                            .requestAttr(McpManagementAccessFilter.VERIFIED_IDENTITY_ATTRIBUTE, h.identity)
                            .contentType("application/json")
                            .content(JSON.writeValueAsBytes(request())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("YUHENG_ADMIN_CAPABILITY_REQUIRED"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.timestamp").isString());
            mvc.perform(post(PATH)
                            .requestAttr(McpManagementAccessFilter.VERIFIED_IDENTITY_ATTRIBUTE, h.identity)
                            .requestAttr(top.egon.cola.platform.tianquan.shoubing.starter.security
                                             .IdpSecurityObservationFilter.class.getName()
                                    + ".deniedCallback",
                                (java.util.function.BooleanSupplier) () -> false)
                            .contentType("application/json")
                            .content(JSON.writeValueAsBytes(request())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("OAUTH_AUDIT_DELIVERY_UNAVAILABLE"));
        }
    }

    private static String boundDigest(ApprovalIntentVO intent) {
        return McpApprovalAdminService.boundDigest(new McpApprovalOwnerVO("user-1", "1001", "client-1"), ISSUER,
            RESOURCE, intent, McpSecurityDigests.arguments(JSON, request().arguments()));
    }
    private static McpApprovalRequestDTO request() {
        return new McpApprovalRequestDTO(
            "billing", "pay_invoice", Map.of("amount", 42, "password", "password-value"), 90);
    }

    static final class Harness implements AutoCloseable {
        final NativeFixture nativeFixture = new NativeFixture();
        final McpRequestObservationComponent observation = new McpRequestObservationComponent();
        final VerifiedManagementIdentityVO identity;
        final McpApprovalPersistenceRepository storage = mock(McpApprovalPersistenceRepository.class);
        final McpManagementAuditRepository audit = mock(McpManagementAuditRepository.class);
        final ApprovalIntentResolver resolver = mock(ApprovalIntentResolver.class);
        final PermissionExecutionGuard permissions = mock(PermissionExecutionGuard.class);
        final IdpStarterProperties idp = new IdpStarterProperties();
        final ApprovalIntentVO intent =
            new ApprovalIntentVO(9001, "billing", "pay_invoice", "tool-1", "LOCAL_OPERATION", "c".repeat(64));
        final Transactions tx = new Transactions();
        final McpApprovalAdminServiceImpl service;
        Harness() {
            nativeFixture.token("opaque-user", false, "1001", Set.of(APPROVE));
            identity = nativeFixture.authenticator
                           .authenticateManagement(
                               McpRequestObservationComponentTest.http("opaque-user"), RESOURCE, Set.of(APPROVE))
                           .contextWrite(context -> context.put(McpRequestObservationComponent.class, observation))
                           .block(Duration.ofSeconds(5));
            idp.setResourceUri(RESOURCE);
            var scope = new top.egon.cola.platform.tianquan.jianshen.contract.permission.CatalogScope(
                "10", "1001", "billing", "yuheng-mcp", "default", "prod");
            var permission =
                new EffectivePermissionSnapshot(ISSUER.toASCIIString(), identity.principal().subject(), scope, "1", "7",
                    "3", new EffectivePermissionSnapshot.ActiveRoleContext("yuheng-mcp", List.of("50"), List.of("50")),
                    List.of(APPROVE), NOW.plusSeconds(200));
            var snapshot = new SystemAuthorizationSnapshot("1001", identity.principal().subject(), "1", "yuheng-mcp",
                7L, 3L, List.of("50"), null, null, Set.of(APPROVE), Set.of(), Map.of(), Map.of(), "checksum", NOW,
                NOW.plusSeconds(200), permission,
                new top.egon.cola.platform.tianquan.jianshen.contract.authorization.RuntimePolicyBinding(
                    "mcp-management-java", "1", "a".repeat(64), "b".repeat(64), "c".repeat(64)));
            var current = new Rbac3UserDetails(identity.principal(), snapshot);
            when(permissions.require(McpApprovalAdminService.RESOURCE_KEY)).thenReturn(current);
            var auth = new Rbac3AuthenticationToken(current, List.of(new SimpleGrantedAuthority("SCOPE_" + APPROVE)));
            auth.setDetails(identity.proof());
            SecurityContextHolder.getContext().setAuthentication(auth);
            when(resolver.issue(any(), any(), any())).thenReturn(intent);
            var context = new McpPersistenceContextComponent(
                new McpPersistenceProperties().setTenantId(9001L).setIdentityTenantId(1001L).setServiceUserId(
                    "approval-worker"),
                new EgonColaMybatisPlusProperties());
            service = new McpApprovalAdminServiceImpl(storage, audit, resolver, context, new TransactionTemplate(tx),
                permissions, idp, JSON, new SecureRandom(), Clock.fixed(NOW, ZoneOffset.UTC));
        }
        @Override
        public void close() {
            observation.close();
            nativeFixture.close();
            SecurityContextHolder.clearContext();
        }
    }

    static final class Transactions extends AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;
        boolean failCommit;
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }
        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
            if (failCommit)
                throw new org.springframework.transaction.TransactionSystemException("sensitive-commit-message");
        }
        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}
