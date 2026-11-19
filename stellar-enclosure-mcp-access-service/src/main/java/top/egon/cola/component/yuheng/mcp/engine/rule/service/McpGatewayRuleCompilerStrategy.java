package top.egon.cola.component.yuheng.mcp.engine.rule.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.egon.cola.component.tianshu.format.DdcConfigCanonicalJson;
import top.egon.cola.component.yuheng.contract.mcp.rule.*;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.BindingContextVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.BindingPinVO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.service.McpPublishedBindingLoader.SelectionVO;
import top.egon.cola.component.yuheng.mcp.engine.rule.domain.McpGatewayCompiledRulesDTO;
import top.egon.cola.component.yuheng.mcp.rule.service.McpRuleCompiler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * Reads a SHA-pinned local MCP projection. No configuration centre, generic gateway snapshot,
 * provider directory or implicit network discovery participates in compilation.
 */
public final class McpGatewayRuleCompilerStrategy {
    public static final String SCHEMA_VERSION = "mcp-rule-projection-v1";
    private static final int MAX_BYTES = 4_194_304;
    private static final Set<String> CONTENT_FIELDS = Set.of("servers", "tools", "resources", "resourceTemplates",
            "prompts", "taskPolicies", "apps", "remoteProviders", "remoteMounts");
    private final ObjectMapper json;
    private final McpPublishedBindingLoader bindings;
    private final long installationOwnerTenantId;
    private final Clock clock;
    private final McpRuleCompiler compiler = new McpRuleCompiler();

    public McpGatewayRuleCompilerStrategy(ObjectMapper json, McpPublishedBindingLoader bindings,
                                          long installationOwnerTenantId, Clock clock) {
        this.json = Objects.requireNonNull(json, "json").copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        this.json.setConfig(this.json.getDeserializationConfig().without(MapperFeature.ALLOW_COERCION_OF_SCALARS));
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        if (installationOwnerTenantId <= 0) throw invalid();
        this.installationOwnerTenantId = installationOwnerTenantId;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Structural/local verification only. The caller owns atomic lifecycle publication and readiness;
     * actual SI authorization is checked by the binding loader separately at every execution.
     * contentSha256 is JCS over the existing McpRuleContent JSON object (not a generic release).
     */
    public McpGatewayCompiledRulesDTO load(Path file, String expectedSha256) {
        return load(file, expectedSha256, false);
    }

    /** Explicitly retained catalogue: every declared historical pin must be present in the mounted binding inventory. */
    public McpGatewayCompiledRulesDTO loadHistorical(Path file, String expectedSha256) {
        return load(file, expectedSha256, true);
    }

    private McpGatewayCompiledRulesDTO load(Path file, String expectedSha256, boolean historical) {
        try {
            if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")) throw invalid();
            Path path = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
            for (Path parent = path; parent != null; parent = parent.getParent()) if (Files.isSymbolicLink(parent)) throw invalid();
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw invalid();
            byte[] bytes;
            try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length == 0 || bytes.length > MAX_BYTES
                    || !MessageDigest.isEqual(HexFormat.of().parseHex(expectedSha256), HexFormat.of().parseHex(sha256(bytes)))) throw invalid();
            JsonNode root = DdcConfigCanonicalJson.parse(bytes);
            fields(root, Set.of("schemaVersion", "ownerTenantId", "version", "validUntil", "contentSha256", "serverScopes", "bindingPins", "content"));
            if (!SCHEMA_VERSION.equals(text(root, "schemaVersion")) || positive(root, "ownerTenantId") != installationOwnerTenantId) throw invalid();
            String version = text(root, "version"); positive(root, "version");
            String expiry = text(root, "validUntil"); if (!expiry.endsWith("Z")) throw invalid();
            Instant validUntil = Instant.parse(expiry); if (!validUntil.isAfter(clock.instant())) throw invalid();
            JsonNode raw = root.path("content"); fields(raw, CONTENT_FIELDS);
            for (String field : CONTENT_FIELDS) if (!raw.path(field).isArray() || raw.path(field).size() > 1000) throw invalid();
            String contentSha = text(root, "contentSha256");
            if (!contentSha.matches("[0-9a-f]{64}") || !contentSha.equals(sha256(DdcConfigCanonicalJson.canonicalBytes(raw)))) throw invalid();
            McpRuleContent content = json.treeToValue(raw, McpRuleContent.class);
            Map<String, Set<String>> scopes = serverScopes(root.path("serverScopes"), content);
            Map<SelectionVO, BindingPinVO> declared = bindingPins(root.path("bindingPins"));
            Map<SelectionVO, BindingContextVO> fixed = resolveBindings(declared, historical);
            Map<SelectionVO, BindingPinVO> pins = new LinkedHashMap<>();
            for (McpRuntimeTool tool : content.tools()) {
                if ("LOCAL_OPERATION".equals(tool.sourceType())) {
                    SelectionVO selection = new SelectionVO(installationOwnerTenantId, tool.serverCode(), tool.toolId());
                    BindingContextVO bound = fixed.get(selection);
                    if (bound == null) throw invalid();
                    verifyTool(tool, bound.tool()); pins.put(selection, bound.pin());
                    if (bound.validUntil().isBefore(validUntil)) validUntil = bound.validUntil();
                } else if (!"REMOTE_MCP".equals(tool.sourceType()) || tool.operationId() != null || tool.remoteMountId() == null) {
                    throw invalid();
                }
            }
            // Preserve operation-driven prompt/resource metadata only for the same explicitly bound server.
            for (var resource : content.resources()) verifyPrimitive(resource.serverCode(), resource.operationId(), resource.requiredPermissions(), fixed, pins);
            for (var template : content.resourceTemplates()) verifyPrimitive(template.serverCode(), template.operationId(), template.requiredPermissions(), fixed, pins);
            for (var prompt : content.prompts()) verifyPrimitive(prompt.serverCode(), prompt.operationId(), prompt.requiredPermissions(), fixed, pins);
            Set<String> operationIds = new HashSet<>();
            pins.keySet().forEach(selection -> operationIds.add(fixed.get(selection).operation().operationId()));
            if (!pins.equals(declared)) throw invalid();
            var compiled = compiler.compile(content, Set.copyOf(operationIds));
            for (var binding : fixed.values()) if (binding.validUntil().isBefore(validUntil)) validUntil = binding.validUntil();
            if (!validUntil.isAfter(clock.instant())) throw invalid();
            return new McpGatewayCompiledRulesDTO(installationOwnerTenantId, version, validUntil, contentSha, expectedSha256,
                    content, compiled, scopes, pins);
        } catch (IOException | RuntimeException failure) {
            // Only stable categories escape. No file contents, endpoint, token or arbitrary parser message is logged.
            throw invalid();
        }
    }

