package top.egon.cola.component.yuheng.mcp.engine.mcp.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import top.egon.cola.component.tianshu.format.DdcConfigCanonicalJson;
import top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpTaskBO;
import top.egon.cola.component.yuheng.mcp.engine.config.McpPersistenceContextComponent;
import top.egon.cola.component.yuheng.mcp.engine.mcp.adapter.MpMcpRuntimeTaskStore;
import top.egon.cola.component.yuheng.mcp.engine.mcp.converter.McpTaskManagementConverter;
import top.egon.cola.component.yuheng.mcp.engine.mcp.repository.McpManagementAuditRepository;
import top.egon.cola.component.yuheng.mcp.engine.mcp.repository.McpManagementAuditRepository.AuditCommand;
import top.egon.cola.component.yuheng.mcp.engine.mcp.repository.McpTaskCancellationReceiptRepository;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpGatewayIdentityAuthenticator.VerifiedManagementIdentityVO;
import top.egon.cola.component.yuheng.mcp.task.domain.McpTask;
import top.egon.cola.component.yuheng.mcp.task.service.McpTaskService.Owner;
import top.egon.cola.platform.tianquan.jianshen.starter.permission.PermissionExecutionGuard;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** USER-only management of the runtime store. The HTTP request can never choose persistence tenancy or a task owner. */
@Service("mcpTaskManagementService")
@RequiredArgsConstructor
public class McpTaskManagementService {
    public static final String READ = "yuheng:mcp:runtime:read";
    public static final String WRITE = "yuheng:mcp:write";
    @Qualifier("mcpRuntimeTaskStore") private final MpMcpRuntimeTaskStore tasks;
    @Qualifier("mcpTaskCancellationReceiptRepository") private final McpTaskCancellationReceiptRepository receipts;
    @Qualifier("mcpManagementAuditRepository") private final McpManagementAuditRepository audits;
    @Qualifier("mcpTaskManagementConverterImpl") private final McpTaskManagementConverter converter;
    @Qualifier("mcpPersistenceContextComponent") private final McpPersistenceContextComponent persistence;
    @Qualifier("mcpManagementTransactionTemplate") private final TransactionTemplate transaction;
    private final PermissionExecutionGuard permissions;
    @Qualifier("gatewayClock") private final Clock clock;

    public List<McpTaskBO> tasks(String tenantId, String clientId, VerifiedManagementIdentityVO identity) {
        Owner owner = authorize(identity, READ, "yuheng.api.mcpTaskAdmin.list");
        if (!owner.tenantId().equals(tenantId) || clientId != null && !owner.clientId().equals(clientId)) throw notFound();
        return context(owner, () -> tasks.managementList(owner, 256).stream()
                .filter(task -> task.expiresAt().isAfter(clock.instant())).map(converter::toTarget).toList());
    }

    public McpTaskBO task(String id, VerifiedManagementIdentityVO identity) {
        Owner owner = authorize(identity, READ, "yuheng.api.mcpTaskAdmin.get");
        return context(owner, () -> {
            McpTask task = tasks.managementFind(taskId(id), owner).orElseThrow(McpTaskManagementService::notFound);
            if (!task.expiresAt().isAfter(clock.instant())) throw notFound();
            identity.requireCurrent(clock); return converter.toTarget(task);
        });
    }

