package top.egon.cola.component.yuheng.admin.mcp.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import top.egon.cola.component.yuheng.admin.catalog.repository.GatewayCatalogRepository;
import top.egon.cola.component.yuheng.admin.group.domain.bo.GatewayGroupBO;
import top.egon.cola.component.yuheng.admin.group.repository.GatewayGroupRepository;
import top.egon.cola.component.yuheng.admin.mcp.domain.vo.*;
import top.egon.cola.component.yuheng.admin.mcp.service.McpReleaseContentFactory;
import top.egon.cola.component.yuheng.admin.observability.repository.GatewayAuditLogRepository;
import top.egon.cola.component.yuheng.admin.release.domain.bo.GatewayReleaseBO;
import top.egon.cola.component.yuheng.admin.release.domain.enums.GatewayReleaseStatus;
import top.egon.cola.component.yuheng.admin.release.domain.vo.GatewayCiliumReleaseArtifact;
import top.egon.cola.component.yuheng.admin.release.repository.GatewayReleaseRepository;
import top.egon.cola.component.yuheng.admin.release.service.GatewayReleaseService;
import top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayDraftBO;
import top.egon.cola.component.yuheng.admin.routing.repository.GatewayDraftJpaRepository;
import top.egon.cola.component.yuheng.admin.shared.domain.AdminActor;
import top.egon.cola.component.yuheng.admin.shared.domain.enums.AdminActorTypeEnum;

