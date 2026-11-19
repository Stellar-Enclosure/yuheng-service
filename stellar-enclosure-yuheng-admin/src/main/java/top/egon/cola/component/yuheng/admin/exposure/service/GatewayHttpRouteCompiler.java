package top.egon.cola.component.yuheng.admin.exposure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.BindingVO;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.PublicationDomainVO;
import top.egon.cola.component.yuheng.contract.protocol.AccessZone;
import top.egon.cola.component.yuheng.contract.protocol.GatewayProtocol;
import top.egon.cola.component.yuheng.contract.rule.GatewayRuntimePolicy;
import top.egon.cola.component.yuheng.contract.rule.GatewayRuntimeRoute;

import java.net.IDN;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pure northbound compiler. The platform supplies already verified physical inventory; this class
 * neither obtains authority from draft JSON nor performs Kubernetes/DB/network operations.
 */
@Slf4j
@Component("gatewayHttpRouteCompiler")
public final class GatewayHttpRouteCompiler {

    public static final String SCHEMA_VERSION = "cilium-exposure-v2";
    public static final String CAPABILITY_PROFILE_ID = "cilium-1.20.5-gapi-1.6.3-edge-h1-gamma-v2";
    public static final String COMPILER_VERSION = "2";
    public static final String AUTHORIZATION_PROFILE = "oauth2-resource-server-v1";
    public static final String CONTROLLER_NAME = "io.cilium/gateway-controller";
    public static final List<String> REMOVED_RETRY_HEADERS = List.of("x-envoy-retry-on", "x-envoy-retry-grpc-on",
            "x-envoy-max-retries", "x-envoy-retriable-header-names", "x-envoy-retriable-status-codes",
            "x-envoy-hedge-on-per-try-timeout", "x-envoy-upstream-rq-per-try-timeout-ms");
    private static final Set<String> DRAFT_FIELDS = Set.of("host", "httpMethod", "pathPattern", "accessZones",
            "priority", "transportPolicy", "pathMatchType", "yuhengRouteSource");
    private static final Set<String> LEGACY_TRANSPORT_FIELDS = Set.of("profile", "transportProtocol", "requestBodyMode",
            "responseMode", "maxRequestBodyBytes", "connectTimeoutMs", "responseHeaderTimeoutMs", "streamIdleTimeoutMs",
            "totalTimeoutMs", "websocketIdleTimeoutMs", "websocketMaxFrameBytes", "bodyLogEnabled", "retryEnabled");
    private static final Set<String> HTTP_METHODS = Set.of("GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE", "PATCH", "*");

    private final ObjectMapper json;