    private Map<SelectionVO, BindingContextVO> resolveBindings(Map<SelectionVO, BindingPinVO> declared, boolean historical) {
        Map<SelectionVO, BindingContextVO> result = new LinkedHashMap<>();
        if (!historical) {
            Map<SelectionVO, BindingContextVO> active = bindings.fixedActiveBindings();
            declared.forEach((selection, pin) -> {
                BindingContextVO value = active.get(selection);
                if (value == null || !pin.equals(value.pin())) throw invalid(); result.put(selection, value);
            });
        } else {
            List<BindingContextVO> mounted = bindings.fixedPinnedBindings();
            declared.forEach((selection, pin) -> {
                var matches = mounted.stream().filter(value -> selection.equals(value.selection()) && pin.equals(value.pin())).toList();
                if (matches.size() != 1) throw invalid(); result.put(selection, matches.getFirst());
            });
        }
        return Map.copyOf(result);
    }

    private Map<SelectionVO, BindingPinVO> bindingPins(JsonNode entries) {
        if (!entries.isArray() || entries.size() > 256) throw invalid();
        Map<SelectionVO, BindingPinVO> result = new LinkedHashMap<>();
        for (JsonNode entry : entries) {
            fields(entry, Set.of("selection", "pin")); JsonNode selection = entry.path("selection"), pin = entry.path("pin");
            fields(selection, Set.of("ownerTenantId", "serverCode", "toolId"));
            long owner = positive(selection, "ownerTenantId"); if (owner != installationOwnerTenantId) throw invalid();
            SelectionVO key = new SelectionVO(owner, text(selection, "serverCode"), text(selection, "toolId"));
            fields(pin, Set.of("bindingId", "bindingVersion", "resourceId", "versionId", "versionNo", "siDigestSha256",
                    "publicationId", "publicationArtifactSha256", "dependencyClosureDigest"));
            BindingPinVO value = new BindingPinVO(text(pin, "bindingId"), positive(pin, "bindingVersion"), text(pin, "resourceId"),
                    text(pin, "versionId"), positive(pin, "versionNo"), text(pin, "siDigestSha256"), text(pin, "publicationId"),
                    text(pin, "publicationArtifactSha256"), text(pin, "dependencyClosureDigest"));
            if (result.put(key, value) != null) throw invalid();
        }
        return Map.copyOf(result);
    }

