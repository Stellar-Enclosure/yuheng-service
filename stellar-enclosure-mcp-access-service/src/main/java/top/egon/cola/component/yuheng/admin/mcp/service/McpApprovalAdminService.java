package top.egon.cola.component.yuheng.admin.mcp.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.yuheng.admin.mcp.domain.dto.McpApprovalRequestDTO;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpApprovalOwnerVO;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpApprovalVO;
import top.egon.cola.component.yuheng.core.mcp.security.McpApprovalPort.ConsumptionRequest;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpGatewayIdentityAuthenticator.VerifiedManagementIdentityVO;
import top.egon.cola.component.yuheng.mcp.server.domain.McpRequestContext;

/**
 * 中文说明：一次性审批管理合同。只有已验证的 USER 管理证明和当前受信目录才能签发，签发与消费绑定相同不可变意图。
 * English summary: One-shot approval management. A verified USER and current trusted catalog authorize the same frozen
 * intent at issuance and consumption; no raw token or client-supplied pin is persisted.
 */
@Validated
public interface McpApprovalAdminService {
    String MCP_APPROVE = "yuheng:mcp:approve";
    String RESOURCE_KEY = "yuheng.api.mcpApproval.issue";

    McpApprovalVO issue(@NotNull @Valid McpApprovalRequestDTO request, @NotNull VerifiedManagementIdentityVO identity);

    /**
     * 中文说明：部署绑定的当前目录授权边界，消费必须读取实际请求的固定目录，不得回退活动版本。
     * English summary: Deployment-owned catalog authorization. Consumption must use the actual request's pinned
     * catalog and must not fall back to an active version or trust caller-supplied pin fields.
     */
    interface ApprovalIntentResolver {
        ApprovalIntentVO issue(VerifiedManagementIdentityVO actor, String serverCode, String toolName);
        ApprovalIntentVO consume(McpRequestContext actual, ConsumptionRequest request);
    }

    /**
     * 中文说明：仅由受信目录解析的工具身份；摘要涵盖 LOCAL_OPERATION 固定绑定或真实 REMOTE_MCP 提供方/挂载事实。
     * English summary: Trusted catalog identity whose source digest covers the exact local pin or real remote mount.
     */
    record ApprovalIntentVO(long ownerTenantId, String serverCode, String toolName, String toolId, String sourceType,
        String sourceIdentityDigest) {
        public ApprovalIntentVO {
            if (ownerTenantId <= 0)
                throw new IllegalArgumentException("MCP_APPROVAL_INTENT_INVALID");
            required(serverCode, 128);
            required(toolName, 256);
            required(toolId, 128);
            if (!"LOCAL_OPERATION".equals(sourceType) && !"REMOTE_MCP".equals(sourceType)) {
                throw new IllegalArgumentException("MCP_APPROVAL_INTENT_INVALID");
            }
            digest(sourceIdentityDigest);
        }
    }

    /**
     * 中文说明：长度前缀的确定性版本化哈希，无分隔歧义；旧 raw-arguments 摘要永不自动升级。
     * English summary: Deterministic length-prefixed intent hash. Legacy argument-only approvals never auto-upgrade.
     */
    static String boundDigest(McpApprovalOwnerVO owner, URI issuer, URI configuredAdapterResource,
        ApprovalIntentVO intent, String argumentDigest) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(configuredAdapterResource, "resource");
        digest(argumentDigest);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                for (String value :
                    new String[] {"mcp-approval-intent-v1", issuer.toASCIIString(),
                        configuredAdapterResource.toASCIIString(), owner.subjectId(), owner.tenantId(),
                        owner.clientId(), Long.toString(intent.ownerTenantId()), intent.serverCode(), intent.toolName(),
                        intent.toolId(), intent.sourceType(), intent.sourceIdentityDigest(), argumentDigest}) {
                    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                    output.writeInt(encoded.length);
                    output.write(encoded);
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("MCP_APPROVAL_DIGEST_UNAVAILABLE");
        }
    }

    private static void required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("MCP_APPROVAL_INTENT_INVALID");
    }
    private static void digest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("MCP_APPROVAL_DIGEST_INVALID");
    }

    /** 中文说明：安全且稳定的管理失败。 English summary: Safe, stable management failures. */
    final class Failure extends RuntimeException {
        private final int status;
        private final String code;
        public Failure(int status, String code) {
            super(code);
            this.status = status;
            this.code = code;
        }
        public int status() {
            return status;
        }
        public String code() {
            return code;
        }
        public static Failure unavailable() {
            return new Failure(503, "YUHENG_ADMIN_SECURITY_UNAVAILABLE");
        }
        public static Failure denied() {
            return new Failure(403, "YUHENG_ADMIN_CAPABILITY_REQUIRED");
        }
        public static Failure invalid() {
            return new Failure(422, "YUHENG_ADMIN_VALIDATION_FAILED");
        }
    }
}
