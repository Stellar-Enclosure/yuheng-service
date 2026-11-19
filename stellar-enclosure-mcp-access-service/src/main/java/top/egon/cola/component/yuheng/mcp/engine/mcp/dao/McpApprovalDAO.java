package top.egon.cola.component.yuheng.mcp.engine.mcp.dao;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import top.egon.cola.component.common.mybatis.extension.EgonColaMapper;
import top.egon.cola.component.yuheng.mcp.engine.mcp.domain.po.McpApprovalRecordPO;

/**
 * 中文说明：审批唯一 Mapper，仅由受守卫 repository 调用；写入继续复用 Egon 的插入和版本化 CAS。
 * English summary: The sole approval mapper, reached through its guarded repository; writes reuse Egon insert/versioned
 * CAS.
 */
public interface McpApprovalDAO extends EgonColaMapper<McpApprovalRecordPO> {
    /**
     * 中文说明：按明确存储租户及摘要读取活跃记录，最多两条以便拒绝异常歧义。
     * English summary: Reads at most two active rows under the exact storage tenant and digest to detect ambiguity.
     * @param tenantId 受信存储租户；the trusted storage tenant.
     * @param tokenDigest 审批摘要；the approval digest.
     * @return 活跃记录；the active rows.
     */
    List<McpApprovalRecordPO> selectActiveByTokenDigest(@Param("tenantId") @NotNull @Positive Long tenantId,
        @Param("tokenDigest") @NotNull @Pattern(regexp = "[0-9a-f]{64}") String tokenDigest);
}
