package top.egon.cola.component.yuheng.mcp.engine.mcp.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.*;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;
import top.egon.cola.component.common.mybatis.model.EgonModel;
import top.egon.cola.component.yuheng.mcp.engine.mcp.dao.typehandler.McpJsonbTypeHandler;
import java.time.Instant;

/** Task-only receipt over the existing managed idempotency table; it never owns task execution state. */
@Data @NoArgsConstructor @AllArgsConstructor @SuperBuilder @Accessors(chain = true) @EqualsAndHashCode(callSuper = true)
@TableName(value = "gateway_idempotency_record", autoResultMap = true)
public class McpTaskCancellationReceiptPO extends EgonModel<McpTaskCancellationReceiptPO> {
    @TableField("scope_type") private String scopeType;
    @TableField("scope_id") private String scopeId;
    @TableField("idempotency_key") private String idempotencyKey;
    @TableField("payload_sha256") private String payloadSha256;
    @TableField("resource_id") private String resourceId;
    @TableField(value = "response_content", typeHandler = McpJsonbTypeHandler.class) private JsonNode responseContent;
    @TableField("expires_at") private Instant expiresAt;
}
