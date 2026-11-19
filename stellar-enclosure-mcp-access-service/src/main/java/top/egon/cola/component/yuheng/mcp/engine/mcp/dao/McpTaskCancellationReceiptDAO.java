package top.egon.cola.component.yuheng.mcp.engine.mcp.dao;

import org.apache.ibatis.annotations.Param;
import top.egon.cola.component.common.mybatis.extension.EgonColaMapper;
import top.egon.cola.component.yuheng.mcp.engine.mcp.domain.po.McpTaskCancellationReceiptPO;

public interface McpTaskCancellationReceiptDAO extends EgonColaMapper<McpTaskCancellationReceiptPO> {
    int lockCancellation(@Param("lockKey") long lockKey, @Param("scopeId") String scopeId, @Param("keyHash") String keyHash);
}
