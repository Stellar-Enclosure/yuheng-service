package top.egon.cola.component.yuheng.admin.release.domain.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Three versioned native documents, independent from the retired Engine activation/chunk contract. */
public record GatewayCiliumReleaseArtifact(@NotNull JsonNode canonicalSnapshot,
        @NotNull JsonNode activationContent, @NotNull JsonNode chunkManifest) {
    public static final String SCHEMA_VERSION = "cilium-exposure-v2";
    public static final String CAPABILITY_PROFILE_ID = "cilium-1.20.5-gapi-1.6.3-edge-h1-gamma-v2";
    private static final int MAX_DOCUMENT_BYTES = 8 * 1024 * 1024;
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Set<String> STATES = Set.of("OPEN", "CLOSED", "WITHDRAWN");

    public GatewayCiliumReleaseArtifact {
        canonicalSnapshot = document(canonicalSnapshot);
        activationContent = document(activationContent);
        chunkManifest = document(chunkManifest);
        String release = positive(canonicalSnapshot, "releaseId");
        positive(canonicalSnapshot, "ownerTenantId"); positive(canonicalSnapshot, "gatewayGroupId");
        String generatedAt = text(canonicalSnapshot, "generatedAt");
        try { if (!generatedAt.endsWith("Z")) throw invalid(); Instant.parse(generatedAt); }
        catch (RuntimeException malformed) { throw invalid(); }
        object(canonicalSnapshot.get("sourceContent"));
        JsonNode publication = object(canonicalSnapshot.get("exposurePublication"));
        fixed(publication, "schemaVersion", SCHEMA_VERSION); fixed(publication, "target", "CILIUM_GATEWAY_API");
        fixed(publication, "gatewayClassName", "cilium"); fixed(publication, "routeControllerName", "io.cilium/gateway-controller");
        fixed(publication, "compilerVersion", "2"); fixed(publication, "capabilityProfileId", CAPABILITY_PROFILE_ID);
        fixed(publication, "backendAuthorizationProfile", "oauth2-resource-server-v1"); text(publication, "publicationDomainId");
        var entries = new HashMap<String, JsonNode>();
        for (JsonNode entry : array(publication, "exposures")) {
            String id = positive(entry, "exposureId");
            if (entries.putIfAbsent(id, entry) != null) throw invalid();
            text(entry, "routeId"); digest(entry, "contentSha256"); positive(entry, "expectedDesiredGeneration");
            if (!STATES.contains(text(entry, "requestedState"))) throw invalid();
            array(entry, "resourceRefs"); array(entry, "backendRefs");
            JsonNode route = object(entry.get("routeObject")); fixed(route, "kind", "HTTPRoute");
            text(route, "namespace"); fixed(route, "name", "yuheng-exposure-" + id);
        }
        fixed(activationContent, "schemaVersion", SCHEMA_VERSION); fixed(activationContent, "releaseId", release);
        var activated = new HashSet<String>();
        for (JsonNode result : array(activationContent, "exposures")) {
            String id = positive(result, "exposureId");
            JsonNode source = entries.get(id);
            if (source == null || !activated.add(id)) throw invalid();
            fixed(result, "desiredGeneration", positive(source, "expectedDesiredGeneration"));
            fixed(result, "contentSha256", digest(source, "contentSha256"));
            if (!STATES.contains(text(result, "effectiveState"))) throw invalid();
            JsonNode precondition = result.get("precondition");
            if (precondition == null) throw invalid();
            if (!precondition.isNull()) {
                object(precondition); nonNegative(precondition, "version"); positive(precondition, "desiredGeneration");
                if (!STATES.contains(text(precondition, "desiredState"))) throw invalid();
            }
        }
        if (!activated.equals(entries.keySet())) throw invalid();
        fixed(chunkManifest, "schemaVersion", SCHEMA_VERSION); fixed(chunkManifest, "compilerVersion", "2");
        fixed(chunkManifest, "capabilityProfileId", CAPABILITY_PROFILE_ID); array(chunkManifest, "dependencies");
        var materialized = new HashSet<String>();
        for (JsonNode resource : array(chunkManifest, "resources")) {
            String id = positive(resource, "exposureId");
            JsonNode source = entries.get(id);
            if (source == null || !materialized.add(id)) throw invalid();
            fixed(resource, "contentSha256", digest(source, "contentSha256"));
            JsonNode route = object(resource.get("object"));
            fixed(route, "apiVersion", "gateway.networking.k8s.io/v1"); fixed(route, "kind", "HTTPRoute");
            JsonNode metadata = object(route.get("metadata"));
            fixed(metadata, "namespace", text(source.get("routeObject"), "namespace"));
            fixed(metadata, "name", text(source.get("routeObject"), "name"));
            JsonNode spec = object(route.get("spec"));
            if (!array(spec, "parentRefs").isEmpty()) throw invalid();
            JsonNode parent = object(resource.get("approvedParent"));
            fixed(parent, "group", "gateway.networking.k8s.io"); fixed(parent, "kind", "Gateway");
            text(parent, "namespace"); text(parent, "name"); text(parent, "sectionName");
        }
        if (!materialized.equals(entries.keySet())) throw invalid();
    }

    @Override public JsonNode canonicalSnapshot() { return canonicalSnapshot.deepCopy(); }
    @Override public JsonNode activationContent() { return activationContent.deepCopy(); }
    @Override public JsonNode chunkManifest() { return chunkManifest.deepCopy(); }
    public String releaseId() { return canonicalSnapshot.get("releaseId").textValue(); }
    public String ownerTenantId() { return canonicalSnapshot.get("ownerTenantId").textValue(); }
    public String gatewayGroupId() { return canonicalSnapshot.get("gatewayGroupId").textValue(); }
    public String ruleContentSha256() { return sha256(canonicalSnapshot.get("exposurePublication")); }
    public String artifactSha256() {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.set("canonicalSnapshot", canonicalSnapshot); envelope.set("activationContent", activationContent);
        envelope.set("chunkManifest", chunkManifest);
        return sha256(envelope);
    }
    public long snapshotSize() { return bytes(canonicalSnapshot).length; }
    @Override public String toString() { return "Cilium release artifact"; }

    private static JsonNode document(JsonNode value) {
        JsonNode result = normalize(object(value), new IdentityHashMap<>(), 0, new int[] { 0 });
        if (bytes(result).length > MAX_DOCUMENT_BYTES) throw invalid();
        return result;
    }

    /** Sorted objects, retained array order/nulls and normalized numbers survive PostgreSQL jsonb formatting. */
    private static JsonNode normalize(JsonNode value, IdentityHashMap<JsonNode, Boolean> active, int depth, int[] nodes) {
        if (value == null || depth > 64 || ++nodes[0] > 500_000 || value.isMissingNode() || value.isPojo() || value.isBinary()) throw invalid();
        if (value.isContainerNode()) {
            if (value.size() > 10_000 || active.put(value, Boolean.TRUE) != null) throw invalid();
            try {
                if (value.isObject()) {
                    ObjectNode copy = JSON.createObjectNode(); var names = new TreeSet<String>(); value.fieldNames().forEachRemaining(names::add);
                    for (String name : names) copy.set(name, normalize(value.get(name), active, depth + 1, nodes));
                    return copy;
                }
                var copy = JSON.createArrayNode();
                value.forEach(item -> copy.add(normalize(item, active, depth + 1, nodes)));
                return copy;
            } finally { active.remove(value); }
        }
        if (value.isNumber()) {
            if (value.isFloatingPointNumber() && !Double.isFinite(value.doubleValue())) throw invalid();
            BigDecimal decimal = value.decimalValue().stripTrailingZeros();
            return JSON.getNodeFactory().numberNode(decimal.signum() == 0 ? BigDecimal.ZERO : decimal);
        }
        if (value.isTextual() && value.textValue().length() > MAX_DOCUMENT_BYTES) throw invalid();
        return value.deepCopy();
    }

    private static byte[] bytes(JsonNode node) { return node.toString().getBytes(StandardCharsets.UTF_8); }
    private static String sha256(JsonNode node) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(bytes(normalize(node, new IdentityHashMap<>(), 0, new int[] { 0 })))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static JsonNode object(JsonNode node) { if (node == null || !node.isObject()) throw invalid(); return node; }
    private static JsonNode array(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key); if (value == null || !value.isArray()) throw invalid(); return value;
    }
    private static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 1024) throw invalid();
        return value.textValue();
    }
    private static String positive(JsonNode node, String key) {
        String value = text(node, key);
        if (!value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try { Long.parseLong(value); } catch (NumberFormatException overflow) { throw invalid(); }
        return value;
    }
    private static void nonNegative(JsonNode node, String key) {
        String value = text(node, key);
        if (!"0".equals(value)) positive(node, key);
    }
    private static String digest(JsonNode node, String key) {
        String value = text(node, key); if (!value.matches("[0-9a-f]{64}")) throw invalid(); return value;
    }
    private static void fixed(JsonNode node, String key, String expected) { if (!Objects.equals(text(node, key), expected)) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("YUHENG_CILIUM_ARTIFACT_INVALID"); }
}
