package top.egon.cola.component.yuheng.mcp.engine.mcp.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties;
import top.egon.cola.component.common.mybatis.extension.EgonColaRepository;
import top.egon.cola.component.yuheng.mcp.engine.mcp.dao.McpTaskCancellationReceiptDAO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.domain.po.McpTaskCancellationReceiptPO;
import java.time.Instant;
import java.util.Optional;

/** Serializes each owner/key on the same PRIMARY transaction as task CAS and management audit. */
@Repository("mcpTaskCancellationReceiptRepository")
@RequiredArgsConstructor
public class McpTaskCancellationReceiptRepository extends EgonColaRepository<McpTaskCancellationReceiptDAO, McpTaskCancellationReceiptPO> {
    public static final String SCOPE = "MCP_TASK_CANCEL";
    @Qualifier("mcpTaskCancellationReceiptDAO") private final McpTaskCancellationReceiptDAO mapper;
    @Qualifier("egon.cola.component.mybatis-plus-top.egon.cola.component.common.mybatis.autoconfigure.EgonColaMybatisPlusProperties")
    private final EgonColaMybatisPlusProperties properties;
    @Override public McpTaskCancellationReceiptDAO getBaseMapper() { return mapper; }
    @Override protected EgonColaMybatisPlusProperties getProperties() { return properties; }
    public void lock(long lockKey, String scopeId, String keyHash) {
        requireTransaction(); if (mapper.lockCancellation(lockKey, scopeId, keyHash) <= 0) throw new IllegalStateException("MCP_TASK_RECEIPT_LOCK_UNAVAILABLE");
    }
    public Optional<McpTaskCancellationReceiptPO> findReceipt(String scopeId, String keyHash) {
        requireTransaction();
        var rows = list(new Page<>(1L, 1L), Wrappers.<McpTaskCancellationReceiptPO>lambdaQuery()
                .eq(McpTaskCancellationReceiptPO::getScopeType, SCOPE).eq(McpTaskCancellationReceiptPO::getScopeId, scopeId)
                .eq(McpTaskCancellationReceiptPO::getIdempotencyKey, keyHash));
        return rows.stream().findFirst();
    }
    public void complete(String scopeId, String keyHash, String digest, String taskId, boolean cancelled, Instant expiresAt) {
        requireTransaction(); var previous = findReceipt(scopeId, keyHash);
        var row = previous.orElseGet(McpTaskCancellationReceiptPO::new);
        row.setScopeType(SCOPE).setScopeId(scopeId).setIdempotencyKey(keyHash).setPayloadSha256(digest)
                .setResourceId(taskId).setResponseContent(JsonNodeFactory.instance.objectNode().put("cancelled", cancelled)).setExpiresAt(expiresAt);
        boolean saved = previous.isPresent() ? updateById(row) : save(row);
        if (!saved) throw new IllegalStateException("MCP_TASK_RECEIPT_WRITE_UNAVAILABLE");
    }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("MCP_MANAGEMENT_TRANSACTION_REQUIRED");
    }
}