    @Autowired
    public GatewayHttpRouteCompiler(@Qualifier("jacksonObjectMapper") ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json").copy()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    /** expectedDesiredGeneration is the generation this release commits, not a Kubernetes generation. */
    public CompiledExposure compile(ExposureInput input, PublicationBinding binding) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(binding, "binding");
        validateInput(input, binding);
        GatewayRuntimeRoute route = input.route();
        var canonical = canonicalMatch(route.host(), route.httpMethod(), route.pathPattern(),
                input.canonicalDraft().get("pathMatchType"));
        String host = canonical.host();
        String method = canonical.method();
        String path = canonical.path();
        PathMatchType match = canonical.type();
        if (!binding.approvedHostnames().stream().map(GatewayHttpRouteCompiler::hostname).toList().contains(host)
                || !binding.approvedMethods().contains(method)) {
            throw invalid("approvedMatch");
        }
        if (match == PathMatchType.PATH_PREFIX && !binding.approvedPathPrefixes().stream()
                .map(GatewayHttpRouteCompiler::canonicalPath).toList().contains(path)) {
            throw invalid("approvedPathPrefix");
        }

        ArrayNode backendSpecs = json.createArrayNode();
        ArrayNode frozenBackends = json.createArrayNode();
        ArrayNode dependencies = json.createArrayNode();
        List<BackendReference> backends = validateBackends(binding);
        for (BackendReference backend : backends) {
            backendSpecs.addObject().put("group", "").put("kind", "Service").put("namespace", backend.namespace())
                    .put("name", backend.name()).put("port", backend.port()).put("weight", backend.weight());
            ObjectNode frozen = frozenBackends.addObject().put("namespace", backend.namespace()).put("name", backend.name())
                    .put("uid", backend.uid()).put("port", backend.port()).put("weight", backend.weight())
                    .put("upstreamProtocol", backend.upstreamProtocol());
            if (backend.grant() != null) frozen.put("referenceGrantName", backend.grant().name());
            ObjectNode dependency = dependencies.addObject();
            dependency.set("service", json.valueToTree(backend));
        }
        ArrayNode applicationPolicies = validatePolicies(input, binding);
        ArrayNode resourceRefs = resources(input.resourceRefs());
        ObjectNode parent = json.createObjectNode().put("group", "gateway.networking.k8s.io").put("kind", "Gateway")
                .put("namespace", binding.gateway().namespace()).put("name", binding.gateway().name())
                .put("sectionName", binding.gateway().listenerName());

        ObjectNode content = json.createObjectNode().put("schemaVersion", SCHEMA_VERSION)
                .put("compilerVersion", COMPILER_VERSION).put("capabilityProfileId", CAPABILITY_PROFILE_ID)
                .put("backendAuthorizationProfile", AUTHORIZATION_PROFILE)
                .put("ownerTenantId", Long.toString(input.ownerTenantId()))
                .put("gatewayGroupId", Long.toString(input.gatewayGroupId()))
                .put("routeId", route.routeId()).put("operationId", route.operationId())
                .put("hostname", host).put("httpMethod", method).put("pathMatchType", match.name()).put("pathValue", path);
        content.set("binding", json.valueToTree(binding));
        content.set("backendRefs", frozenBackends);
        content.set("approvedParent", parent);
        content.set("applicationPolicies", applicationPolicies);
        content.set("resourceRefs", resourceRefs);
        content.set("removedRequestHeaders", json.valueToTree(REMOVED_RETRY_HEADERS));
        String digest = contentDigest(content);

        ObjectNode object = json.createObjectNode().put("apiVersion", "gateway.networking.k8s.io/v1").put("kind", "HTTPRoute");
        String name = "yuheng-exposure-" + input.exposureId();
        ObjectNode metadata = object.putObject("metadata").put("namespace", binding.routeNamespace()).put("name", name);
        metadata.putObject("labels").put("app.kubernetes.io/managed-by", "yuheng")
                .put("stellar.io/exposure-id", Long.toString(input.exposureId()))
                .put("stellar.io/owner-tenant-id", Long.toString(input.ownerTenantId()));
        ObjectNode spec = object.putObject("spec");
        spec.putArray("parentRefs"); // Creation is always inert; approved attach target remains separate.
        spec.putArray("hostnames").add(host);
        ObjectNode rule = spec.putArray("rules").addObject();
        ObjectNode routeMatch = rule.putArray("matches").addObject();
        if (!"*".equals(method)) routeMatch.put("method", method);
        routeMatch.putObject("path").put("type", match == PathMatchType.EXACT ? "Exact" : "PathPrefix").put("value", path);
        rule.putArray("filters").addObject().put("type", "RequestHeaderModifier")
                .putObject("requestHeaderModifier").set("remove", json.valueToTree(REMOVED_RETRY_HEADERS));
        rule.set("backendRefs", backendSpecs);

        ObjectNode entry = json.createObjectNode().put("exposureId", Long.toString(input.exposureId()))
                .put("routeId", route.routeId()).put("contentSha256", digest)
                .put("expectedDesiredGeneration", Long.toString(input.expectedDesiredGeneration())).put("requestedState", "OPEN");
        entry.set("resourceRefs", resourceRefs.deepCopy());
        entry.set("backendRefs", frozenBackends.deepCopy());
        entry.putObject("routeObject").put("kind", "HTTPRoute").put("namespace", binding.routeNamespace()).put("name", name);
        dependencies.addObject().set("publicationBinding", json.valueToTree(binding));
        return new CompiledExposure(input.exposureId(), host, method, match, path, digest, object, parent, entry, content, dependencies, binding);
    }

