package top.egon.cola.component.yuheng.mcp.engine.mcp.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
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
import top.egon.cola.component.yuheng.mcp.engine.mcp.domain.enums.McpPersistentApprovalStatusEnum;

/**
 * 中文说明：执行服务唯一审批行模型。签发只存 token/参数摘要，审批 ID 保留独立 opaque 身份；
 * 存储 tenant、审计、软删和技术 version 由 EgonModel 与受守卫持久化上下文拥有。
 * English summary: The execution service's sole approval row. Only token/argument digests are stored;
 * the opaque approval ID remains independent and Egon owns storage tenancy, audit and optimistic version.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName(value = "gateway_mcp_approval", autoResultMap = true)
public class McpApprovalRecordPO extends EgonModel<McpApprovalRecordPO> {
    /**
     * 中文说明：审批令牌摘要（64 位十六进制），对应 {@code token_digest}。 English summary: the 64-hex approval token
     * digest, mapped to {@code token_digest}.
     */
    @NotNull(groups = {Insert.class, Persisted.class})
    @Size(min = 64, max = 64)
    @TableField("token_digest")
    private String tokenDigest;

    /**
     * 中文说明：授权主体标识，对应 {@code subject_id}。 English summary: the authorized subject identifier, mapped to
     * {@code subject_id}.
     */
    @NotNull(groups = {Insert.class, Persisted.class})
    @Size(max = 128)
    @TableField("subject_id")
    private String subjectId;

    /**
     * 中文说明：客户端标识，对应 {@code client_id}。 English summary: the client identifier, mapped to {@code
     * client_id}.
     */
    @NotNull(groups = {Insert.class, Persisted.class})
    @Size(max = 128)
    @TableField("client_id")
    private String clientId;

    /**
     * 中文说明：MCP 服务器编码，对应 {@code server_code}。 English summary: the MCP server code, mapped to {@code
     * server_code}.
     */
    @NotNull(groups = {Insert.class, Persisted.class})
    @Size(max = 128)
    @TableField("server_code")
    private String serverCode;

    /** 中文说明：工具名称，对应 {@code tool_name}。 English summary: the tool name, mapped to {@code tool_name}. */
    @NotNull(groups = {Insert.class, Persisted.class})
    @Size(max = 256)
    @TableField("tool_name")
    private String toolName;

    /**
     * 中文说明：参数摘要，对应 {@code argument_digest}。 English summary: the argument digest, mapped to {@code
     * argument_digest}.
     */
    @NotNull(groups = {Insert.class, Persisted.class})
    @Size(min = 64, max = 64)
    @TableField("argument_digest")
    private String argumentDigest;

    /**
     * 中文说明：审批状态，以 {@link McpPersistentApprovalStatusEnum} 的 wire 字符串落库。 English summary: the
     * approval status, persisted as the wire string of {@link McpPersistentApprovalStatusEnum}.
     */
    @NotNull(groups = {Insert.class, Persisted.class})
    @TableField("status")
    private McpPersistentApprovalStatusEnum status;

    /**
     * 中文说明：审批协议修订号，与 MP 技术 {@code version} 互不替代。 English summary: the protocol revision of the
     * approval, never a substitute for the technical MP {@code version}.
     */
    @NotNull(groups = {Insert.class, Persisted.class}) @PositiveOrZero @TableField("revision") private Long revision;

    /**
     * 中文说明：签发时刻，对应 {@code issued_at}。 English summary: the issuing instant, mapped to {@code issued_at}.
     */
    @NotNull(groups = {Insert.class, Persisted.class}) @TableField("issued_at") private Instant issuedAt;

    /**
     * 中文说明：有效期截止时刻，对应 {@code expires_at}。 English summary: the validity deadline, mapped to {@code
     * expires_at}.
     */
    @NotNull(groups = {Insert.class, Persisted.class}) @TableField("expires_at") private Instant expiresAt;

    /** 中文说明：一次性消费时刻，可为空。 English summary: the one-shot consumption instant, nullable. */
    @TableField("consumed_at") private Instant consumedAt;

    /**
     * 中文说明：审批协议标识，与摘要独立，和存储租户组成永久去重键。 English summary: the opaque approval identifier,
     * independent of the digest and permanently deduplicated within the storage tenant.
     */
    @NotNull(groups = {Insert.class, Persisted.class})
    @Size(max = 64)
    @TableField("approval_key")
    private String approvalKey;

    /**
     * 中文说明：原 MCP owner 的字符串租户标识；不与部署持久化的数值 {@code tenant_id} 混用。 English summary: the
     * original MCP owner tenant string; it is not mixed with the deployment-persisted numeric {@code tenant_id}.
     */
    @NotNull(groups = {Insert.class, Persisted.class})
    @Size(max = 128)
    @TableField("subject_tenant_id")
    private String subjectTenantId;
}