    private void verifyPrimitive(String server, String operationId, Set<String> permissions,
                                 Map<SelectionVO, BindingContextVO> fixed, Map<SelectionVO, BindingPinVO> pins) {
        if (operationId == null) return;
        var matches = fixed.values().stream().filter(binding -> binding.selection().ownerTenantId() == installationOwnerTenantId
                && binding.selection().serverCode().equals(server) && binding.operation().operationId().equals(operationId)).toList();
        if (matches.size() != 1 || !permissions.containsAll(matches.getFirst().tool().requiredPermissions())) throw invalid();
        BindingContextVO selected = matches.getFirst(); pins.put(selected.selection(), selected.pin());
    }

    private void verifyTool(McpRuntimeTool projected, McpRuntimeTool published) {
        if (!projected.toolId().equals(published.toolId()) || !projected.serverCode().equals(published.serverCode())
                || !projected.name().equals(published.name()) || !Objects.equals(projected.description(), published.description())
                || !projected.sourceType().equals(published.sourceType()) || !Objects.equals(projected.operationId(), published.operationId())
                || !Objects.equals(projected.operationProtocol(), published.operationProtocol()) || projected.remoteMountId() != null
                || !schema(projected.inputSchema()).equals(schema(published.inputSchema()))
                || !schema(projected.outputSchema()).equals(schema(published.outputSchema()))
                || !projected.annotations().equals(published.annotations())
                || !projected.requiredPermissions().containsAll(published.requiredPermissions())
                || McpRiskLevel.valueOf(projected.riskLevel()).ordinal() < McpRiskLevel.valueOf(published.riskLevel()).ordinal()
                || projected.enabled() && !published.enabled() || projected.idempotent() && !published.idempotent()) throw invalid();
    }

    private static JsonNode schema(String value) {
        return value == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.nullNode()
                : DdcConfigCanonicalJson.parse(value.getBytes(StandardCharsets.UTF_8));
    }
    private static Map<String, Set<String>> serverScopes(JsonNode node, McpRuleContent content) {
        if (!node.isObject()) throw invalid();
        Set<String> servers = new HashSet<>(); content.servers().forEach(server -> servers.add(server.serverCode()));
        Set<String> keys = new HashSet<>(); node.fieldNames().forEachRemaining(keys::add);
        if (!servers.equals(keys)) throw invalid();
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String server : keys) {
            JsonNode values = node.path(server); if (!values.isArray() || values.isEmpty() || values.size() > 64) throw invalid();
            Set<String> checked = new LinkedHashSet<>();
            for (JsonNode value : values) {
                if (!value.isTextual() || !value.textValue().matches("[a-z][a-z0-9._-]*(?::[A-Za-z0-9._*-]+)+")
                        || value.textValue().length() > 128 || !checked.add(value.textValue())) throw invalid();
            }
            result.put(server, Set.copyOf(checked));
        }
        return Map.copyOf(result);
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank() || !value.textValue().equals(value.textValue().strip())
                || value.textValue().length() > 2048 || value.textValue().codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return value.textValue();
    }
    private static long positive(JsonNode node, String field) {
        String value = text(node, field); if (!value.matches("[1-9][0-9]{0,18}")) throw invalid(); return Long.parseLong(value);
    }
    private static void fields(JsonNode node, Set<String> fields) {
        if (!node.isObject()) throw invalid(); Set<String> actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
        if (!fields.equals(actual)) throw invalid();
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("MCP_RULE_PROJECTION_INVALID"); }
}
