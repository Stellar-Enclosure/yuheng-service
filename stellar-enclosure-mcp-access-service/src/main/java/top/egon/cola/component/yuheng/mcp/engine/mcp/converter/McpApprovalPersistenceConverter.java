package top.egon.cola.component.yuheng.mcp.engine.mcp.converter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.BeanMapping;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import top.egon.cola.component.common.core.converter.BaseForwardConverter;
import top.egon.cola.component.yuheng.core.mcp.security.McpApprovalPort;
import top.egon.cola.component.yuheng.mcp.engine.mcp.domain.po.McpApprovalRecordPO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.repository.McpApprovalPersistenceRepository.IssueCommand;

/**
 * 中文说明：审批列的单向 MapStruct 投影。签发只映射已批准的摘要身份，技术 tenant/ID/审计由 Egon 填充；
 * 消费投影仅携带读取到的版本与目标状态，所有授权/CAS 谓词留在 repository。
 * English summary: One-way MapStruct approval projection. Issuance maps approved digest identity while Egon owns
 * technical metadata; consumption maps the observed version and target state, leaving authorization/CAS in the
 * repository.
 */
@Slf4j
@Validated
@Component("mcpApprovalPersistenceConverter")
public class McpApprovalPersistenceConverter implements BaseForwardConverter<IssueCommand, McpApprovalRecordPO> {
    private static final ApprovalMapping MAPPING = Mappers.getMapper(ApprovalMapping.class);

    /**
     * 中文说明：映射不可变签发命令，状态固定 PENDING、业务 revision 固定 0，不接收技术列。
     * English summary: Maps immutable issuance with fixed PENDING/revision zero and no supplied technical columns.
     * @param command 已授权签发命令；the authorized issuance command.
     * @return 待插入记录；the row to insert.
     */
    @Override
    public McpApprovalRecordPO toTarget(IssueCommand command) {
        return MAPPING.toRow(Objects.requireNonNull(command, "command"));
    }

    /**
     * 中文说明：生成部分消费行，溢出或缺失业务版本拒绝，不将 null revision 猜成零。
     * English summary: Builds a partial consumption row and refuses missing/overflowed revisions.
     * @param current 已读取记录；the observed row.
     * @param consumedAt 当前 UTC 时刻；the current UTC instant.
     * @return CAS 目标行；the CAS target row.
     */
    public McpApprovalRecordPO consumedRow(@NotNull @Valid McpApprovalRecordPO current, @NotNull Instant consumedAt) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(consumedAt, "consumedAt");
        if (current.getRevision() == null || current.getRevision() < 0) {
            throw new IllegalStateException("MCP_APPROVAL_INVALID_VERSION");
        }
        Math.addExact(current.getRevision(), 1L);
        return MAPPING.toConsumed(current, consumedAt);
    }

    /**
     * 中文说明：逐一核对协议身份，不从外部输入派生持久化租户，不重算摘要。
     * English summary: Compares protocol identity fieldwise without deriving storage tenancy or recomputing digests.
     * @param row 已读取记录；the observed row.
     * @param request 消费请求；the consumption request.
     * @return 是否全部匹配；whether all identity fields match.
     */
    public boolean matchesIdentity(
        @NotNull @Valid McpApprovalRecordPO row, @NotNull @Valid McpApprovalPort.ConsumptionRequest request) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(request, "request");
        return Objects.equals(row.getSubjectId(), request.subjectId())
            && Objects.equals(row.getSubjectTenantId(), request.tenantId())
            && Objects.equals(row.getClientId(), request.clientId())
            && Objects.equals(row.getServerCode(), request.serverCode())
            && Objects.equals(row.getToolName(), request.toolName())
            && Objects.equals(row.getArgumentDigest(), request.argumentDigest());
    }

    /**
     * 中文说明：唯一结构映射实现由 MapStruct 生成。 English summary: MapStruct generates the sole structural mapping.
     */
    @Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR, builder = @Builder(disableBuilder = true))
    interface ApprovalMapping {
        @BeanMapping(ignoreByDefault = true)
        @Mapping(target = "approvalKey", source = "approvalId")
        @Mapping(target = "tokenDigest", source = "tokenDigest")
        @Mapping(target = "subjectId", source = "subjectId")
        @Mapping(target = "subjectTenantId", source = "subjectTenantId")
        @Mapping(target = "clientId", source = "clientId")
        @Mapping(target = "serverCode", source = "serverCode")
        @Mapping(target = "toolName", source = "toolName")
        @Mapping(target = "argumentDigest", source = "argumentDigest")
        @Mapping(target = "issuedAt", source = "issuedAt")
        @Mapping(target = "expiresAt", source = "expiresAt")
        @Mapping(target = "status", constant = "PENDING")
        @Mapping(target = "revision", constant = "0L")
        McpApprovalRecordPO toRow(IssueCommand command);

        @BeanMapping(ignoreByDefault = true)
        @Mapping(target = "id", source = "current.id")
        @Mapping(target = "version", source = "current.version")
        @Mapping(target = "status", constant = "CONSUMED")
        @Mapping(target = "consumedAt", source = "consumedAt")
        @Mapping(target = "updateTime", source = "consumedAt")
        @Mapping(target = "revision", expression = "java(Math.addExact(current.getRevision(), 1L))")
        McpApprovalRecordPO toConsumed(McpApprovalRecordPO current, Instant consumedAt);
    }
}