public class McpMappingPublicationExportTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant NOW = Instant.parse("2026-11-19T08:00:00Z");
    public static final AdminActor ACTOR = new AdminActor(
        "operator", AdminActorTypeEnum.USER, Set.of("yuheng:mcp:write"), Set.of(), Set.of("yuheng:mcp:write"));
    GatewayGroupRepository groups;
    GatewayDraftJpaRepository drafts;
    GatewayReleaseRepository releases;
    GatewayAuditLogRepository audits;
    McpReleaseContentFactory compiler;
    GatewayReleaseService service;
    GatewayDraftBO head;
    GatewayCiliumReleaseArtifact source;
    String previous;
    @BeforeEach
    void setup() {
        previous = MDC.get("tenantId");
        MDC.put("tenantId", "7");
        groups = mock(GatewayGroupRepository.class);
        drafts = mock(GatewayDraftJpaRepository.class);
        releases = mock(GatewayReleaseRepository.class);
        audits = mock(GatewayAuditLogRepository.class);
        compiler = mock(McpReleaseContentFactory.class);
        head = new GatewayDraftBO("10", "operator", NOW).setRevision(3).setBasedOnReleaseId("301");
        when(groups.findByIdAndDeletedFalse("10"))
            .thenReturn(
                Optional.of(new GatewayGroupBO().setId("10").setEnv("test").setNamespace("default").setEnabled(true)));
        when(drafts.findById("10")).thenAnswer(i -> Optional.of(head));
        source = nativeArtifact("301",
            JSON.createArrayNode()
                .add(mapping("201", "501", "1", "orders", "101", Set.of("mcp:orders:read")))
                .add(mapping("202", "502", "1", "payments", "102", Set.of("mcp:payments:read"))),
            false);
        when(releases.find("301")).thenReturn(Optional.of(header("301", GatewayReleaseStatus.SUCCESS)));
        when(releases.loadNative("301")).thenReturn(Optional.of(source));
        var beans = new StaticListableBeanFactory();
        beans.addBean("mcpReleaseContentFactory", compiler);
        service = new GatewayReleaseService(groups, drafts, null, mock(GatewayCatalogRepository.class), releases,
            audits, null, null, null, null, null, beans.getBeanProvider(McpReleaseContentFactory.class), JSON,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }
    @AfterEach
    void restore() {
        if (previous == null)
            MDC.remove("tenantId");
        else
            MDC.put("tenantId", previous);
    }
    GatewayReleaseBO header(String id, GatewayReleaseStatus status) {
        return new GatewayReleaseBO().setId(id).setGatewayGroupId("10").setStatus(status);
    }
    McpMappingPublicationEvidenceVO export() {
        return service.exportBoundMapping(7, "10", "301", "201", "501", "1", ACTOR);
    }
    @Test
    void boundEvidenceHasAnIndependentPerSiContract() {
        assertThat(McpMappingPublicationEvidenceVO.SCHEMA_VERSION).isEqualTo("yuheng-mcp-mapping-publication-v1");
    }
    @Test
    void approvedNativePublicationHasAnOperatorExportQuery() {
        var proof = export();
        assertThat(proof.document().path("mappings")).hasSize(1);
        assertThat(proof.document().path("publishedSi").path("resourceId").asText()).isEqualTo("201");
        assertThat(proof.document().path("sourceRelease").path("artifactSha256").asText())
            .isEqualTo(source.artifactSha256());
        assertThat(proof.document().path("sourceRelease").path("mappingContentSha256").asText())
            .isEqualTo(source.mcpPublication().path("contentSha256").asText());
        assertThat(new String(proof.canonicalBytes(), java.nio.charset.StandardCharsets.UTF_8))
            .doesNotContain(
                "payments", "sourceContent", "unrelated-draft-marker", "activationContent", "chunkManifest");
        verify(compiler).revalidateMappingSection(eq(7L), eq("10"),
            argThat(section
                -> section.path("mappings").size() == 1
                    && section.path("mappings").get(0).path("siServer").path("resourceId").asText().equals("201")));
        verify(drafts, never()).save(any());
        verify(releases, never()).insertNative(any(), any(), anyInt());
        verify(releases, never()).beginAttempt(anyString(), anyInt(), any());
        verifyNoInteractions(audits);
    }
    @Test
    void combinedHttpAndMultipleSiReleaseDoesNotBroadenTargetOrTouchRoutes() {
        source = nativeArtifact("301", source.mcpPublication().path("mappings"), true);
        when(releases.loadNative("301")).thenReturn(Optional.of(source));
        assertThat(export().document().path("mappings")).hasSize(1);
        assertThat(export().document().has("exposurePublication")).isFalse();
        verifyNoInteractions(audits);
    }
    @Test
    void exactVersionSelectorsNeverChooseAnotherVersionOrCurrent() {
        assertThatThrownBy(() -> service.exportBoundMapping(7, "10", "301", "201", "502", "1", ACTOR))
            .hasMessageContaining("unavailable");
        assertThatThrownBy(() -> service.exportBoundMapping(7, "10", "301", "201", "501", "2", ACTOR))
            .hasMessageContaining("unavailable");
        assertThatThrownBy(() -> service.exportBoundMapping(7, "10", "301", "201", "501", "0", ACTOR))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(compiler);
    }
    @Test
    void unrelatedSecondVersionCanOnlyBeReadByItsExactVersionIdentity() {
        var m = JSON.createArrayNode()
                    .add(mapping("201", "501", "1", "orders", "101", Set.of("mcp:orders:read")))
                    .add(mapping("201", "503", "2", "orders", "103", Set.of("mcp:orders:read")));
        source = nativeArtifact("301", m, false);
        when(releases.loadNative("301")).thenReturn(Optional.of(source));
        assertThat(export().document().path("mappings")).hasSize(1);
        assertThat(service.exportBoundMapping(7, "10", "301", "201", "503", "2", ACTOR)
                       .document()
                       .path("mappings")
                       .get(0)
                       .path("sourceOperation")
                       .path("operationId")
                       .asText())
            .isEqualTo("103");
    }
    @Test
    void currentEffectiveBasisCannotBeLoosenedByExportingOlderApprovedBytes() {
        var current = nativeArtifact("302",
            JSON.createArrayNode().add(
                mapping("201", "501", "1", "orders", "101", Set.of("mcp:orders:read", "mcp:orders:admin"))),
            false);
        head.setBasedOnReleaseId("302");
        when(releases.find("302")).thenReturn(Optional.of(header("302", GatewayReleaseStatus.SUCCESS)));
        when(releases.loadNative("302")).thenReturn(Optional.of(current));
        assertThatThrownBy(this::export).hasMessageContaining("REPLAY");
        verifyNoInteractions(compiler);
    }
    @Test
    void missingCurrentBasisOrNonSuccessfulSourceCannotProduceProof() {
        when(releases.find("301")).thenReturn(Optional.of(header("301", GatewayReleaseStatus.PUBLISHING)));
        assertThatThrownBy(this::export).hasMessageContaining("SUCCESS_REQUIRED");
        when(releases.find("301")).thenReturn(Optional.of(header("301", GatewayReleaseStatus.SUCCESS)));
        head.setBasedOnReleaseId(null);
        assertThatThrownBy(this::export).hasMessageContaining("REPLAY");
        head.setBasedOnReleaseId("302");
        when(releases.find("302")).thenReturn(Optional.of(header("302", GatewayReleaseStatus.SUCCESS)));
        when(releases.loadNative("302")).thenReturn(Optional.empty());
        assertThatThrownBy(this::export).hasMessageContaining("REPLAY");
    }
    @Test
    void currentSiAuthorizationFailureAndMovingBasisCannotReturnHistoricalFallback() {
        doThrow(new SecurityException("revoked"))
            .when(compiler)
            .revalidateMappingSection(anyLong(), anyString(), any());
        assertThatThrownBy(this::export).isInstanceOf(SecurityException.class);
        doAnswer(i -> {
            head.setRevision(4);
            return null;
        })
            .when(compiler)
            .revalidateMappingSection(anyLong(), anyString(), any());
        assertThatThrownBy(this::export).hasMessageContaining("REPLAY");
    }
    @Test
    void tenantAndExactOperatorScopeCannotBeManufacturedByRequestSelectors() {
        assertThatThrownBy(() -> service.exportBoundMapping(8, "10", "301", "201", "501", "1", ACTOR))
            .hasMessageContaining("not authorized");
        var noOauth = new AdminActor("operator", AdminActorTypeEnum.USER, Set.of("*"), Set.of(), Set.of());
        assertThatThrownBy(() -> service.exportBoundMapping(7, "10", "301", "201", "501", "1", noOauth))
            .hasMessageContaining("not authorized");
        verifyNoInteractions(releases);
    }
    @Test
    void conflictingRealmUnderTheSameExactVersionIsRejectedBeforeAuthorityAccess() {
        var first = mapping("201", "501", "1", "orders", "101", Set.of("mcp:orders:read"));
        var second = mapping("201", "501", "1", "orders", "103", Set.of("mcp:orders:read"));
        ((ObjectNode) second.path("siServer")).put("namespaceCode", "another-namespace");
        source = nativeArtifact("301", JSON.createArrayNode().add(first).add(second), false);
        when(releases.loadNative("301")).thenReturn(Optional.of(source));
        assertThatIllegalArgumentException().isThrownBy(this::export);
        verifyNoInteractions(compiler);
    }

    @Test
    void missingConfiguredAuthorityIsUnavailableRatherThanAnExportableHistoricalFallback() {
        doThrow(new top.egon.cola.component.yuheng.admin.mcp.domain.exception.McpValidationException(
                    "YUHENG_MCP_API_MAPPING_SI_UNAVAILABLE", "mapping.siServer", "operator configuration missing"))
            .when(compiler)
            .revalidateMappingSection(anyLong(), anyString(), any());
        assertThatThrownBy(this::export)
            .isInstanceOf(top.egon.cola.component.common.core.exception.CommonException.class)
            .hasMessage("The current SI authority is unavailable");
    }

    @Test
    void projectionHashesAndImmutableCopiesAreIndependentOfFullSourceArtifact() {
        var proof = export();
        var copy = (ObjectNode) proof.document();
        copy.put("publicationId", "999");
        assertThat(proof.publicationId()).isEqualTo("301");
        ObjectNode payload = (ObjectNode) proof.document();
        payload.remove("artifactSha256");
        assertThat(McpMappingSeedArtifactVO.sha256(McpMappingSeedArtifactVO.bytes(payload)))
            .isEqualTo(proof.artifactSha256());
        payload = (ObjectNode) proof.document();
        payload.put("canonicalSnapshot", "forbidden");
        ObjectNode bad = payload;
        assertThatIllegalArgumentException().isThrownBy(() -> new McpMappingPublicationEvidenceVO(bad));
    }
    public static McpMappingPublicationEvidenceVO vector() {
        var mappings =
            JSON.createArrayNode().add(mapping("201", "501", "1", "orders", "101", Set.of("mcp:orders:read")));
        return McpMappingPublicationEvidenceVO.create(nativeArtifact("301", mappings, false), mappings);
    }
    static ObjectNode mapping(
        String resource, String versionId, String versionNo, String code, String operation, Set<String> permissions) {
        String key = "http:" + code + ":GET:/" + code + "/" + operation;
        String id =
            McpMappingSeedArtifactVO.sha256((code + '\0' + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ObjectNode m = JSON.createObjectNode().put("toolId", id).put("dependencyClosureDigest", "c".repeat(64));
        m.putObject("sourceOperation")
            .put("operationId", operation)
            .put("operationKey", key)
            .put("definitionDigest", "a".repeat(64));
        m.putObject("siServer")
            .put("resourceId", resource)
            .put("versionId", versionId)
            .put("versionNo", versionNo)
            .put("digestSha256", "b".repeat(64))
            .put("bizCode", "commerce")
            .put("namespaceCode", "default")
            .put("envCode", "test")
            .put("resourceKey", code);
        var t = m.putObject("tool")
                    .put("toolId", id)
                    .put("serverCode", code)
                    .put("name", code + ".get")
                    .putNull("description")
                    .put("sourceType", "LOCAL_OPERATION")
                    .put("operationId", operation)
                    .put("operationProtocol", "HTTP")
                    .putNull("remoteMountId")
                    .put("inputSchema", "{\"type\":\"object\"}")
                    .put("outputSchema", "{\"type\":\"object\"}")
                    .put("riskLevel", "LOW")
                    .put("idempotent", true)
                    .put("enabled", true);
        t.putObject("annotations");
        var p = t.putArray("requiredPermissions");
        permissions.stream().sorted().forEach(p::add);
        return m;
    }
    static GatewayCiliumReleaseArtifact nativeArtifact(String id, JsonNode mappings, boolean http) {
        ObjectNode snapshot = JSON.createObjectNode()
                                  .put("releaseId", id)
                                  .put("ownerTenantId", "7")
                                  .put("gatewayGroupId", "10")
                                  .put("generatedAt", NOW.toString());
        snapshot.putObject("sourceContent").put("unrelated-draft-marker", "not-exported");
        snapshot.set("mcpPublication", GatewayCiliumReleaseArtifact.mcpPublication(mappings));
        ObjectNode activation = JSON.createObjectNode().put("schemaVersion", "cilium-exposure-v2").put("releaseId", id);
        var activations = activation.putArray("exposures");
        ObjectNode manifest = JSON.createObjectNode()
                                  .put("schemaVersion", "cilium-exposure-v2")
                                  .put("compilerVersion", "2")
                                  .put("capabilityProfileId", GatewayCiliumReleaseArtifact.CAPABILITY_PROFILE_ID);
        manifest.putArray("dependencies");
        var resources = manifest.putArray("resources");
        if (http) {
            var publication = snapshot.putObject("exposurePublication")
                                  .put("schemaVersion", "cilium-exposure-v2")
                                  .put("target", "CILIUM_GATEWAY_API")
                                  .put("gatewayClassName", "cilium")
                                  .put("routeControllerName", "io.cilium/gateway-controller")
                                  .put("compilerVersion", "2")
                                  .put("capabilityProfileId", GatewayCiliumReleaseArtifact.CAPABILITY_PROFILE_ID)
                                  .put("publicationDomainId", "public-apps")
                                  .put("backendAuthorizationProfile", "oauth2-resource-server-v1");
            var entry = publication.putArray("exposures")
                            .addObject()
                            .put("exposureId", "810001")
                            .put("routeId", "orders-read")
                            .put("contentSha256", "d".repeat(64))
                            .put("expectedDesiredGeneration", "1")
                            .put("requestedState", "OPEN");
            entry.putArray("resourceRefs");
            entry.putArray("backendRefs");
            entry.putObject("routeObject")
                .put("kind", "HTTPRoute")
                .put("namespace", "yuheng-routes")
                .put("name", "yuheng-exposure-810001");
            activations.addObject()
                .put("exposureId", "810001")
                .put("desiredGeneration", "1")
                .put("effectiveState", "OPEN")
                .put("contentSha256", "d".repeat(64))
                .putNull("precondition");
            var resource = resources.addObject().put("exposureId", "810001").put("contentSha256", "d".repeat(64));
            var route =
                resource.putObject("object").put("apiVersion", "gateway.networking.k8s.io/v1").put("kind", "HTTPRoute");
            route.putObject("metadata").put("namespace", "yuheng-routes").put("name", "yuheng-exposure-810001");
            route.putObject("spec").putArray("parentRefs");
            resource.putObject("approvedParent")
                .put("group", "gateway.networking.k8s.io")
                .put("kind", "Gateway")
                .put("namespace", "edge-system")
                .put("name", "public-apps")
                .put("sectionName", "https-api");
        }
        return new GatewayCiliumReleaseArtifact(snapshot, activation, manifest);
    }
}
