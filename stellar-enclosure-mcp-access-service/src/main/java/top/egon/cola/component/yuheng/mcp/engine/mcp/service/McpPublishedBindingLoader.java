package top.egon.cola.component.yuheng.mcp.engine.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import top.egon.cola.component.tianshu.api.client.DdcSiCatalogClient;
import top.egon.cola.component.tianshu.format.DdcConfigCanonicalJson;
import top.egon.cola.component.tianshu.model.si.DdcSiKindEnum;
import top.egon.cola.component.yuheng.contract.mcp.rule.McpRuntimeTool;
import top.egon.cola.component.yuheng.contract.protocol.GatewayProtocol;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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
 * Loads a deployment-pinned execution bundle. Published mapping evidence proves logical provenance;
 * only this separately provisioned bundle selects a Service, credential registration and audience.
 * Structural readiness is distinct from the fresh, per-selection SI authorization performed at admission.
 */
public final class McpPublishedBindingLoader {
    public static final String SCHEMA_VERSION = "mcp-execution-binding-v1";
    public static final String PUBLICATION_SCHEMA = "yuheng-mcp-mapping-publication-v1";
    private static final int MAX_FILE_BYTES = 4_194_304;
    private static final int MAX_BINDINGS = 256;
    private static final Set<String> SI_FIELDS = Set.of("resourceId", "versionId", "versionNo", "digestSha256",
            "bizCode", "namespaceCode", "envCode", "ownerAppCode", "resourceKey");
    private static final Set<String> DEFINITION_FIELDS = Set.of("summary", "tags", "requestSchema", "responseSchema",
            "errorSchema", "descriptorSnapshot", "attributes", "externalAccessible");
    private final Path file;
    private final String expectedSha256;
    private final DdcSiCatalogClient si;
    private final String trustedCallerAppCode;
    private final Clock clock;
    private volatile BundleVO bundle;
    private volatile String lastErrorCode = "MCP_BINDINGS_NOT_READY";

