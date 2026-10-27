package top.egon.cola.component.yuheng.admin.routing.yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import top.egon.cola.component.yuheng.admin.routing.service.GatewayRouteDraftMapper;
import top.egon.cola.component.yuheng.contract.protocol.AccessZone;
import top.egon.cola.component.yuheng.core.provider.ProviderProtocolType;
import top.egon.cola.component.yuheng.core.provider.ProviderServiceKey;
import top.egon.cola.component.yuheng.core.route.GatewayResponseMode;
import top.egon.cola.component.yuheng.core.route.HttpRouteCompiler;
import top.egon.cola.component.yuheng.core.route.RuntimeHttpRoute;

/** Parses a bounded YAML document into the existing route draft representation. */
public final class GatewayYamlRouteParser {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final Set<String> TRANSPORT_FIELDS = Set.of(
            "profile", "transportProtocol", "requestBodyMode", "responseMode",
            "maxRequestBodyBytes", "connectTimeoutMs", "responseHeaderTimeoutMs",
            "streamIdleTimeoutMs", "totalTimeoutMs", "websocketIdleTimeoutMs",
            "websocketMaxFrameBytes", "bodyLogEnabled", "retryEnabled");
    private static final ProviderServiceKey VALIDATION_SERVICE = new ProviderServiceKey(
            "validation", "validation", "validation", "validation",
            ProviderProtocolType.HTTP, "validation", "default", "v1", "http");
    private final GatewayRouteDraftMapper mapper = new GatewayRouteDraftMapper();

    public Document parse(String source) {
        if (source == null || source.isBlank()
                || source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("route YAML must be non-empty and at most 1 MiB");
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(32);
        options.setCodePointLimit(MAX_BYTES);
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(source);
        } catch (YAMLException failure) {
            throw new IllegalArgumentException("invalid route YAML", failure);
        }
        Map<String, Object> root = map(loaded, "root", Set.of("egon"));
        Map<String, Object> egon = map(root.get("egon"), "egon", Set.of("yuheng"));
        Map<String, Object> yuheng = map(egon.get("yuheng"), "yuheng", Set.of("route-spec"));
        Map<String, Object> spec = map(yuheng.get("route-spec"), "route-spec",
                Set.of("schema-version", "gateway-group", "routes"));
        if (!"1".equals(String.valueOf(spec.get("schema-version")))) {
            throw new IllegalArgumentException("unsupported route YAML schema-version");
        }
        String group = text(spec.get("gateway-group"), "gateway-group");
        if (!(spec.get("routes") instanceof List<?> input) || input.size() > 10_000) {
            throw new IllegalArgumentException("routes must be a list of at most 10000 entries");
        }
        List<Route> routes = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Object entry : input) {
            Map<String, Object> route = map(entry, "route", Set.of(
                    "id", "operation-id", "predicates", "access-zones",
                    "priority", "enabled", "transport-policy"));
            String id = text(route.get("id"), "id");
            if (!ids.add(id)) {
                throw new IllegalArgumentException("duplicate route id: " + id);
            }
            String operationId = text(route.get("operation-id"), "operation-id");
            if (!(route.get("predicates") instanceof List<?> predicates)) {
                throw new IllegalArgumentException("predicates must be a list");
            }
            Map<String, Object> content = new LinkedHashMap<>();
            for (Object predicate : predicates) {
                GatewayRoutePredicateFactory.apply(text(predicate, "predicate"), content);
            }
            if (!content.keySet().containsAll(Set.of("host", "httpMethod", "pathPattern"))) {
                throw new IllegalArgumentException("Host, Method and Path predicates are required");
            }
            if (!(route.get("access-zones") instanceof List<?> rawZones) || rawZones.isEmpty()) {
                throw new IllegalArgumentException("access-zones must be a non-empty list");
            }
            List<String> zones = rawZones.stream()
                    .map(value -> AccessZone.valueOf(text(value, "access-zone")).name())
                    .distinct().toList();
            content.put("accessZones", zones);
            Object priority = route.getOrDefault("priority", 0);
            if (!(priority instanceof Integer)) {
                throw new IllegalArgumentException("priority must be a 32-bit integer");
            }
            content.put("priority", priority);
            Object enabled = route.getOrDefault("enabled", true);
            if (!(enabled instanceof Boolean)) {
                throw new IllegalArgumentException("enabled must be a boolean");
            }
            if (route.containsKey("transport-policy")) {
                content.put("transportPolicy", map(route.get("transport-policy"),
                        "transport-policy", TRANSPORT_FIELDS));
            }
            Map<String, Object> canonical = mapper.canonicalize(content);
            mapper.transportPolicy(canonical);
            routes.add(new Route(id, operationId, canonical, (boolean) enabled));
        }
        validateRoutes(group, routes);
        return new Document(group, List.copyOf(routes));
    }

    public void validateRoutes(String group, List<Route> routes) {
        List<RuntimeHttpRoute> compiled = new ArrayList<>();
        for (Route route : routes) {
            if (!route.enabled()) {
                continue;
            }
            Map<String, Object> content = mapper.canonicalize(route.content());
            if (!(content.get("accessZones") instanceof List<?> zones)) {
                throw new IllegalArgumentException("route accessZones must be a list: " + route.id());
            }
            compiled.add(new RuntimeHttpRoute(route.id(), route.operationId(), group,
                    zones.stream().map(value -> AccessZone.valueOf(text(value, "access-zone")))
                            .collect(java.util.stream.Collectors.toSet()),
                    text(content.get("host"), "host"),
                    Set.of(text(content.get("httpMethod"), "method")),
                    text(content.get("pathPattern"), "path"), true, VALIDATION_SERVICE,
                    Set.of(), ((Number) content.get("priority")).intValue(),
                    GatewayResponseMode.TRANSPARENT, Map.of()));
        }
        new HttpRouteCompiler().compile(compiled);
    }

    private static Map<String, Object> map(Object value, String name, Set<String> allowed) {
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(name + " must be a mapping");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (!(key instanceof String field) || !allowed.contains(field)) {
                throw new IllegalArgumentException("unsupported " + name + " field: " + key);
            }
            result.put(field, item);
        });
        return result;
    }

    private static String text(Object value, String name) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-empty string");
        }
        return text.trim();
    }

    public record Document(String gatewayGroup, List<Route> routes) { }

    public record Route(String id, String operationId, Map<String, Object> content, boolean enabled) { }
}
