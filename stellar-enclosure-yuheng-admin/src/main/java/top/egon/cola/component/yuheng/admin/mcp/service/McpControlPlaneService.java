package top.egon.cola.component.yuheng.admin.mcp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpServerBO;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpCapabilityPreviewVO;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpServerVO;
import top.egon.cola.component.yuheng.admin.mcp.repository.McpServerRepository;
import top.egon.cola.component.yuheng.admin.mcp.repository.McpArtifactMetadataRepository;
import top.egon.cola.component.yuheng.admin.mcp.repository.McpCapabilityDraftRepository;
import top.egon.cola.component.yuheng.admin.mcp.repository.McpRemoteProviderRepository;
import top.egon.cola.component.yuheng.admin.shared.domain.exception.GatewayAdminNotFoundException;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuleContent;

import java.util.List;
import java.util.Objects;

/**
 * Read-only legacy MCP catalog diagnostics and mapping preview.
 * Native definitions are owned by Tianshu; this service has no native asset
 * mutation, App artifact transport or execution-task persistence. Task reads
 * and cancellation belong exclusively to the standalone MCP access service.
 */
@Slf4j
@Service("mcpControlPlaneService")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
@Transactional(readOnly = true)
public class McpControlPlaneService {

    @Qualifier("mcpServerRepository")
    private final McpServerRepository servers;
    @Qualifier("mpMcpCapabilityDraftRepository")
    private final McpCapabilityDraftRepository capabilities;
    @Qualifier("mpMcpRemoteProviderRepository")
    private final McpRemoteProviderRepository remote;
    @Qualifier("mpMcpArtifactMetadataRepository")
    private final McpArtifactMetadataRepository artifacts;
    @Qualifier("mcpReleaseContentFactory")
    private final McpReleaseContentFactory contentFactory;
    @Qualifier("mcpValidationService")
    private final McpValidationService validation;

    public List<McpServerVO> listServers(String gatewayGroupId) {
        return servers
                .findAllByGatewayGroupIdAndDeletedFalseOrderByServerCode(
                        required(gatewayGroupId, "gatewayGroupId")
                )
                .stream()
                .map(this::view)
                .toList();
    }

    public McpServerVO getServer(String id) {
        return view(requiredServer(id));
    }

    public List<top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpCapabilityRecordBO> capabilities(
            String gatewayGroupId,
            String serverId,
            top.egon.cola.component.yuheng.admin.mcp.domain.enums.McpCapabilityKindEnum kind) {
        requiredServerInGroup(serverId, gatewayGroupId);
        return capabilities.load(gatewayGroupId)
                .capabilities(kind)
                .stream()
                .filter(item -> item.getServerId().equals(serverId))
                .toList();
    }

    public McpCapabilityPreviewVO preview(String gatewayGroupId) {
        McpRuleContent content = contentFactory.preview(gatewayGroupId);
        return new McpCapabilityPreviewVO(content, validation.validate(content));
    }

    public top.egon.cola.component.yuheng.admin.mcp.domain.vo.McpValidationReportVO validate(
            String gatewayGroupId) {
        return validation.validate(contentFactory.preview(gatewayGroupId));
    }

    public List<top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpRemoteProviderDraftBO> providers(
            String gatewayGroupId) {
        return remote.providers(gatewayGroupId);
    }

    public List<top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpRemoteCapabilityBO>
            remoteCapabilities(String providerId) {
        return remote.capabilities(providerId);
    }

    public List<top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpRemoteMountDraftBO> mounts(
            String gatewayGroupId) {
        return remote.mounts(gatewayGroupId);
    }

    public List<top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpArtifactMetadataBO> artifacts(
            String gatewayGroupId) {
        return artifacts.list(gatewayGroupId);
    }

    public top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpArtifactMetadataBO artifact(String id) {
        return artifacts.find(id).orElseThrow(() -> notFound(
                "MCP App artifact",
                id
        ));
    }

    private McpServerBO requiredServer(String id) {
        return servers.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> notFound("MCP Server", id));
    }

    private void requiredServerInGroup(String id, String gatewayGroupId) {
        requireGroup(gatewayGroupId, requiredServer(id).getGatewayGroupId());
    }

    private void requireGroup(String expected, String actual) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException(
                    "MCP resource belongs to another Gateway Group"
            );
        }
    }

    private GatewayAdminNotFoundException notFound(
            String resource,
            String id) {
        return new GatewayAdminNotFoundException(
                resource + " " + id + " was not found"
        );
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private McpServerVO view(McpServerBO server) {
        return new McpServerVO(
                server.getId(),
                server.getGatewayGroupId(),
                server.getServerCode(),
                server.getDisplayName(),
                server.getDescription(),
                server.getInstructions(),
                server.getDialects(),
                server.getResourceUri(),
                server.getListCacheTtlSeconds(),
                server.isEnabled(),
                server.getRevision(),
                server.getCreatedAt(),
                server.getUpdatedAt()
        );
    }
}