    public boolean cancelTask(String id, long expectedRevision, String idempotencyKey,
                              VerifiedManagementIdentityVO identity, String requestId, String traceId) {
        Owner owner = authorize(identity, WRITE, "yuheng.api.mcpTaskAdmin.cancel");
        String taskId = taskId(id);
        if (expectedRevision < 0 || idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 256
                || !idempotencyKey.equals(idempotencyKey.strip()) || idempotencyKey.codePoints().anyMatch(Character::isISOControl))
            throw status(HttpStatus.BAD_REQUEST, "MCP_TASK_CANCEL_INVALID");
        String scope = hash((identity.issuer() + "\0" + identity.resource() + "\0" + owner.tenantId() + "\0" + owner.subjectId()
                + "\0" + owner.clientId() + "\0" + taskId).getBytes(StandardCharsets.UTF_8));
        String key = hash(idempotencyKey.getBytes(StandardCharsets.UTF_8));
        String payload = hash(DdcConfigCanonicalJson.canonicalBytes(JsonNodeFactory.instance.objectNode()
                .put("taskId", taskId).put("expectedRevision", Long.toString(expectedRevision))));
        long lockKey = ByteBuffer.wrap(HexFormat.of().parseHex(hash((scope + '\0' + key).getBytes(StandardCharsets.UTF_8)))).getLong();
        return context(owner, () -> Boolean.TRUE.equals(transaction.execute(tx -> {
            identity.requireCurrent(clock); receipts.lock(lockKey, scope, key);
            var previous = receipts.findReceipt(scope, key);
            if (previous.isPresent() && previous.get().getExpiresAt() != null && previous.get().getExpiresAt().isAfter(clock.instant())) {
                var saved = previous.get();
                if (!taskId.equals(saved.getResourceId()) || !payload.equals(saved.getPayloadSha256()))
                    throw status(HttpStatus.CONFLICT, "MCP_TASK_IDEMPOTENCY_CONFLICT");
                if (saved.getResponseContent() == null || !saved.getResponseContent().isObject()
                        || saved.getResponseContent().size() != 1 || !saved.getResponseContent().path("cancelled").isBoolean())
                    throw status(HttpStatus.SERVICE_UNAVAILABLE, "MCP_TASK_RECEIPT_INVALID");
                identity.requireCurrent(clock); return saved.getResponseContent().path("cancelled").booleanValue();
            }
            McpTask task = tasks.managementFind(taskId, owner).orElseThrow(McpTaskManagementService::notFound);
            boolean cancelled = !task.terminal() && task.expiresAt().isAfter(clock.instant()) && task.revision() == expectedRevision
                    && tasks.managementCancel(taskId, owner, task.state(), expectedRevision, clock.instant());
            identity.requireCurrent(clock);
            if (cancelled) {
                audits.record(new AuditCommand(owner.subjectId(), owner.tenantId(), owner.clientId(), identity.issuer(), identity.resource(),
                        safeTrace(requestId), safeTrace(traceId), "MCP_TASK", taskId, "CANCEL", task.serverCode(), task.toolName(),
                        task.requestDigest(), expectedRevision, clock.instant()));
            }
            receipts.complete(scope, key, payload, taskId, cancelled, clock.instant().plus(Duration.ofDays(7)));
            identity.requireCurrent(clock); return cancelled;
        })));
    }

    private Owner authorize(VerifiedManagementIdentityVO identity, String scope, String api) {
        if (identity == null) throw status(HttpStatus.UNAUTHORIZED, "MCP_MANAGEMENT_IDENTITY_REQUIRED");
        identity.requireCurrent(clock);
        requireSecurityActor(identity);
        if (!identity.scopes().contains(scope)) throw status(HttpStatus.FORBIDDEN, "MCP_MANAGEMENT_SCOPE_DENIED");
        // Reuses the current Jianshen API/field policy context; ownership below is an additional object boundary.
        permissions.requireExecution(api);
        requireSecurityActor(identity);
        identity.requireCurrent(clock);
        return new Owner(identity.principal().subject(), identity.principal().tenantId(), identity.clientId());
    }
    private static void requireSecurityActor(VerifiedManagementIdentityVO identity) {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof top.egon.cola.platform.tianquan.jianshen.starter.security.Rbac3UserDetails user)
                || !identity.principal().equals(user.identity()) || !Objects.equals(identity.proof(), authentication.getDetails()))
            throw status(HttpStatus.FORBIDDEN, "MCP_MANAGEMENT_ACTOR_MISMATCH");
    }
    private <T> T context(Owner owner, java.util.concurrent.Callable<T> work) {
        try { return persistence.call(owner.tenantId(), work); }
        catch (RuntimeException failure) { throw failure; }
        catch (Exception unavailable) { throw status(HttpStatus.SERVICE_UNAVAILABLE, "MCP_TASK_STORE_UNAVAILABLE"); }
    }
    private static String taskId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw status(HttpStatus.BAD_REQUEST, "MCP_TASK_ID_INVALID"); return id;
    }
    private static String safeTrace(String value) {
        if (value == null || value.isBlank()) return null;
        return value.length() <= 128 && value.chars().allMatch(c -> c >= 0x21 && c <= 0x7e) ? value : null;
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static ResponseStatusException notFound() { return status(HttpStatus.NOT_FOUND, "MCP_TASK_NOT_FOUND"); }
    private static ResponseStatusException status(HttpStatus status, String code) { return new ResponseStatusException(status, code); }
}
