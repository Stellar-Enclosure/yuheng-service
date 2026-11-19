package top.egon.cola.component.yuheng.mcp.engine.bootstrap.lifecycle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import top.egon.cola.component.yuheng.mcp.engine.config.McpPublicationMountProperties;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpEngineHttpHandler.SnapshotResolver;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader;
import top.egon.cola.component.yuheng.mcp.engine.rule.domain.McpGatewayCompiledRulesDTO;
import top.egon.cola.component.yuheng.mcp.engine.rule.service.McpGatewayRuleCompilerStrategy;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owns the deployment-pinned MCP catalogs. Spring Boot owns HTTP acceptance and shutdown. */
@Slf4j
@RequiredArgsConstructor
public final class McpGatewayEngineRuntime implements SmartLifecycle, SnapshotResolver {

    @Qualifier("mcpPublishedBindingLoader")
    private final McpPublishedBindingLoader bindings;
    @Qualifier("mcpGatewayRuleCompilerStrategy")
    private final McpGatewayRuleCompilerStrategy compiler;
    private final McpPublicationMountProperties mounts;
    @Qualifier("gatewayClock")
    private final Clock clock;

    private volatile CatalogsVO catalogs;

    @Override
    public synchronized void start() {
        if (catalogs != null) {
            return;
        }
        bindings.reload();
        var activeFile = mounts.activeCatalog();
        var active = compiler.load(activeFile.file(), activeFile.sha256());
        Map<String, McpGatewayCompiledRulesDTO> byIdentity = new LinkedHashMap<>();
        byIdentity.put(active.identity().key(), active);
        for (var file : mounts.historicalCatalogs()) {
            var historical = compiler.loadHistorical(file.file(), file.sha256());
            if (byIdentity.putIfAbsent(historical.identity().key(), historical) != null) {
                throw new IllegalArgumentException("MCP_CATALOG_IDENTITY_DUPLICATE");
            }
        }
        catalogs = new CatalogsVO(active, Map.copyOf(byIdentity));
        log.info("Loaded pinned MCP catalogs activeVersion={} catalogs={}", active.releaseId(), byIdentity.size());
    }

    @Override
    public synchronized void stop() {
        catalogs = null;
    }

    @Override
    public boolean isRunning() {
        return catalogs != null;
    }

    public boolean running() {
        return isRunning();
    }

    public boolean ready() {
        return active() != null;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    @Override
    public McpGatewayCompiledRulesDTO active() {
        var current = catalogs;
        return current == null ? null : usable(current, current.active());
    }

    @Override
    public McpGatewayCompiledRulesDTO pinned(String compoundIdentity) {
        var current = catalogs;
        if (current == null || compoundIdentity == null) {
            return null;
        }
        return usable(current, current.byIdentity().get(compoundIdentity));
    }

    private McpGatewayCompiledRulesDTO usable(CatalogsVO state, McpGatewayCompiledRulesDTO catalog) {
        return catalog != null && bindings.isReady() && catalog.validUntil().isAfter(clock.instant())
                && catalogs == state ? catalog : null;
    }

    private record CatalogsVO(McpGatewayCompiledRulesDTO active,
            Map<String, McpGatewayCompiledRulesDTO> byIdentity) { }
}