    /** Stable, sorted JSON digest for v2 material. Runtime IDs/generations are excluded by compile(). */
    public String contentDigest(JsonNode material) {
        Objects.requireNonNull(material, "material");
        try {
            byte[] bytes = json.writeValueAsBytes(sorted(material));
            if (bytes.length > 1_048_576) throw invalid("contentSize");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.io.IOException | NoSuchAlgorithmException failure) {
            throw new IllegalArgumentException("YUHENG_EXPOSURE_COMPILE_INVALID: canonicalContent", failure);
        }
    }

    private void validateInput(ExposureInput input, PublicationBinding binding) {
        positive(input.ownerTenantId(), "ownerTenantId"); positive(input.gatewayGroupId(), "gatewayGroupId");
        positive(input.exposureId(), "exposureId"); positive(input.expectedDesiredGeneration(), "desiredGeneration");
        decimal(input.releaseId(), false, "releaseId");
        GatewayRuntimeRoute route = Objects.requireNonNull(input.route(), "route");
        if (input.operationProtocol() != GatewayProtocol.HTTP || !input.externalAccessible() || !route.enabled()
                || !route.accessZones().contains(AccessZone.PUBLIC) || route.priority() != 0
                || !HTTP_METHODS.contains(route.httpMethod())) throw invalid("routeCapability");
        if (input.ownerTenantId() != binding.ownerTenantId() || input.gatewayGroupId() != binding.gatewayGroupId()
                || !route.routeId().equals(binding.routeId()) || !route.operationId().equals(binding.operationId())) {
            throw invalid("bindingOwner");
        }
        validateBinding(binding);
        Map<String, Object> draft = input.canonicalDraft();
        if (draft.containsKey("yuhengRouteSource") && !"YAML".equals(draft.get("yuhengRouteSource"))) {
            throw invalid("routeProvenance");
        }
        if (!DRAFT_FIELDS.containsAll(draft.keySet())
                || !Objects.equals(draft.get("host"), route.host())
                || !Objects.equals(draft.get("httpMethod"), route.httpMethod())
                || !Objects.equals(draft.get("pathPattern"), route.pathPattern())) throw invalid("draftShape");
        if (!(draft.get("accessZones") instanceof List<?> zones) || zones.isEmpty()
                || !new HashSet<>(zones).equals(route.accessZones().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()))) {
            throw invalid("accessZones");
        }
        if (draft.get("priority") != null && (!(draft.get("priority") instanceof Number number)
                || !number.toString().equals("0"))) throw invalid("priority");
        Object transport = draft.get("transportPolicy");
        if (transport != null && (!(transport instanceof Map<?, ?> policy)
                || policy.entrySet().stream().anyMatch(field ->
                        !isCompatibleLegacyTransportField(field.getKey(), field.getValue())))) throw invalid("transportPolicy");
        if (route.transportPolicy() != null) {
            JsonNode policy = json.valueToTree(route.transportPolicy());
            policy.fields().forEachRemaining(field -> {
                if (!field.getValue().isNull() && (!Set.of("retryEnabled", "bodyLogEnabled").contains(field.getKey())
                        || !field.getValue().isBoolean() || field.getValue().booleanValue())) throw invalid("transportPolicy");
            });
        }
    }

    /** Same pure capability checks for a draft preview; the placeholder IDs never leave this method. */
    public void validate(GatewayRuntimeRoute route, GatewayProtocol operationProtocol, boolean externalAccessible,
            Map<String, Object> canonicalDraft, List<GatewayRuntimePolicy> policies,
            List<ResourceReference> resourceRefs, PublicationBinding binding) {
        compile(new ExposureInput(binding.ownerTenantId(), binding.gatewayGroupId(), "1", 1L, 1L,
                route, operationProtocol, externalAccessible, canonicalDraft, policies, resourceRefs), binding);
    }

    /** Startup and compilation share the exact same structural authority and capability checks. */
    public void validateBinding(PublicationBinding binding) {
        Objects.requireNonNull(binding, "binding");
        positive(binding.ownerTenantId(), "ownerTenantId"); positive(binding.gatewayGroupId(), "gatewayGroupId");
        bounded(binding.routeId(), 128, "routeId"); bounded(binding.operationId(), 128, "operationId");
        positive(binding.bindingVersion(), "bindingVersion");
        bounded(binding.bindingId(), 128, "bindingId"); bounded(binding.environment(), 64, "environment");
        bounded(binding.logicalNamespace(), 128, "logicalNamespace");
        PublicationDomainVO domain = Objects.requireNonNull(binding.publicationDomain(), "publicationDomain");
        GatewayReference gateway = Objects.requireNonNull(binding.gateway(), "gateway");
        if (!domain.complete() || !domain.bindings().contains(new BindingVO(gateway.namespace(), gateway.name(), gateway.listenerName()))
                || !gateway.https443Verified() || !gateway.forwardedHeadersVerified()) throw invalid("gatewayBinding");
        dnsLabel(gateway.namespace(), "gatewayNamespace"); dnsSubdomain(gateway.name(), "gatewayName");
        dnsLabel(gateway.listenerName(), "listenerName"); bounded(gateway.uid(), 128, "gatewayUid");
        if (!"yuheng-routes".equals(binding.routeNamespace())) throw invalid("routeNamespace");
        if (binding.approvedHostnames().isEmpty() || binding.approvedMethods().isEmpty()) throw invalid("approvedMatch");
        if (!AUTHORIZATION_PROFILE.equals(binding.authorization().profile()) || !binding.authorization().resourceServerVerified()) {
            throw invalid("backendAuthorization");
        }
        URI resource;
        try { resource = URI.create(binding.authorization().resourceUri()); }
        catch (RuntimeException failure) { throw invalid("resourceUri"); }
        if (!"https".equals(resource.getScheme()) || resource.getHost() == null || resource.getUserInfo() != null
                || resource.getQuery() != null || resource.getFragment() != null) throw invalid("resourceUri");
        bounded(binding.authorization().version(), 128, "authorizationVersion");
        digest(binding.authorization().contentSha256(), "authorizationDigest");
        binding.approvedHostnames().forEach(GatewayHttpRouteCompiler::hostname);
        if (!HTTP_METHODS.containsAll(binding.approvedMethods())) throw invalid("approvedMethods");
        for (String prefix : binding.approvedPathPrefixes()) {
            if ("/".equals(canonicalPath(prefix))) throw invalid("unboundedPrefix");
        }
        validateBackends(binding);
        approvedPolicies(binding);
    }

    private List<BackendReference> validateBackends(PublicationBinding binding) {
        if (binding.backends().isEmpty() || binding.backends().size() > 16) throw invalid("backendRefs");
        Set<String> targets = new HashSet<>(); Set<String> serviceUids = new HashSet<>();
        Set<String> grants = new HashSet<>(); Set<String> endpoints = new HashSet<>(); long weight = 0;
        List<BackendReference> backends = binding.backends().stream().sorted(Comparator.comparing(BackendReference::namespace)
                .thenComparing(BackendReference::name).thenComparingInt(BackendReference::port)).toList();
        for (BackendReference backend : backends) {
            dnsLabel(backend.namespace(), "backendNamespace"); dnsSubdomain(backend.name(), "backendName");
            bounded(backend.uid(), 128, "backendUid"); bounded(backend.resourceVersion(), 128, "backendResourceVersion");
            digest(backend.endpointSliceDigest(), "endpointSliceDigest");
            if (backend.port() != 80 || backend.targetPort() != 8080 || backend.weight() < 0 || backend.weight() > 1_000_000
                    || !"HTTP_1_1".equals(backend.upstreamProtocol()) || !backend.networkAdmissionVerified()
                    || backend.readyEndpointIds().isEmpty() || !serviceUids.add(backend.uid())
                    || !targets.add(backend.namespace() + "/" + backend.name() + ":" + backend.port())) {
                throw invalid("backendRefs");
            }
            for (String endpoint : backend.readyEndpointIds()) {
                bounded(endpoint, 253, "readyEndpointIdentity");
                if (!endpoints.add(endpoint)) throw invalid("overlappingReadyEndpoints");
            }
            GrantReference grant = backend.grant();
            if (!backend.namespace().equals(binding.routeNamespace()) && grant == null) throw invalid("referenceGrant");
            if (grant != null) {
                dnsSubdomain(grant.name(), "referenceGrantName"); bounded(grant.uid(), 128, "referenceGrantUid");
                bounded(grant.resourceVersion(), 128, "referenceGrantResourceVersion");
                if (!grant.namespace().equals(backend.namespace()) || !grant.fromNamespace().equals(binding.routeNamespace())
                        || !grant.serviceName().equals(backend.name())
                        || !grants.add(grant.namespace() + "/" + grant.name())) throw invalid("referenceGrant");
            }
            weight += backend.weight();
        }
        if (weight <= 0 || weight > Integer.MAX_VALUE) throw invalid("backendWeight");
        return backends;
    }

    private ArrayNode validatePolicies(ExposureInput input, PublicationBinding binding) {
        ArrayNode result = json.createArrayNode();
        Map<String, PolicyReference> approved = approvedPolicies(binding);
        Set<String> observed = new HashSet<>();
        for (GatewayRuntimePolicy policy : input.policies().stream().sorted(Comparator.comparing(GatewayRuntimePolicy::policyId)).toList()) {
            PolicyReference reference = approved.get(policy.policyId());
            JsonNode configuration = json.valueToTree(policy.configuration());
            if (reference == null || !observed.add(policy.policyId()) || !policy.type().equals(reference.type())
                    || !policy.scope().equals(reference.scope()) || !contentDigest(configuration).equals(reference.contentSha256())) {
                throw invalid("policyCapability");
            }
            // Only verified application-owned policy references are frozen. No equivalent network policy is invented.
            result.add(json.valueToTree(reference));
        }
        if (!observed.equals(approved.keySet())) throw invalid("policyReferences");
        return result;
    }

    private Map<String, PolicyReference> approvedPolicies(PublicationBinding binding) {
        Map<String, PolicyReference> approved = new TreeMap<>();
        for (PolicyReference reference : binding.authorization().policies()) {
            if (!Set.of("AUTHENTICATION", "SECURITY").contains(reference.type()) || !"APPLICATION".equals(reference.owner())
                    || approved.putIfAbsent(reference.policyId(), reference) != null) throw invalid("approvedPolicy");
            digest(reference.contentSha256(), "policyDigest");
        }
        for (PolicyReference reference : approved.values()) {
            bounded(reference.policyId(), 128, "policyId"); bounded(reference.scope(), 64, "policyScope");
        }
        return approved;
    }

    private ArrayNode resources(List<ResourceReference> references) {
        ArrayNode result = json.createArrayNode(); Set<String> ids = new HashSet<>();
        for (ResourceReference reference : references.stream().sorted(Comparator.comparing(ResourceReference::kind)
                .thenComparing(ResourceReference::resourceId).thenComparing(ResourceReference::version)).toList()) {
            bounded(reference.kind(), 64, "resourceKind"); bounded(reference.resourceId(), 128, "resourceId");
            decimal(reference.version(), false, "resourceVersion"); digest(reference.contentSha256(), "resourceDigest");
            if (!ids.add(reference.kind() + ":" + reference.resourceId())) throw invalid("duplicateResource");
            result.add(json.valueToTree(reference));
        }
        return result;
    }

    /** Normalizes the match used by draft diagnostics, duplicate detection and release compilation. */
    public static RouteMatchVO canonicalMatch(String host, String method, String path, Object matchType) {
        if (method == null || !HTTP_METHODS.contains(method)) throw invalid("httpMethod");
        return new RouteMatchVO(hostname(host), method, canonicalPath(path), matchType(matchType));
    }

    /** Allows only disabled historical flags and null values of known historical fields. */
    public static boolean isCompatibleLegacyTransportField(Object name, Object value) {
        return name instanceof String field && LEGACY_TRANSPORT_FIELDS.contains(field)
                && (value == null || (("retryEnabled".equals(field) || "bodyLogEnabled".equals(field))
                        && Boolean.FALSE.equals(value)));
    }

    private static PathMatchType matchType(Object value) {
        if (value == null) return PathMatchType.EXACT;
        if (!(value instanceof String text)) throw invalid("pathMatchType");
        try { return PathMatchType.valueOf(text); }
        catch (IllegalArgumentException failure) { throw invalid("pathMatchType"); }
    }

    private static String hostname(String value) {
        bounded(value, 253, "hostname");
        String wildcard = value.startsWith("*.") ? "*." : "";
        String name = wildcard.isEmpty() ? value : value.substring(2);
        if (name.endsWith(".") || name.contains(":") || name.contains("/") || name.contains("*")) throw invalid("hostname");
        try {
            name = IDN.toASCII(name, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            dnsSubdomain(name, "hostname");
            return wildcard + name;
        } catch (IllegalArgumentException failure) { throw invalid("hostname"); }
    }

    private static String canonicalPath(String value) {
        bounded(value, 1024, "pathValue");
        if (!value.startsWith("/") || value.startsWith("//") || value.contains("//") || value.indexOf('\\') >= 0
                || value.indexOf('{') >= 0 || value.indexOf('}') >= 0 || value.indexOf('*') >= 0) throw invalid("pathValue");
        URI uri;
        try { uri = URI.create(value); } catch (IllegalArgumentException failure) { throw invalid("pathValue"); }
        if (!value.equals(uri.getRawPath()) || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawAuthority() != null) throw invalid("pathValue");
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '%') { normalized.append(character); continue; }
            if (index + 2 >= value.length()) throw invalid("pathEncoding");
            int octet;
            try { octet = Integer.parseInt(value.substring(index + 1, index + 3), 16); }
            catch (NumberFormatException failure) { throw invalid("pathEncoding"); }
            char decoded = (char) octet;
            if (decoded >= 'a' && decoded <= 'z' || decoded >= 'A' && decoded <= 'Z' || decoded >= '0' && decoded <= '9' || "-._~".indexOf(decoded) >= 0) {
                normalized.append(decoded);
            } else {
                if (octet < 0x20 || octet == 0x7f || octet == 0x5c) throw invalid("pathEncoding");
                normalized.append('%').append(value.substring(index + 1, index + 3).toUpperCase(Locale.ROOT));
            }
            index += 2;
        }
        for (String segment : normalized.toString().split("/", -1)) if (".".equals(segment) || "..".equals(segment)) throw invalid("pathDotSegment");
        return normalized.toString();
    }

    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode();
            List<String> names = new ArrayList<>(); node.fieldNames().forEachRemaining(names::add); Collections.sort(names);
            names.forEach(name -> result.set(name, sorted(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = json.createArrayNode(); node.forEach(value -> result.add(sorted(value))); return result;
        }
        if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) throw invalid("nonFiniteNumber");
        return node.deepCopy();
    }

    private static void positive(long value, String field) { if (value <= 0) throw invalid(field); }
    private static void decimal(String value, boolean zero, String field) {
        if (value == null || !value.matches(zero ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}")) throw invalid(field);
        try { Long.parseLong(value); } catch (NumberFormatException failure) { throw invalid(field); }
    }
    private static void bounded(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum || !value.chars().allMatch(c -> c >= 0x21 && c <= 0x7e)) throw invalid(field);
    }
    private static void dnsLabel(String value, String field) {
        if (value == null || !value.matches("[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?")) throw invalid(field);
    }
    private static void dnsSubdomain(String value, String field) {
        bounded(value, 253, field);
        for (String label : value.split("\\.", -1)) dnsLabel(label, field);
    }
    private static void digest(String value, String field) { if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid(field); }
    private static IllegalArgumentException invalid(String field) { return new IllegalArgumentException("YUHENG_EXPOSURE_COMPILE_INVALID: " + field); }

    @FunctionalInterface
    public interface BindingResolver {
        Optional<PublicationBinding> resolve(long ownerTenantId, long gatewayGroupId, String routeId, String operationId);
    }

    /** Immutable normalized HTTPRoute match, independent of placement and authorization. */
    public record RouteMatchVO(String host, String method, String path, PathMatchType type) { }

    public enum PathMatchType { EXACT, PATH_PREFIX }

    public record ExposureInput(long ownerTenantId, long gatewayGroupId, String releaseId, long exposureId,
            long expectedDesiredGeneration, GatewayRuntimeRoute route, GatewayProtocol operationProtocol,
            boolean externalAccessible, Map<String, Object> canonicalDraft, List<GatewayRuntimePolicy> policies,
            List<ResourceReference> resourceRefs) {
        public ExposureInput {
            canonicalDraft = freezeMap(Objects.requireNonNull(canonicalDraft, "canonicalDraft"));
            policies = policies.stream().map(policy -> new GatewayRuntimePolicy(policy.policyId(), policy.type(),
                    policy.scope(), freezeMap(policy.configuration()))).toList();
            resourceRefs = List.copyOf(resourceRefs);
        }
    }

    /** Platform authority, not caller-supplied DTO data. Resolver construction is owned by the application assembly. */
    public record PublicationBinding(long ownerTenantId, long gatewayGroupId, String routeId, String operationId,
            String environment, String logicalNamespace, String bindingId, long bindingVersion,
            PublicationDomainVO publicationDomain, GatewayReference gateway, String routeNamespace,
            Set<String> approvedHostnames, Set<String> approvedMethods, Set<String> approvedPathPrefixes,
            List<BackendReference> backends, AuthorizationBinding authorization) {
        public PublicationBinding {
            approvedHostnames = sortedSet(approvedHostnames); approvedMethods = sortedSet(approvedMethods);
            approvedPathPrefixes = sortedSet(approvedPathPrefixes);
            backends = backends.stream().sorted(Comparator.comparing(BackendReference::namespace)
                    .thenComparing(BackendReference::name).thenComparingInt(BackendReference::port)).toList();
            authorization = Objects.requireNonNull(authorization, "authorization");
        }
    }

    public record GatewayReference(String namespace, String name, String uid, String listenerName,
                                   boolean https443Verified, boolean forwardedHeadersVerified) { }

    /** readyEndpointIds are verified Pod incarnation identities, compared across all version Services. */
    public record BackendReference(String namespace, String name, String uid, String resourceVersion,
            int port, int targetPort, int weight, String upstreamProtocol, GrantReference grant,
            String endpointSliceDigest, Set<String> readyEndpointIds, boolean networkAdmissionVerified) {
        public BackendReference { readyEndpointIds = sortedSet(readyEndpointIds); }
    }

    public record GrantReference(String namespace, String name, String uid, String resourceVersion,
                                 String fromNamespace, String serviceName) { }

    public record AuthorizationBinding(String profile, String resourceUri, String version, String contentSha256,
                                       boolean resourceServerVerified, List<PolicyReference> policies) {
        public AuthorizationBinding { policies = policies.stream().sorted(Comparator.comparing(PolicyReference::policyId)).toList(); }
    }

    public record PolicyReference(String policyId, String type, String scope, String contentSha256, String owner) { }
    public record ResourceReference(String kind, String resourceId, String version, String contentSha256) { }

    public record CompiledExposure(long exposureId, String hostname, String httpMethod, PathMatchType pathMatchType,
            String pathValue, String contentSha256, JsonNode routeObject, JsonNode approvedParent, JsonNode snapshotEntry,
            JsonNode canonicalContent, JsonNode dependencies, PublicationBinding binding) {
        public CompiledExposure {
            routeObject = routeObject.deepCopy(); approvedParent = approvedParent.deepCopy(); snapshotEntry = snapshotEntry.deepCopy();
            canonicalContent = canonicalContent.deepCopy(); dependencies = dependencies.deepCopy();
        }
        @Override public JsonNode routeObject() { return routeObject.deepCopy(); }
        @Override public JsonNode approvedParent() { return approvedParent.deepCopy(); }
        @Override public JsonNode snapshotEntry() { return snapshotEntry.deepCopy(); }
        @Override public JsonNode canonicalContent() { return canonicalContent.deepCopy(); }
        @Override public JsonNode dependencies() { return dependencies.deepCopy(); }
    }

    private static Set<String> sortedSet(Set<String> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(values, "values").stream().sorted().toList()));
    }

    private static Map<String, Object> freezeMap(Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(Objects.requireNonNull(key, "key"), freeze(value)));
        return Collections.unmodifiableMap(result);
    }
    private static Object freeze(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, nested) -> { if (!(key instanceof String name)) throw invalid("draftKey"); result.put(name, freeze(nested)); });
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) return Collections.unmodifiableList(list.stream().map(GatewayHttpRouteCompiler::freeze).toList());
        throw invalid("draftValue");
    }
}
