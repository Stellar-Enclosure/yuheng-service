package top.egon.cola.component.yuheng.admin.scope.service;

import top.egon.cola.component.yuheng.admin.scope.domain.dto.GatewayScopeQueryDTO;
import java.util.List;

/** Exact logical application membership in deployment-approved environment/namespace scopes. */
@FunctionalInterface
public interface GatewayScopeBindingReader {
    List<BindingVO> bindings(GatewayScopeQueryDTO query);

    /** Native read model; it carries no provider address, instance lease or registration capability. */
    record BindingVO(String bindingId, String bizCode, String namespaceCode, String env,
                     String appId, String appCode, String appName, boolean enabled) { }

    /** Immutable, deployment-owned coordinates; application names and enablement come from current authority. */
    record DeploymentBindingVO(String bindingId, String bizCode, String namespace, String env,
                               String applicationId, String applicationCode) {
        public DeploymentBindingVO {
            for (String value : List.of(bindingId, bizCode, namespace, env, applicationId, applicationCode)) {
                if (value.isBlank() || value.length() > 64 || value.codePoints().anyMatch(Character::isISOControl)
                        || !value.equals(value.trim())) {
                    throw new IllegalArgumentException("YUHENG_SCOPE_BINDING_INVALID");
                }
            }
        }
    }
}
