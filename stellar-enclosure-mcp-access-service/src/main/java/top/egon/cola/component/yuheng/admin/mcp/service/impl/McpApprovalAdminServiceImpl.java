package top.egon.cola.component.yuheng.admin.mcp.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.yuheng.admin.mcp.domain.dto.McpApprovalRequestDTO;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpApprovalOwnerVO;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpApprovalVO;
import top.egon.cola.component.yuheng.admin.mcp.service.McpApprovalAdminService;
import top.egon.cola.component.yuheng.mcp.common.security.McpSecurityDigests;
import top.egon.cola.component.yuheng.mcp.engine.config.McpPersistenceContextComponent;
import top.egon.cola.component.yuheng.mcp.engine.mcp.repository.McpApprovalPersistenceRepository;
import top.egon.cola.component.yuheng.mcp.engine.mcp.repository.McpApprovalPersistenceRepository.IssueCommand;
import top.egon.cola.component.yuheng.mcp.engine.mcp.repository.McpManagementAuditRepository;
import top.egon.cola.component.yuheng.mcp.engine.mcp.repository.McpManagementAuditRepository.AuditCommand;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpGatewayIdentityAuthenticator.SecurityFailure;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpGatewayIdentityAuthenticator.VerifiedManagementIdentityVO;
import top.egon.cola.platform.tianquan.jianshen.starter.permission.PermissionExecutionGuard;
import top.egon.cola.platform.tianquan.shoubing.starter.autoconfigure.IdpStarterProperties;

/**
 * 中文说明：签发只读取当前已授权固定目录，摘要记录和审计在唯一受守卫事务中提交后才交付明文。
 * English summary: Authorizes the current fixed catalog, commits digest-only issuance and audit together, then returns
 * the plaintext credential once. Unknown commit never becomes successful plaintext delivery.
 */
@Slf4j
@Validated
@Service("mcpApprovalAdminServiceImpl")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class McpApprovalAdminServiceImpl implements McpApprovalAdminService {
    @Qualifier("mcpApprovalPersistenceRepository") private final McpApprovalPersistenceRepository approvals;
    @Qualifier("mcpManagementAuditRepository") private final McpManagementAuditRepository audit;
    @Qualifier("mcpApprovalIntentResolver") private final ApprovalIntentResolver intents;
    @Qualifier("mcpPersistenceContextComponent") private final McpPersistenceContextComponent persistence;
    @Qualifier("mcpManagementTransactionTemplate") private final TransactionTemplate transactions;
    @Qualifier("permissionExecutionGuard") private final PermissionExecutionGuard permissions;
    @Qualifier("idpStarterProperties") private final IdpStarterProperties identityProperties;
    @Qualifier("jacksonObjectMapper") private final ObjectMapper json;
    @Qualifier("mcpApprovalSecureRandom") private final SecureRandom random;
    @Qualifier("gatewayClock") private final Clock clock;

    @Override
    public McpApprovalVO issue(McpApprovalRequestDTO request, VerifiedManagementIdentityVO identity) {
        requireIdentity(identity);
        // Refreshes actual catalog policy and verifies the captured MVC input field grants before any write.
        var current = permissions.require(RESOURCE_KEY);
        if (current == null || !Objects.equals(current.identity(), identity.principal())
            || current.snapshot().permissionSnapshot() == null || current.snapshot().policyBinding() == null
            || !identity.issuer().toASCIIString().equals(current.snapshot().permissionSnapshot().issuer())
            || !current.snapshot().expiresAt().isAfter(clock.instant()))
            throw Failure.denied();
        requireRequest(request);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw Failure.unavailable();
        String server = request.serverCode().trim();
        String tool = request.toolName().trim();
        final ApprovalIntentVO intent = intents.issue(identity, server, tool);
        if (intent == null || !Objects.equals(persistence.installationTenantId(), intent.ownerTenantId())
            || !server.equals(intent.serverCode()) || !tool.equals(intent.toolName()))
            throw Failure.denied();
        var owner = new McpApprovalOwnerVO(
            identity.principal().subject(), identity.principal().tenantId(), identity.clientId());
        String arguments = McpSecurityDigests.arguments(json, request.arguments());
        String intentDigest = McpApprovalAdminService.boundDigest(
            owner, identity.issuer(), identityProperties.getResourceUri(), intent, arguments);
        requireIdentity(identity);
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusSeconds(request.ttlSeconds());
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        String id = UUID.randomUUID().toString();
        var command = new IssueCommand(id, McpSecurityDigests.token(token), owner.subjectId(), owner.tenantId(),
            owner.clientId(), server, tool, intentDigest, issuedAt, expiresAt);
        String trace = safeTrace(MDC.get("traceId"));
        try {
            return persistence.call(owner.tenantId(), () -> transactions.execute(transaction -> {
                requireIdentity(identity);
                approvals.issue(command);
                audit.record(new AuditCommand(owner.subjectId(), owner.tenantId(), owner.clientId(), identity.issuer(),
                    identityProperties.getResourceUri(), trace, trace, "MCP_APPROVAL", id, "ISSUE", server, tool,
                    intentDigest, null, issuedAt));
                return new McpApprovalVO(id, token, expiresAt);
            }));
        } catch (Failure | SecurityFailure known) {
            throw known;
        } catch (DataIntegrityViolationException conflict) {
            throw new Failure(409, "YUHENG_ADMIN_RESOURCE_CONFLICT");
        } catch (Exception unavailable) {
            log.warn("MCP_APPROVAL_ISSUANCE_UNAVAILABLE");
            throw Failure.unavailable();
        }
    }

    private void requireIdentity(VerifiedManagementIdentityVO identity) {
        if (identity == null)
            throw new Failure(401, "YUHENG_ADMIN_AUTHENTICATION_REQUIRED");
        identity.requireCurrent(clock);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (identity.proof() == null || !identity.resource().equals(identityProperties.getResourceUri())
            || !identity.scopes().contains(MCP_APPROVE) || authentication == null || !authentication.isAuthenticated()
            || authentication.getDetails() != identity.proof())
            throw Failure.denied();
    }

    private static void requireRequest(McpApprovalRequestDTO request) {
        if (request == null || request.serverCode() == null || request.serverCode().isBlank()
            || request.toolName() == null || request.toolName().isBlank() || request.arguments() == null
            || request.ttlSeconds() < 1 || request.ttlSeconds() > 300)
            throw Failure.invalid();
    }
    private static String safeTrace(String trace) {
        return trace != null && trace.matches("[A-Za-z0-9._~-]{1,128}") ? trace : null;
    }
}
