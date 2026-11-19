package top.egon.cola.component.yuheng.admin.scope.service;

import lombok.extern.slf4j.Slf4j;
import top.egon.cola.component.common.mybatis.business.EgonColaTenantIdProvider;
import top.egon.cola.component.tianshu.api.catalog.DdcLogicalCatalogClient;
import top.egon.cola.component.tianshu.model.management.DdcManagementApp;
import top.egon.cola.component.yuheng.admin.config.GatewayPersistenceContextComponent;
import top.egon.cola.component.yuheng.admin.scope.domain.dto.GatewayScopeQueryDTO;
import java.util.*;

/** Resolves only approved deployment scopes against the live tenant-scoped logical catalog. */
@Slf4j
public final class GatewayTianshuScopeBindingReader implements GatewayScopeBindingReader {
    private final GatewayPersistenceContextComponent context;
    private final long owner;
    private final DdcLogicalCatalogClient catalog;
    private final List<DeploymentBindingVO> inventory;

    public GatewayTianshuScopeBindingReader(GatewayPersistenceContextComponent context,
            DdcLogicalCatalogClient catalog, List<DeploymentBindingVO> inventory) {
        this.context = Objects.requireNonNull(context, "context");
        Long configuredOwner = context.installationTenantId();
        if (configuredOwner == null || configuredOwner <= 0) { throw invalid(); }
        owner = configuredOwner;
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        if (inventory == null || inventory.size() > 256 || inventory.stream().anyMatch(Objects::isNull)) { throw invalid(); }
        Set<String> ids = new HashSet<>(); Set<List<String>> scopes = new HashSet<>();
        for (DeploymentBindingVO binding : inventory) {
            if (!ids.add(binding.bindingId()) || !scopes.add(List.of(binding.bizCode(), binding.namespace(),
                    binding.env(), binding.applicationCode()))) { throw invalid(); }
        }
        this.inventory = List.copyOf(inventory);
    }

    @Override
    public List<BindingVO> bindings(GatewayScopeQueryDTO query) {
        Objects.requireNonNull(query, "query");
        if (!Objects.equals(context.installationTenantId(), owner)
                || !Objects.equals(EgonColaTenantIdProvider.currentTenantId(), owner)) {
            throw new IllegalStateException("YUHENG_SCOPE_OWNER_CONTEXT_MISMATCH");
        }
        Map<String, Optional<DdcManagementApp>> current = new HashMap<>();
        List<BindingVO> result = new ArrayList<>();
        for (DeploymentBindingVO binding : inventory) {
            if (!matches(query.bizCode(), binding.bizCode()) || !matches(query.namespace(), binding.namespace())
                    || !matches(query.env(), binding.env()) || !matches(query.appCode(), binding.applicationCode())) { continue; }
            Optional<DdcManagementApp> selected;
            try {
                selected = current.computeIfAbsent(binding.applicationId(), catalog::findApplication);
            } catch (RuntimeException unavailable) {
                log.warn("Logical scope catalog request failed code=YUHENG_SCOPE_CATALOG_UNAVAILABLE");
                throw new IllegalStateException("YUHENG_SCOPE_CATALOG_UNAVAILABLE");
            }
            if (selected.isEmpty()) { continue; }
            DdcManagementApp app = selected.orElseThrow();
            if (!binding.applicationId().equals(app.id()) || !binding.bizCode().equals(app.bizCode())
                    || !binding.applicationCode().equals(app.appCode())) { throw invalid(); }
            if (app.enabled() && app.businessEnabled()) {
                result.add(new BindingVO(binding.bindingId(), binding.bizCode(), binding.namespace(), binding.env(),
                        app.id(), app.appCode(), app.appName(), true));
            }
        }
        return List.copyOf(result);
    }

    private static boolean matches(String requested, String actual) {
        return requested == null || requested.isBlank() || requested.equals(actual);
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("YUHENG_SCOPE_BINDING_INVALID");
    }
}
