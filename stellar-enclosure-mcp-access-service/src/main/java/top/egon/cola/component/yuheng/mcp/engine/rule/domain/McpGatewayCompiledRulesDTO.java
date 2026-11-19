package top.egon.cola.component.yuheng.mcp.engine.rule.domain;

import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuleContent;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.BindingPinVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.SelectionVO;
import top.egon.cola.component.yuheng.mcp.rule.domain.CompiledMcpRules;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A role-local MCP catalogue, without generic routes, provider selection or traffic governance. */
public record McpGatewayCompiledRulesDTO(
        long ownerTenantId,
        String version,
        Instant validUntil,
        String contentSha256,
        String fileSha256,
        McpRuleContent content,
        CompiledMcpRules mcpRules,
        Map<String, Set<String>> serverScopes,
        Map<SelectionVO, BindingPinVO> bindingPins
) {
    public McpGatewayCompiledRulesDTO {
        if (ownerTenantId <= 0 || version == null || !version.matches("[1-9][0-9]{0,18}")) throw invalid();
        try { Long.parseLong(version); } catch (NumberFormatException invalid) { throw invalid(); }
        Objects.requireNonNull(validUntil, "validUntil");
        if (contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}")
                || fileSha256 == null || !fileSha256.matches("[0-9a-f]{64}")) throw invalid();
        Objects.requireNonNull(content, "content"); Objects.requireNonNull(mcpRules, "mcpRules");
        if (!content.equals(mcpRules.content())) throw invalid();
        Map<String, Set<String>> scopes = new LinkedHashMap<>();
        Objects.requireNonNull(serverScopes, "serverScopes").forEach((server, values) -> {
            if (server == null || values == null || values.isEmpty() || values.size() > 64) throw invalid();
            scopes.put(server, Set.copyOf(values));
        });
        if (!scopes.keySet().equals(mcpRules.serversByCode().keySet())) throw invalid();
        serverScopes = Map.copyOf(scopes);
        bindingPins = Map.copyOf(Objects.requireNonNull(bindingPins, "bindingPins"));
        if (bindingPins.keySet().stream().anyMatch(key -> key.ownerTenantId() != ownerTenantId)) throw invalid();
    }

    public String releaseId() { return version; }
    public String ruleChecksum() { return fileSha256; }
    public boolean expiredAt(Instant now) { return !validUntil.isAfter(Objects.requireNonNull(now, "now")); }

    /** Includes file SHA so an OAuth-scope-only change also changes the session/task identity. */
    public SnapshotIdentityVO identity() { return new SnapshotIdentityVO(ownerTenantId, version, contentSha256, fileSha256); }

    public record SnapshotIdentityVO(long ownerTenantId, String version, String contentSha256, String fileSha256) {
        public SnapshotIdentityVO {
            if (ownerTenantId <= 0 || version == null || !version.matches("[1-9][0-9]{0,18}")
                    || contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}")
                    || fileSha256 == null || !fileSha256.matches("[0-9a-f]{64}")) throw invalid();
            try { Long.parseLong(version); } catch (NumberFormatException malformed) { throw invalid(); }
        }
        public String key() { return ownerTenantId + ":" + version + ":" + contentSha256 + ":" + fileSha256; }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("MCP_RULE_PROJECTION_INVALID"); }
}
