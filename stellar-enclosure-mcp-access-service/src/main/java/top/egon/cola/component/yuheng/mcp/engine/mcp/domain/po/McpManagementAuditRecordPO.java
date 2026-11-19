package top.egon.cola.component.yuheng.mcp.engine.mcp.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationGroups.Insert;
import top.egon.cola.component.common.mybatis.model.EgonColaModelValidationGroups.Persisted;
import top.egon.cola.component.common.mybatis.model.EgonModel;
import top.egon.cola.component.yuheng.mcp.engine.mcp.dao.typehandler.McpJsonbTypeHandler;

/**
 * 中文说明：{@code McpManagementAuditRecordPO} 是 MyBatis-Plus 行模型，负责 {@code gateway_audit_log}
 * 表业务列的持久化边界，id/tenant/审计/软删/版本列由 {@link EgonModel} 只在父类声明一次。 English summary: {@code
 * McpManagementAuditRecordPO} is the MyBatis-Plus row model that owns the business columns of {@code gateway_audit_log}
 * while id, tenant, audit, soft-delete and version stay declared once in {@link EgonModel}.
 *
 * 用法 / Usage: 仅在持久边界使用；jsonb 列显式绑定 {@code McpJsonbTypeHandler}、vector 列绑定 {@code
 * GatewayVectorTypeHandler}，并依赖 {@code autoResultMap} 读回；业务 {@code revision} 等编码列与 MP {@code
 * version}/{@code id} 互不替代。/ Use it only at the persistence boundary; jsonb columns bind {@code
 * McpJsonbTypeHandler} and vector columns {@code GatewayVectorTypeHandler} under {@code autoResultMap}; business
 * columns such as {@code revision} stay separate from the MP {@code version} and {@code id}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName(value = "gateway_audit_log", autoResultMap = true)
public class McpManagementAuditRecordPO extends EgonModel<McpManagementAuditRecordPO> {
    @TableField("actor_id") @NotNull(groups = {Insert.class, Persisted.class}) @Size(max = 128) private String actorId;

    @TableField("actor_type")
    @NotNull(groups = {Insert.class, Persisted.class})
    @Size(max = 32)
    private String actorType;

    @TableField("source") @NotNull(groups = {Insert.class, Persisted.class}) @Size(max = 64) private String source;

    @TableField("request_id") @Size(max = 128) private String requestId;

    @TableField("trace_id") @Size(max = 128) private String traceId;

    @TableField("resource_type")
    @NotNull(groups = {Insert.class, Persisted.class})
    @Size(max = 64)
    private String resourceType;

    @TableField("resource_id")
    @NotNull(groups = {Insert.class, Persisted.class})
    @Size(max = 128)
    private String resourceId;

    @TableField("action") @NotNull(groups = {Insert.class, Persisted.class}) @Size(max = 128) private String action;

    @TableField(value = "before_summary", typeHandler = McpJsonbTypeHandler.class) private JsonNode beforeSummary;

    @TableField(value = "after_summary", typeHandler = McpJsonbTypeHandler.class) private JsonNode afterSummary;

    @TableField("draft_revision") private Long draftRevision;

    @TableField("release_id") @Size(max = 64) private String releaseId;

    @TableField("successful") @NotNull(groups = {Insert.class, Persisted.class}) private Boolean successful;

    @TableField("error_code") @Size(max = 128) private String errorCode;

    @TableField("occurred_at") @NotNull(groups = {Insert.class, Persisted.class}) private Instant occurredAt;
}
