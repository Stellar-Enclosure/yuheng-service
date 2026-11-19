package top.egon.cola.component.yuheng.mcp.engine.mcp.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.common.mybatis.business.EgonColaTenantIdProvider;
import top.egon.cola.component.common.mybatis.extension.EgonColaRepository;
import top.egon.cola.component.yuheng.core.mcp.security.McpApprovalPort;
import top.egon.cola.component.yuheng.mcp.engine.mcp.converter.McpApprovalPersistenceConverter;
import top.egon.cola.component.yuheng.mcp.engine.mcp.dao.McpApprovalDAO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.domain.enums.McpPersistentApprovalStatusEnum;
import top.egon.cola.component.yuheng.mcp.engine.mcp.domain.po.McpApprovalRecordPO;

/**
 * 中文说明：执行服务唯一审批存储，签发和消费共用 Egon 的租户、审计、活跃行和版本守卫。
 * 签发 API 只接受已授权意图的摘要；授权与原始 token 生成属于调用方，不在此处重新构造身份。
 * English summary: The sole approval store, sharing Egon tenancy/audit/active-row/version guards for issuance and
 * consumption. The caller authorizes the immutable intent and generates the raw token; this boundary receives digests
 * only.
 */
@Slf4j
@Validated
@Repository("mcpApprovalPersistenceRepository")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class McpApprovalPersistenceRepository extends EgonColaRepository<McpApprovalDAO, McpApprovalRecordPO> {
    @Qualifier("mcpApprovalDAO") private final McpApprovalDAO mapper;

    @Qualifier("egon.cola.component.mybatis-plus-top.egon.cola.component.common.mybatis.autoconfigure."
        + "EgonColaMybatisPlusProperties")
    private final EgonColaMybatisPlusProperties properties;

    @Qualifier("mcpApprovalPersistenceConverter") private final McpApprovalPersistenceConverter converter;

    @Override
    public McpApprovalDAO getBaseMapper() {
        return mapper;
    }

    @Override
    protected EgonColaMybatisPlusProperties getProperties() {
        return properties;
    }

    /**
     * 中文说明：在调用方已开启的写事务中插入一次性审批，永不 upsert、覆盖或恢复历史终态。
     * English summary: Inserts once within the caller's writable transaction; never upserts or revives a terminal row.
     * @param command 已授权且仅包含摘要的签发意图；the authorized digest-only issuance intent.
     */
    public void issue(@NotNull @Valid IssueCommand command) {
        Objects.requireNonNull(command, "command");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("MCP_APPROVAL_WRITE_TRANSACTION_REQUIRED");
        }
        if (!save(converter.toTarget(command))) {
            throw new IllegalStateException("MCP_APPROVAL_INSERT_CONFLICT");
        }
    }

    /**
     * 中文说明：只读取受信存储租户内的活跃摘要行，永久唯一约束异常时拒绝歧义而不取首行。
     * English summary: Reads an active digest in the trusted storage tenant and rejects an ambiguous result.
     * @param tokenDigest 严格小写 SHA-256；the exact lowercase SHA-256.
     * @return 唯一活跃记录；the unique active row, if present.
     */
    public Optional<McpApprovalRecordPO> findActiveByTokenDigest(
        @NotNull @Pattern(regexp = "[0-9a-f]{64}") String tokenDigest) {
        requireDigest(tokenDigest);
        List<McpApprovalRecordPO> rows =
            mapper.selectActiveByTokenDigest(EgonColaTenantIdProvider.currentTenantId(), tokenDigest);
        if (rows.size() > 1) {
            throw new IllegalStateException("MCP_APPROVAL_IDENTITY_CONFLICT");
        }
        return rows.stream().findFirst();
    }

    /**
     * 中文说明：完整复核读取版本、PENDING、有效期及所有身份/摘要，0 行不代表成功；失败方由适配器重读。
     * English summary: CASes the exact read version, PENDING state, validity and every identity/digest predicate;
     * zero rows is not success and the adapter re-reads after losing the CAS.
     * @param current 已读取记录；the observed row.
     * @param request 已验证消费意图；the verified consumption intent.
     * @param now 当前 UTC 时刻；the current UTC instant.
     * @return 是否恰好推进一行；whether one row advanced.
     */
    public boolean consumePending(@NotNull @Valid McpApprovalRecordPO current,
        @NotNull @Valid McpApprovalPort.ConsumptionRequest request, @NotNull Instant now) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(now, "now");
        Long tenant = EgonColaTenantIdProvider.currentTenantId();
        if (!tenant.equals(current.getTenantId())) {
            throw new IllegalStateException("TENANT_CONTEXT_MISMATCH");
        }
        if (current.getId() == null || current.getVersion() == null || current.getRevision() == null
            || current.getRevision() < 0 || current.getRevision() == Long.MAX_VALUE) {
            throw new IllegalStateException("MCP_APPROVAL_INVALID_VERSION");
        }
        if (current.getStatus() != McpPersistentApprovalStatusEnum.PENDING || current.getExpiresAt() == null
            || !current.getExpiresAt().isAfter(now) || !Objects.equals(current.getTokenDigest(), request.tokenDigest())
            || !converter.matchesIdentity(current, request)) {
            return false;
        }
        return update(converter.consumedRow(current, now),
            Wrappers.<McpApprovalRecordPO>lambdaUpdate()
                .eq(McpApprovalRecordPO::getId, current.getId())
                .eq(McpApprovalRecordPO::getTenantId, tenant)
                .eq(McpApprovalRecordPO::getVersion, current.getVersion())
                .eq(McpApprovalRecordPO::getRevision, current.getRevision())
                .eq(McpApprovalRecordPO::getStatus, McpPersistentApprovalStatusEnum.PENDING)
                .gt(McpApprovalRecordPO::getExpiresAt, now)
                .eq(McpApprovalRecordPO::getTokenDigest, request.tokenDigest())
                .eq(McpApprovalRecordPO::getSubjectId, request.subjectId())
                .eq(McpApprovalRecordPO::getSubjectTenantId, request.tenantId())
                .eq(McpApprovalRecordPO::getClientId, request.clientId())
                .eq(McpApprovalRecordPO::getServerCode, request.serverCode())
                .eq(McpApprovalRecordPO::getToolName, request.toolName())
                .eq(McpApprovalRecordPO::getArgumentDigest, request.argumentDigest())
                .isNull(McpApprovalRecordPO::getDeletedAt));
    }

    private static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("MCP_APPROVAL_DIGEST_INVALID");
        }
    }

    /**
     * 中文说明：不可变签发意图，不含原始 token、存储 tenant、技术 ID 或可设置的状态。
     * English summary: Immutable issuance intent without raw token, storage tenant, technical ID or caller-controlled
     * state.
     */
    public record IssueCommand(@NotBlank @Size(max = 64) String approvalId,
        @NotNull @Pattern(regexp = "[0-9a-f]{64}") String tokenDigest, @NotBlank @Size(max = 128) String subjectId,
        @NotBlank @Size(max = 128) String subjectTenantId, @NotBlank @Size(max = 128) String clientId,
        @NotBlank @Size(max = 128) String serverCode, @NotBlank @Size(max = 256) String toolName,
        @NotNull @Pattern(regexp = "[0-9a-f]{64}") String argumentDigest, @NotNull Instant issuedAt,
        @NotNull Instant expiresAt) {
        public IssueCommand {
            approvalId = bounded(approvalId, 64);
            requireDigest(tokenDigest);
            subjectId = bounded(subjectId, 128);
            subjectTenantId = bounded(subjectTenantId, 128);
            clientId = bounded(clientId, 128);
            serverCode = bounded(serverCode, 128);
            toolName = bounded(toolName, 256);
            requireDigest(argumentDigest);
            Objects.requireNonNull(issuedAt, "issuedAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (!expiresAt.isAfter(issuedAt)) {
                throw new IllegalArgumentException("MCP_APPROVAL_EXPIRY_INVALID");
            }
        }

        private static String bounded(String value, int max) {
            if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("MCP_APPROVAL_IDENTITY_INVALID");
            }
            return value.trim();
        }
    }
}
