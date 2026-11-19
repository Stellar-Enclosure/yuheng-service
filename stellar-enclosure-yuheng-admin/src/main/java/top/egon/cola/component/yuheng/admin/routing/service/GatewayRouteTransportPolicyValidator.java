package top.egon.cola.component.yuheng.admin.routing.service;

import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler;
import top.egon.cola.component.yuheng.contract.protocol.AccessZone;
import top.egon.cola.component.yuheng.contract.protocol.GatewayProtocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks draft syntax before the exposure compiler checks the approved Gateway and Service binding. */
public final class GatewayRouteTransportPolicyValidator {
    private static final Set<String> ZONES = Set.of(AccessZone.PUBLIC.name(), AccessZone.INTERNAL.name());

    public List<GatewayTransportValidationIssue> validate(Map<String, Object> route, GatewayProtocol operationProtocol) {
        var issues = new ArrayList<GatewayTransportValidationIssue>();
        String host = requiredText(route.get("host"), "host", "HOST", "Host", issues);
        String method = requiredText(route.get("httpMethod"), "httpMethod", "METHOD", "HTTP Method", issues);
        String path = requiredText(route.get("pathPattern"), "pathPattern", "PATH", "Path Pattern", issues);

        if (host != null && method != null && path != null) {
            try {
                GatewayHttpRouteCompiler.canonicalMatch(host, method, path, route.get("pathMatchType"));
            } catch (IllegalArgumentException invalid) {
                String field = switch (invalid.getMessage().substring(invalid.getMessage().lastIndexOf(' ') + 1)) {
                    case "hostname" -> "host";
                    case "httpMethod" -> "httpMethod";
                    case "pathMatchType" -> "pathMatchType";
                    default -> "pathPattern";
                };
                issues.add(new GatewayTransportValidationIssue(field, "ROUTE_MATCH_INVALID", invalid.getMessage()));
            }
        }
        if (!(route.get("accessZones") instanceof List<?> zones) || zones.isEmpty()) {
            issues.add(new GatewayTransportValidationIssue("accessZones", "ROUTE_ACCESS_ZONE_REQUIRED",
                    "At least one Access Zone is required"));
        } else if (zones.stream().anyMatch(zone -> !(zone instanceof String name) || !ZONES.contains(name))) {
            issues.add(new GatewayTransportValidationIssue("accessZones", "ROUTE_ACCESS_ZONE_INVALID",
                    "Access Zone contains an unknown value"));
        } else if (zones.contains(AccessZone.PUBLIC.name())) {
            if (operationProtocol != null && operationProtocol != GatewayProtocol.HTTP) {
                issues.add(new GatewayTransportValidationIssue("operationId", "ROUTE_PROTOCOL_UNSUPPORTED",
                        "The public HTTPRoute requires an HTTP operation"));
            }
            Object priority = route.get("priority");
            if (priority != null && (!(priority instanceof Number number) || !"0".equals(number.toString()))) {
                issues.add(new GatewayTransportValidationIssue("priority", "ROUTE_PRIORITY_UNSUPPORTED",
                        "The public HTTPRoute requires priority 0"));
            }
        }

        Object transport = route.get("transportPolicy");
        if (transport instanceof Map<?, ?> policy) {
            policy.forEach((field, value) -> {
                if (!GatewayHttpRouteCompiler.isCompatibleLegacyTransportField(field, value)) {
                    issues.add(new GatewayTransportValidationIssue("transportPolicy." + field, "TRANSPORT_OPTION_RETIRED",
                            "The Cilium HTTPRoute profile does not accept this application proxy option"));
                }
            });
        } else if (transport != null) {
            issues.add(new GatewayTransportValidationIssue("transportPolicy", "TRANSPORT_POLICY_INVALID",
                    "Transport policy must be an object"));
        }
        return List.copyOf(issues);
    }

    private String requiredText(Object value, String field, String code, String label,
            List<GatewayTransportValidationIssue> issues) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        if (value == null || value instanceof String) {
            issues.add(new GatewayTransportValidationIssue(field, "ROUTE_" + code + "_REQUIRED", label + " is required"));
        } else {
            issues.add(new GatewayTransportValidationIssue(field, "ROUTE_" + code + "_INVALID", label + " must be a string"));
        }
        return null;
    }
}