    /** The path and SHA come exclusively from deployment configuration, never from an MCP request. */
    public McpPublishedBindingLoader(Path file, String expectedSha256, DdcSiCatalogClient si,
                                    String trustedCallerAppCode, Clock clock) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.expectedSha256 = checkedDigest(expectedSha256);
        this.si = Objects.requireNonNull(si, "si");
        this.trustedCallerAppCode = coordinate(trustedCallerAppCode);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Reloads the same pinned input. A changed approved SHA requires a new deployment instance. */
    public synchronized void reload() {
        // Invalidate before I/O: a malformed replacement never silently retains a previous execution grant.
        bundle = null;
        lastErrorCode = "MCP_BINDINGS_NOT_READY";
        try {
            for (Path parent = file; parent != null; parent = parent.getParent()) {
                if (Files.isSymbolicLink(parent)) throw failure("MCP_BINDINGS_FILE_INVALID");
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw failure("MCP_BINDINGS_FILE_INVALID");
            byte[] bytes;
            try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            }
            if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES || !sameDigest(expectedSha256, sha256(bytes))) {
                throw failure("MCP_BINDINGS_FILE_INVALID");
            }
            JsonNode root = DdcConfigCanonicalJson.parse(bytes);
            fields(root, root.has("historicalBindings") ? Set.of("schemaVersion", "validUntil", "bindings", "historicalBindings")
                    : Set.of("schemaVersion", "validUntil", "bindings"));
            fixed(root, "schemaVersion", SCHEMA_VERSION);
            Instant expiry = utc(root, "validUntil");
            if (!expiry.isAfter(clock.instant())) throw failure("MCP_BINDINGS_EXPIRED");
            JsonNode entries = root.path("bindings");
            if (!entries.isArray() || entries.size() > MAX_BINDINGS) throw invalid();
            JsonNode historical = root.has("historicalBindings") ? root.path("historicalBindings") : JsonNodeFactory.instance.arrayNode();
            if (!historical.isArray() || entries.size() + historical.size() > MAX_BINDINGS) throw invalid();
            Map<SelectionVO, BindingContextVO> checked = new LinkedHashMap<>();
            Map<SelectionPinVO, BindingContextVO> pinned = new LinkedHashMap<>();
            Set<List<String>> identifiers = new HashSet<>();
            for (JsonNode entry : entries) {
                BindingContextVO context = parseBinding(entry, expiry);
                if (!identifiers.add(List.of(context.bindingId(), Long.toString(context.bindingVersion())))
                        || checked.put(context.selection(), context) != null
                        || pinned.put(new SelectionPinVO(context.selection(), context.pin()), context) != null) throw invalid();
            }
            for (JsonNode entry : historical) {
                BindingContextVO context = parseBinding(entry, expiry);
                if (!identifiers.add(List.of(context.bindingId(), Long.toString(context.bindingVersion())))
                        || pinned.put(new SelectionPinVO(context.selection(), context.pin()), context) != null) throw invalid();
            }
            bundle = new BundleVO(expiry, Map.copyOf(checked), Map.copyOf(pinned));
            lastErrorCode = "";
        } catch (BindingRejectedException rejected) {
            lastErrorCode = rejected.errorCode();
            throw rejected;
        } catch (IOException | IllegalArgumentException malformed) {
            lastErrorCode = "MCP_BINDINGS_FILE_INVALID";
            throw failure(lastErrorCode);
        }
    }

    /** Structural readiness only; it never claims that every SI authority is currently reachable. */
    public boolean isReady() {
        BundleVO current = bundle;
        return current != null && current.validUntil().isAfter(clock.instant());
    }

    public String lastErrorCode() {
        BundleVO current = bundle;
        return current != null && !current.validUntil().isAfter(clock.instant()) ? "MCP_BINDINGS_EXPIRED" : lastErrorCode;
    }

    /** Immutable structurally verified active catalogue only; enumeration never admits execution or calls SI. */
    public Map<SelectionVO, BindingContextVO> fixedActiveBindings() {
        BundleVO current = bundle;
        if (current == null) throw failure("MCP_BINDINGS_NOT_READY");
        if (!current.validUntil().isAfter(clock.instant())) throw failure("MCP_BINDINGS_EXPIRED");
        return current.bindings();
    }

    /** Immutable mounted active/history catalogue only; callers must still use requirePinned at dispatch. */
    public List<BindingContextVO> fixedPinnedBindings() {
        BundleVO current = bundle;
        if (current == null) throw failure("MCP_BINDINGS_NOT_READY");
        if (!current.validUntil().isAfter(clock.instant())) throw failure("MCP_BINDINGS_EXPIRED");
        return List.copyOf(current.pinned().values());
    }

    /** Admits one tool using fresh exact-version SI authorization. Failure of A does not revoke B. */
    public BindingContextVO require(SelectionVO selection) {
        return requireInternal(selection, null);
    }

    /** Resumes only the original immutable binding. A different active version is never substituted. */
    public BindingContextVO requirePinned(SelectionVO selection, BindingPinVO pin) {
        return requireInternal(selection, Objects.requireNonNull(pin, "pin"));
    }

    private BindingContextVO requireInternal(SelectionVO selection, BindingPinVO pin) {
        Objects.requireNonNull(selection, "selection");
        BundleVO current = bundle;
        if (current == null) throw failure("MCP_BINDINGS_NOT_READY");
        if (!current.validUntil().isAfter(clock.instant())) throw failure("MCP_BINDINGS_EXPIRED");
        BindingContextVO selected = pin == null ? current.bindings().get(selection)
                : current.pinned().get(new SelectionPinVO(selection, pin));
        if (selected == null) throw failure("MCP_BINDING_PIN_UNAVAILABLE");
        if (!selected.tool().enabled()) throw failure("MCP_TOOL_DISABLED");
        BindingContextVO active = current.bindings().get(selection);
        if (active != null) {
            if (!active.tool().enabled()) throw failure("MCP_TOOL_DISABLED");
            if (!selected.tool().requiredPermissions().containsAll(active.tool().requiredPermissions())
                    || risk(selected.tool().riskLevel()) < risk(active.tool().riskLevel())
                    || selected.tool().idempotent() && !active.tool().idempotent()) throw failure("MCP_BINDING_POLICY_REVOKED");
        }
        verifyAuthority(selected);
        // The synchronous authority read cannot extend expiry or revive a bundle invalidated during I/O.
        if (bundle != current || !current.validUntil().isAfter(clock.instant())) throw failure("MCP_BINDINGS_NOT_READY");
        return selected;
    }

    private void verifyAuthority(BindingContextVO binding) {
        SiReferenceVO ref = binding.si();
        try {
            var descriptor = si.resolveExecutionResource(binding.selection().ownerTenantId(), ref.bizCode(),
                    ref.namespaceCode(), ref.envCode(), ref.ownerAppCode(), trustedCallerAppCode,
                    DdcSiKindEnum.MCP, ref.resourceKey(), ref.versionNo()).orElseThrow(() -> failure("MCP_SI_UNAVAILABLE"));
            if (!Objects.equals(descriptor.getTenantId(), binding.selection().ownerTenantId())
                    || descriptor.getKind() != DdcSiKindEnum.MCP || descriptor.getStatus() == null
                    || !descriptor.getStatus().isQueryableByRuntime()
                    || !Objects.equals(descriptor.getBizCode(), ref.bizCode())
                    || !Objects.equals(descriptor.getNamespaceCode(), ref.namespaceCode())
                    || !Objects.equals(descriptor.getEnvCode(), ref.envCode())
                    || !Objects.equals(descriptor.getOwnerAppCode(), ref.ownerAppCode())
                    || !Objects.equals(descriptor.getResourceKey(), ref.resourceKey())
                    || !Objects.equals(descriptor.getResourceId(), Long.valueOf(ref.resourceId()))
                    || !Objects.equals(descriptor.getResolvedVersionId(), Long.valueOf(ref.versionId()))
                    || !Objects.equals(descriptor.getResolvedVersionNo(), ref.versionNo())
                    || !Objects.equals(descriptor.getDigestSha256(), ref.digestSha256())
                    || descriptor.getSpecJson() == null
                    || descriptor.getSpecJson().getBytes(StandardCharsets.UTF_8).length > 1_048_576
                    || !ref.digestSha256().equals(sha256(descriptor.getSpecJson().getBytes(StandardCharsets.UTF_8)))
                    || descriptor.getDependencyManifest() == null || !descriptor.isExecutionSnapshotShapeValid()
                    || !binding.pin().dependencyClosureDigest().equals(descriptor.getDependencyManifest().closureDigest())) {
                throw failure("MCP_SI_MISMATCH");
            }
            JsonNode spec = DdcConfigCanonicalJson.parse(descriptor.getSpecJson().getBytes(StandardCharsets.UTF_8));
            fixed(spec, "schemaVersion", "mcp-server-v2");
            fixed(spec, "sourceType", "COMPOSED_API");
            JsonNode pointer = spec.path("apiMapping");
            fields(pointer, Set.of("gatewayGroupId", "publicationId", "schemaVersion", "contentSha256", "artifactSha256"));
            PublicationReferenceVO proof = binding.sourceMapping();
            if (!proof.gatewayGroupId().equals(text(pointer, "gatewayGroupId"))
                    || !proof.publicationId().equals(text(pointer, "publicationId"))
                    || !proof.schemaVersion().equals(text(pointer, "schemaVersion"))
                    || !proof.contentSha256().equals(digest(pointer, "contentSha256"))
                    || !proof.artifactSha256().equals(digest(pointer, "artifactSha256"))) throw failure("MCP_SI_MISMATCH");
            verifyLiveTool(spec.path("tools"), binding.tool());
        } catch (BindingRejectedException rejected) {
            throw rejected;
        } catch (RuntimeException unavailable) {
            // Remote descriptions/status trailers and credentials are intentionally not propagated.
            throw failure("MCP_SI_AUTHORITY_UNAVAILABLE");
        }
    }

    private static void verifyLiveTool(JsonNode tools, McpRuntimeTool tool) {
        if (!tools.isArray() || tools.size() > 1000) throw failure("MCP_SI_MISMATCH");
        JsonNode selected = null;
        Set<String> ids = new HashSet<>();
        for (JsonNode candidate : tools) {
            String id = digest(candidate, "toolId");
            if (!ids.add(id)) throw failure("MCP_SI_MISMATCH");
            if (id.equals(tool.toolId())) selected = candidate;
        }
        if (selected == null || !bool(selected, "enabled")) throw failure("MCP_TOOL_DISABLED");
        if (!tool.name().equals(text(selected, "name")) || !Objects.equals(tool.description(), nullableText(selected, "description"))
                || !schema(tool.inputSchema()).equals(selected.path("inputSchema"))
                || !schema(tool.outputSchema()).equals(selected.path("outputSchema"))
                || !tool.requiredPermissions().containsAll(strings(selected.path("requiredPermissions")))
                || risk(tool.riskLevel()) < risk(text(selected, "riskLevel"))
                || tool.idempotent() && !bool(selected, "idempotent")) throw failure("MCP_SI_MISMATCH");
    }

    private static BindingContextVO parseBinding(JsonNode node, Instant expiry) {
        fields(node, Set.of("bindingId", "version", "ownerTenantId", "serverCode", "toolId", "si",
                "publicationEvidence", "sourceMappingEvidence", "operation", "transport"));
        String id = coordinate(text(node, "bindingId"));
        long version = positive(node, "version");
        SelectionVO selection = new SelectionVO(positive(node, "ownerTenantId"), coordinate(text(node, "serverCode")), digest(node, "toolId"));
        SiReferenceVO ref = siReference(node.path("si"));
        if (!selection.serverCode().equals(ref.resourceKey())) throw invalid();
        JsonNode proof = node.path("publicationEvidence");
        verifyPublicationEvidence(proof);
        String publicationId = decimal(proof, "publicationId");
        if (positive(proof, "ownerTenantId") != selection.ownerTenantId() || !siReference(proof.path("publishedSi")).equals(ref)) throw invalid();
        JsonNode source = proof.path("sourceRelease");
        fields(source, Set.of("releaseId", "artifactSha256", "mappingContentSha256"));
        fixed(source, "releaseId", publicationId);
        digest(source, "artifactSha256"); digest(source, "mappingContentSha256");
        String group = decimal(proof, "gatewayGroupId");
        ObjectNode content = JsonNodeFactory.instance.objectNode();
        for (String field : List.of("schemaVersion", "ownerTenantId", "gatewayGroupId", "publishedSi", "mappings")) content.set(field, proof.get(field));
        String contentDigest = digest(proof, "contentSha256");
        String artifactDigest = digest(proof, "artifactSha256");
        ObjectNode artifact = ((ObjectNode) proof).deepCopy(); artifact.remove("artifactSha256");
        if (!contentDigest.equals(artifactSha256(content)) || !artifactDigest.equals(artifactSha256(artifact))) throw invalid();
        JsonNode mappings = proof.path("mappings");
        if (!mappings.isArray() || mappings.isEmpty() || mappings.size() > 1000) throw invalid();
        JsonNode chosen = null;
        String previous = "";
        Set<String> operations = new HashSet<>();
        for (JsonNode mapping : mappings) {
            fields(mapping, Set.of("toolId", "sourceOperation", "siServer", "dependencyClosureDigest", "tool"));
            String toolId = digest(mapping, "toolId");
            if (toolId.compareTo(previous) <= 0 || !ref.equals(siReferenceWithNullableOwner(mapping.path("siServer")))) throw invalid();
            previous = toolId;
            JsonNode operation = mapping.path("sourceOperation");
            fields(operation, Set.of("operationId", "operationKey", "definitionDigest"));
            if (!operations.add(decimal(operation, "operationId"))) throw invalid();
            digest(operation, "definitionDigest");
            if (!toolId.equals(sha256((selection.serverCode() + '\0' + text(operation, "operationKey")).getBytes(StandardCharsets.UTF_8)))) throw invalid();
            McpRuntimeTool tool = tool(mapping.path("tool"));
            if (!toolId.equals(tool.toolId()) || !selection.serverCode().equals(tool.serverCode())
                    || !text(operation, "operationId").equals(tool.operationId())) throw invalid();
            digest(mapping, "dependencyClosureDigest");
            if (toolId.equals(selection.toolId())) chosen = mapping;
        }
        if (chosen == null) throw invalid();
        McpRuntimeTool tool = tool(chosen.path("tool"));
        OperationVO operation = operation(node.path("operation"), chosen.path("sourceOperation"), tool);
        TransportVO transport = transport(node.path("transport"), operation);
        PublicationReferenceVO publication = new PublicationReferenceVO(PUBLICATION_SCHEMA, group, publicationId, contentDigest, artifactDigest);
        PublicationReferenceVO sourceMapping = sourceMapping(node.path("sourceMappingEvidence"), selection, ref, chosen);
        if (!publication.gatewayGroupId().equals(sourceMapping.gatewayGroupId())) throw invalid();
        BindingPinVO pin = new BindingPinVO(id, version, ref.resourceId(), ref.versionId(), ref.versionNo(),
                ref.digestSha256(), publicationId, artifactDigest, digest(chosen, "dependencyClosureDigest"));
        return new BindingContextVO(selection, pin, tool, operation, transport, ref, publication, sourceMapping, expiry);
    }

    private static PublicationReferenceVO sourceMapping(JsonNode evidence, SelectionVO selection, SiReferenceVO ref, JsonNode chosen) {
        String schema = text(evidence, "schemaVersion");
        boolean seed = "yuheng-mcp-mapping-seed-v1".equals(schema);
        if (!seed && !PUBLICATION_SCHEMA.equals(schema)) throw invalid();
        fields(evidence, seed ? Set.of("schemaVersion", "publicationId", "ownerTenantId", "gatewayGroupId", "sourceDraftRevision",
                "frozenAt", "registeredSi", "mappings", "contentSha256", "artifactSha256")
                : Set.of("schemaVersion", "publicationId", "ownerTenantId", "gatewayGroupId", "sourceRelease", "publishedSi", "mappings", "contentSha256", "artifactSha256"));
        if (positive(evidence, "ownerTenantId") != selection.ownerTenantId()) throw invalid();
        String group = decimal(evidence, "gatewayGroupId"), publication = decimal(evidence, "publicationId");
        JsonNode identity = evidence.path(seed ? "registeredSi" : "publishedSi");
        if (seed) {
            fields(identity, Set.of("registrationId", "resourceId", "bizCode", "namespaceCode", "envCode", "ownerAppCode",
                    "resourceKey", "kind", "registrationRevision", "registrationDescriptorSha256"));
            decimal(identity, "registrationId"); nonnegative(identity, "registrationRevision");
            digest(identity, "registrationDescriptorSha256"); fixed(identity, "kind", "MCP");
            nonnegative(evidence, "sourceDraftRevision"); utc(evidence, "frozenAt");
        } else {
            siReference(identity);
            JsonNode source = evidence.path("sourceRelease"); fields(source, Set.of("releaseId", "artifactSha256", "mappingContentSha256"));
            fixed(source, "releaseId", publication); digest(source, "artifactSha256"); digest(source, "mappingContentSha256");
        }
        if (!ref.resourceId().equals(decimal(identity, "resourceId")) || !ref.bizCode().equals(text(identity, "bizCode"))
                || !ref.namespaceCode().equals(text(identity, "namespaceCode")) || !ref.envCode().equals(text(identity, "envCode"))
                || !Objects.equals(ref.ownerAppCode(), nullableText(identity, "ownerAppCode"))
                || !ref.resourceKey().equals(text(identity, "resourceKey"))) throw invalid();
        ObjectNode material = JsonNodeFactory.instance.objectNode();
        for (String name : seed ? List.of("schemaVersion", "registeredSi", "mappings")
                : List.of("schemaVersion", "ownerTenantId", "gatewayGroupId", "publishedSi", "mappings")) material.set(name, evidence.get(name));
        String content = digest(evidence, "contentSha256"), artifact = digest(evidence, "artifactSha256");
        ObjectNode full = ((ObjectNode) evidence).deepCopy(); full.remove("artifactSha256");
        if (!content.equals(artifactSha256(material)) || !artifact.equals(artifactSha256(full))) throw invalid();
        JsonNode mappings = evidence.path("mappings");
        if (!mappings.isArray() || mappings.isEmpty() || mappings.size() > 1000) throw invalid();
        JsonNode sourceEntry = null; String previous = ""; Set<String> operations = new HashSet<>();
        for (JsonNode entry : mappings) {
            fields(entry, seed ? Set.of("toolId", "sourceOperation", "tool")
                    : Set.of("toolId", "sourceOperation", "siServer", "dependencyClosureDigest", "tool"));
            String id = digest(entry, "toolId");
            if (id.compareTo(previous) <= 0) throw invalid(); previous = id;
            JsonNode operation = entry.path("sourceOperation"); fields(operation, Set.of("operationId", "operationKey", "definitionDigest"));
            if (!operations.add(decimal(operation, "operationId"))
                    || !id.equals(sha256((ref.resourceKey() + '\0' + text(operation, "operationKey")).getBytes(StandardCharsets.UTF_8)))) throw invalid();
            digest(operation, "definitionDigest");
            McpRuntimeTool tool = tool(entry.path("tool"));
            if (!id.equals(tool.toolId()) || !ref.resourceKey().equals(tool.serverCode())
                    || !text(operation, "operationId").equals(tool.operationId())) throw invalid();
            if (!seed && (!siReference(identity).equals(siReferenceWithNullableOwner(entry.path("siServer")))
                    || !digest(entry, "dependencyClosureDigest").matches("[0-9a-f]{64}"))) throw invalid();
            if (id.equals(selection.toolId())) sourceEntry = entry;
        }
        if (sourceEntry == null || !sourceEntry.path("sourceOperation").equals(chosen.path("sourceOperation"))) throw invalid();
        McpRuntimeTool before = tool(sourceEntry.path("tool")), after = tool(chosen.path("tool"));
        for (String field : List.of("toolId", "serverCode", "name", "description", "sourceType", "operationId", "operationProtocol",
                "remoteMountId", "annotations")) {
            if (!sourceEntry.path("tool").path(field).equals(chosen.path("tool").path(field))) throw invalid();
        }
        if (!schema(before.inputSchema()).equals(schema(after.inputSchema())) || !schema(before.outputSchema()).equals(schema(after.outputSchema()))
                || !after.requiredPermissions().containsAll(before.requiredPermissions()) || risk(after.riskLevel()) < risk(before.riskLevel())
                || after.enabled() && !before.enabled() || after.idempotent() && !before.idempotent()) throw invalid();
        return new PublicationReferenceVO(schema, group, publication, content, artifact);
    }

    /** Hash/shape compatibility check shared with the fixed emitter-vector test; not an execution grant. */
    static void verifyPublicationEvidence(JsonNode proof) {
        fields(proof, Set.of("schemaVersion", "publicationId", "ownerTenantId", "gatewayGroupId", "sourceRelease",
                "publishedSi", "mappings", "contentSha256", "artifactSha256"));
        fixed(proof, "schemaVersion", PUBLICATION_SCHEMA);
        String id = decimal(proof, "publicationId"); decimal(proof, "ownerTenantId"); decimal(proof, "gatewayGroupId");
        siReference(proof.path("publishedSi"));
        JsonNode source = proof.path("sourceRelease"); fields(source, Set.of("releaseId", "artifactSha256", "mappingContentSha256"));
        fixed(source, "releaseId", id); digest(source, "artifactSha256"); digest(source, "mappingContentSha256");
        if (!proof.path("mappings").isArray() || proof.path("mappings").isEmpty() || proof.path("mappings").size() > 1000) throw invalid();
        ObjectNode content = JsonNodeFactory.instance.objectNode();
        for (String name : List.of("schemaVersion", "ownerTenantId", "gatewayGroupId", "publishedSi", "mappings")) content.set(name, proof.get(name));
        ObjectNode full = ((ObjectNode) proof).deepCopy(); full.remove("artifactSha256");
        if (!digest(proof, "contentSha256").equals(artifactSha256(content))
                || !digest(proof, "artifactSha256").equals(artifactSha256(full))) throw invalid();
    }

    private static JsonNode projectedInput(JsonNode schema, GatewayProtocol protocol) {
        if (protocol == GatewayProtocol.RPC) return schema;
        ObjectNode result = ((ObjectNode) schema).deepCopy(); result.remove(List.of("properties", "required"));
        ObjectNode properties = result.putObject("properties");
        for (String field : List.of("path", "query", "body")) if (schema.path("properties").has(field)) properties.set(field, schema.path("properties").get(field));
        if (schema.path("required").isArray()) {
            ArrayNode required = JsonNodeFactory.instance.arrayNode();
            for (JsonNode entry : schema.path("required")) if (entry.isTextual() && Set.of("path", "query", "body").contains(entry.textValue())) required.add(entry);
            if (!required.isEmpty()) result.set("required", required);
        }
        return result;
    }

    private static OperationVO operation(JsonNode node, JsonNode source, McpRuntimeTool tool) {
        fields(node, Set.of("operationId", "operationKey", "protocol", "methodIdentity", "definitionDigest", "definitionJson", "rpcDescriptor"));
        for (String field : List.of("operationId", "operationKey", "definitionDigest")) fixed(node, field, text(source, field));
        GatewayProtocol protocol = GatewayProtocol.valueOf(text(node, "protocol"));
        if (!Set.of(GatewayProtocol.HTTP, GatewayProtocol.RPC).contains(protocol) || !protocol.name().equals(tool.operationProtocol())) throw invalid();
        String method = text(node, "methodIdentity");
        String definitionJson = rawText(node, "definitionJson", 1_048_576);
        if (!digest(node, "definitionDigest").equals(sha256(definitionJson.getBytes(StandardCharsets.UTF_8)))) throw invalid();
        JsonNode definition = DdcConfigCanonicalJson.parse(definitionJson.getBytes(StandardCharsets.UTF_8));
        fields(definition, DEFINITION_FIELDS);
        if (!definition.path("requestSchema").isObject() || !definition.path("responseSchema").isObject()
                || !definition.path("attributes").isObject() || !definition.path("tags").isArray()
                || !definition.path("errorSchema").isArray() || !definition.path("externalAccessible").isBoolean()) throw invalid();
        JsonNode exposure = definition.path("attributes").path("mcpExposure");
        if (!bool(exposure, "registerMcp") || !tool.serverCode().equals(text(exposure, "mcpServerCode"))) throw invalid();
        if (!schema(tool.inputSchema()).equals(projectedInput(definition.path("requestSchema"), protocol))
                || !schema(tool.outputSchema()).equals(definition.path("responseSchema"))) throw invalid();
        String description = definition.path("attributes").hasNonNull("description")
                ? text(definition.path("attributes"), "description") : nullableText(definition, "summary");
        if (!Objects.equals(tool.description(), description)) throw invalid();
        if (!tool.name().equals(text(exposure, "mcpName"))
                || !tool.requiredPermissions().containsAll(exposure.has("requiredPermissions") ? strings(exposure.path("requiredPermissions")) : Set.of())
                || risk(tool.riskLevel()) < risk(exposure.has("riskLevel") ? text(exposure, "riskLevel") : "LOW")
                || tool.idempotent() && !(exposure.has("idempotent") && bool(exposure, "idempotent"))) throw invalid();
        RpcDescriptorVO descriptor = null;
        if (protocol == GatewayProtocol.HTTP) {
            if (!node.path("rpcDescriptor").isNull() || !method.matches("(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS) /[^?#\\\\\\s]*")
                    || method.contains("..") || method.contains("//") || method.toLowerCase(Locale.ROOT).matches(".*%(2e|2f|5c).*")) throw invalid();
            if (definition.path("attributes").path("streaming").asBoolean(false)) throw invalid();
        } else {
            descriptor = rpcDescriptor(node.path("rpcDescriptor"), method);
            if (!definition.path("descriptorSnapshot").isObject()
                    || !descriptor.sha256().equals(text(definition.path("descriptorSnapshot"), "sha256"))) throw invalid();
        }
        return new OperationVO(text(node, "operationId"), text(node, "operationKey"), digest(node, "definitionDigest"),
                protocol, method, definition, descriptor);
    }

    private static RpcDescriptorVO rpcDescriptor(JsonNode node, String method) {
        fields(node, Set.of("sha256", "base64DescriptorSet", "serviceName", "group", "version", "fullMethodName", "requestType", "responseType"));
        String hash = digest(node, "sha256");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(rawText(node, "base64DescriptorSet", 1_500_000)); }
        catch (IllegalArgumentException malformed) { throw invalid(); }
        if (bytes.length == 0 || bytes.length > 1_048_576 || !hash.equals(sha256(bytes))) throw invalid();
        String service = text(node, "serviceName"), fullMethod = text(node, "fullMethodName");
        if (!fullMethod.equals(method) || !fullMethod.startsWith(service + "/") || fullMethod.indexOf('/') != fullMethod.lastIndexOf('/')) throw invalid();
        String request = text(node, "requestType"), response = text(node, "responseType");
        try {
            var set = DescriptorProtos.FileDescriptorSet.parseFrom(bytes);
            if (set.getFileCount() == 0 || set.getFileCount() > 256) throw invalid();
            Map<String, DescriptorProtos.FileDescriptorProto> remaining = new LinkedHashMap<>();
            for (var proto : set.getFileList()) if (remaining.put(proto.getName(), proto) != null) throw invalid();
            Map<String, Descriptors.FileDescriptor> built = new HashMap<>();
            while (!remaining.isEmpty()) {
                int count = remaining.size();
                for (var iterator = remaining.entrySet().iterator(); iterator.hasNext();) {
                    var proto = iterator.next().getValue();
                    if (proto.getDependencyList().stream().allMatch(built::containsKey)) {
                        var dependencies = proto.getDependencyList().stream().map(built::get).toArray(Descriptors.FileDescriptor[]::new);
                        built.put(proto.getName(), Descriptors.FileDescriptor.buildFrom(proto, dependencies)); iterator.remove();
                    }
                }
                if (remaining.size() == count) throw invalid();
            }
            var matches = built.values().stream().flatMap(file -> file.getServices().stream())
                    .filter(candidate -> candidate.getFullName().equals(service)).toList();
            if (matches.size() != 1) throw invalid();
            var selected = matches.getFirst().findMethodByName(fullMethod.substring(fullMethod.indexOf('/') + 1));
            if (selected == null || selected.isClientStreaming() || selected.isServerStreaming()
                    || !selected.getInputType().getFullName().equals(request) || !selected.getOutputType().getFullName().equals(response)) throw invalid();
        } catch (IOException | Descriptors.DescriptorValidationException malformed) { throw invalid(); }
        return new RpcDescriptorVO(hash, bytes, service, coordinate(text(node, "group")), coordinate(text(node, "version")), fullMethod, request, response);
    }

    private static TransportVO transport(JsonNode node, OperationVO operation) {
        fields(node, Set.of("namespace", "service", "port", "rpcBindingId", "rpcBindingVersion", "credential"));
        String namespace = dns(text(node, "namespace")), service = dns(text(node, "service"));
        long port = positive(node, "port"); if (port > 65535) throw invalid();
        String rpcId = nullableText(node, "rpcBindingId"); Long rpcVersion = node.path("rpcBindingVersion").isNull() ? null : positive(node, "rpcBindingVersion");
        if (operation.protocol() == GatewayProtocol.RPC) {
            if (rpcId == null || rpcVersion == null) throw invalid(); coordinate(rpcId);
        } else if (rpcId != null || rpcVersion != null) throw invalid();
        JsonNode auth = node.path("credential");
        fields(auth, Set.of("issuer", "registrationId", "appId", "identityTenantId", "audience", "scopes"));
        URI issuer = https(text(auth, "issuer")), audience = https(text(auth, "audience"));
        Set<String> scopes = strings(auth.path("scopes")); if (scopes.isEmpty() || scopes.size() > 64) throw invalid();
        CredentialVO credential = new CredentialVO(issuer, coordinate(text(auth, "registrationId")), coordinate(text(auth, "appId")),
                decimal(auth, "identityTenantId"), audience, scopes);
        return new TransportVO(namespace, service, (int) port, rpcId, rpcVersion, credential);
    }

    private static McpRuntimeTool tool(JsonNode n) {
        fields(n, Set.of("toolId", "serverCode", "name", "description", "sourceType", "operationId", "operationProtocol",
                "remoteMountId", "inputSchema", "outputSchema", "annotations", "requiredPermissions", "riskLevel", "idempotent", "enabled"));
        fixed(n, "sourceType", "LOCAL_OPERATION");
        if (!n.path("remoteMountId").isNull() || !n.path("annotations").isObject()) throw invalid();
        Map<String,String> annotations = new LinkedHashMap<>();
        n.path("annotations").properties().forEach(entry -> annotations.put(entry.getKey(), rawText(n.path("annotations"), entry.getKey(), 1024)));
        String input = nullableSchemaText(n, "inputSchema"), output = nullableSchemaText(n, "outputSchema");
        schema(input); schema(output);
        String risk = text(n, "riskLevel"); risk(risk);
        return new McpRuntimeTool(digest(n, "toolId"), coordinate(text(n, "serverCode")), text(n, "name"), nullableText(n, "description"),
                "LOCAL_OPERATION", decimal(n, "operationId"), text(n, "operationProtocol"), null, input, output,
                annotations, strings(n.path("requiredPermissions")), risk, bool(n, "idempotent"), bool(n, "enabled"));
    }

    private static SiReferenceVO siReference(JsonNode n) {
        fields(n, SI_FIELDS);
        return new SiReferenceVO(decimal(n, "resourceId"), decimal(n, "versionId"), positive(n, "versionNo"), digest(n, "digestSha256"),
                coordinate(text(n, "bizCode")), coordinate(text(n, "namespaceCode")), coordinate(text(n, "envCode")),
                nullableText(n, "ownerAppCode"), coordinate(text(n, "resourceKey")));
    }
    private static SiReferenceVO siReferenceWithNullableOwner(JsonNode n) {
        if (!n.isObject()) throw invalid();
        ObjectNode copy = ((ObjectNode) n).deepCopy(); if (!copy.has("ownerAppCode")) copy.putNull("ownerAppCode");
        return siReference(copy);
    }
    private static JsonNode schema(String text) {
        if (text == null) return JsonNodeFactory.instance.nullNode();
        JsonNode parsed = DdcConfigCanonicalJson.parse(text.getBytes(StandardCharsets.UTF_8));
        if (!parsed.isObject()) throw invalid(); return parsed;
    }
    private static int risk(String value) {
        return switch (value) { case "LOW" -> 0; case "MEDIUM" -> 1; case "HIGH" -> 2; case "CRITICAL" -> 3; default -> throw invalid(); };
    }
    private static Set<String> strings(JsonNode n) {
        if (!n.isArray() || n.size() > 1000) throw invalid();
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode value : n) {
            if (!value.isTextual() || value.textValue().isBlank() || !value.textValue().equals(value.textValue().strip())
                    || value.textValue().length() > 512 || value.textValue().codePoints().anyMatch(Character::isISOControl)
                    || !values.add(value.textValue())) throw invalid();
        }
        return Collections.unmodifiableSet(values);
    }
    private static URI https(String value) {
        URI uri;
        try { uri = URI.create(value); } catch (IllegalArgumentException malformed) { throw invalid(); }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || !uri.normalize().equals(uri)) throw invalid();
        return uri;
    }
    private static String dns(String value) {
        if (value.length() > 63 || !value.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) throw invalid(); return value;
    }
    private static String coordinate(String value) {
        if (value == null || value.isBlank() || value.length() > 256 || !value.matches("[A-Za-z0-9][A-Za-z0-9._:+-]*")) throw invalid(); return value;
    }
    private static String rawText(JsonNode node, String field, int maximum) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().length() > maximum) throw invalid(); return value.textValue();
    }
    private static String text(JsonNode node, String field) {
        String value = rawText(node, field, 2048);
        if (value.isBlank() || !value.equals(value.strip()) || value.codePoints().anyMatch(Character::isISOControl)) throw invalid(); return value;
    }
    private static String nullableSchemaText(JsonNode node, String field) {
        if (!node.has(field)) throw invalid(); return node.path(field).isNull() ? null : rawText(node, field, 1_048_576);
    }
    private static String nullableText(JsonNode node, String field) {
        if (!node.has(field)) throw invalid(); return node.path(field).isNull() ? null : text(node, field);
    }
    private static String decimal(JsonNode node, String field) {
        String value = text(node, field);
        if (!value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try { Long.parseLong(value); } catch (NumberFormatException malformed) { throw invalid(); } return value;
    }
    private static String nonnegative(JsonNode node, String field) {
        String value = text(node, field); if (!value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try { Long.parseLong(value); } catch (NumberFormatException malformed) { throw invalid(); } return value;
    }
    private static long positive(JsonNode node, String field) { return Long.parseLong(decimal(node, field)); }
    private static String digest(JsonNode node, String field) { return checkedDigest(text(node, field)); }
    private static String checkedDigest(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid(); return value; }
    private static boolean bool(JsonNode node, String field) { if (!node.path(field).isBoolean()) throw invalid(); return node.path(field).booleanValue(); }
    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw invalid();
        Set<String> actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
        if (!expected.equals(actual)) throw invalid();
    }
    private static void fixed(JsonNode node, String field, String expected) { if (!expected.equals(text(node, field))) throw invalid(); }
    private static Instant utc(JsonNode node, String field) {
        String value = text(node, field); if (!value.endsWith("Z")) throw invalid();
        try { return Instant.parse(value); } catch (java.time.DateTimeException malformed) { throw invalid(); }
    }
    /** Same bounded object-key/decimal normalization used by the committed Step32 mapping emitter. */
    private static JsonNode artifactNormalize(JsonNode node, int depth, int[] count) {
        if (node == null || depth > 64 || ++count[0] > 100_000) throw invalid();
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode(); List<String> keys = new ArrayList<>(); node.fieldNames().forEachRemaining(keys::add);
            keys.stream().sorted().forEach(key -> out.set(key, artifactNormalize(node.get(key), depth + 1, count))); return out;
        }
        if (node.isArray()) { ArrayNode out = JsonNodeFactory.instance.arrayNode(); node.forEach(value -> out.add(artifactNormalize(value, depth + 1, count))); return out; }
        if (node.isNumber()) {
            if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) throw invalid();
            return JsonNodeFactory.instance.numberNode(node.decimalValue().stripTrailingZeros());
        }
        return node.deepCopy();
    }
    private static String artifactSha256(JsonNode value) { return sha256(artifactNormalize(value, 0, new int[]{0}).toString().getBytes(StandardCharsets.UTF_8)); }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static boolean sameDigest(String a, String b) { return MessageDigest.isEqual(HexFormat.of().parseHex(a), HexFormat.of().parseHex(b)); }
    private static BindingRejectedException invalid() { return failure("MCP_BINDINGS_INVALID"); }
    private static BindingRejectedException failure(String code) { return new BindingRejectedException(code); }

    public record SelectionVO(long ownerTenantId, String serverCode, String toolId) {
        public SelectionVO { if (ownerTenantId <= 0) throw invalid(); serverCode = coordinate(serverCode); toolId = checkedDigest(toolId); }
    }
    public record BindingPinVO(String bindingId, long bindingVersion, String resourceId, String versionId, long versionNo,
                               String siDigestSha256, String publicationId, String publicationArtifactSha256, String dependencyClosureDigest) {
        public BindingPinVO {
            bindingId = coordinate(bindingId); if (bindingVersion <= 0 || versionNo <= 0) throw invalid();
            for (String id : List.of(resourceId, versionId, publicationId)) {
                if (!id.matches("[1-9][0-9]{0,18}")) throw invalid(); Long.parseLong(id);
            }
            checkedDigest(siDigestSha256); checkedDigest(publicationArtifactSha256); checkedDigest(dependencyClosureDigest);
        }
    }
    public record SiReferenceVO(String resourceId, String versionId, long versionNo, String digestSha256,
                                String bizCode, String namespaceCode, String envCode, String ownerAppCode, String resourceKey) { }
    public record PublicationReferenceVO(String schemaVersion, String gatewayGroupId, String publicationId, String contentSha256, String artifactSha256) { }
    public record CredentialVO(URI issuer, String registrationId, String appId, String identityTenantId, URI audience, Set<String> scopes) {
        public CredentialVO { scopes = Set.copyOf(scopes); }
    }
    public record TransportVO(String namespace, String service, int port, String rpcBindingId, Long rpcBindingVersion, CredentialVO credential) {
        public URI httpOrigin() { return URI.create("http://" + service + '.' + namespace + ".svc.cluster.local:" + port); }
    }
    public record RpcDescriptorVO(String sha256, byte[] descriptorSet, String serviceName, String group, String version,
                                  String fullMethodName, String requestType, String responseType) {
        public RpcDescriptorVO { descriptorSet = descriptorSet.clone(); }
        @Override public byte[] descriptorSet() { return descriptorSet.clone(); }
    }
    public record OperationVO(String operationId, String operationKey, String definitionDigest, GatewayProtocol protocol,
                              String methodIdentity, JsonNode definition, RpcDescriptorVO rpcDescriptor) {
        public OperationVO { definition = definition.deepCopy(); }
        @Override public JsonNode definition() { return definition.deepCopy(); }
    }
    /** Only the loader can construct an admitted binding; consumers cannot self-assert publication verification. */
    public static final class BindingContextVO {
        private final SelectionVO selection; private final BindingPinVO pin; private final McpRuntimeTool tool;
        private final OperationVO operation; private final TransportVO transport; private final SiReferenceVO si;
        private final PublicationReferenceVO publication; private final PublicationReferenceVO sourceMapping; private final Instant validUntil;
        private BindingContextVO(SelectionVO selection, BindingPinVO pin, McpRuntimeTool tool, OperationVO operation,
                                 TransportVO transport, SiReferenceVO si, PublicationReferenceVO publication, PublicationReferenceVO sourceMapping, Instant validUntil) {
            this.selection = selection; this.pin = pin; this.tool = tool; this.operation = operation;
            this.transport = transport; this.si = si; this.publication = publication; this.sourceMapping = sourceMapping; this.validUntil = validUntil;
        }
        public SelectionVO selection() { return selection; } public BindingPinVO pin() { return pin; }
        public String bindingId() { return pin.bindingId(); } public long bindingVersion() { return pin.bindingVersion(); }
        public McpRuntimeTool tool() { return tool; } public OperationVO operation() { return operation; }
        public TransportVO transport() { return transport; } public SiReferenceVO si() { return si; }
        public PublicationReferenceVO publication() { return publication; }
        public PublicationReferenceVO sourceMapping() { return sourceMapping; } public Instant validUntil() { return validUntil; }
    }
    private record SelectionPinVO(SelectionVO selection, BindingPinVO pin) { }
    private record BundleVO(Instant validUntil, Map<SelectionVO, BindingContextVO> bindings,
                            Map<SelectionPinVO, BindingContextVO> pinned) { }
    public static final class BindingRejectedException extends IllegalStateException {
        private final String errorCode;
        private BindingRejectedException(String errorCode) { super(errorCode); this.errorCode = errorCode; }
        public String errorCode() { return errorCode; }
    }
}
