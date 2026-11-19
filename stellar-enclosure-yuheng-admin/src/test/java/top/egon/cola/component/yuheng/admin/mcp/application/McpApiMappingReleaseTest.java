package top.egon.cola.component.yuheng.admin.mcp.application;

import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import top.egon.cola.component.common.core.exception.CommonException;
import top.egon.cola.component.tianshu.api.client.DdcSiCatalogClient;
import top.egon.cola.component.tianshu.model.si.DdcSiDependencyManifestVO;
import top.egon.cola.component.tianshu.model.si.DdcSiKindEnum;
import top.egon.cola.component.tianshu.model.si.DdcSiLifecycleStatusEnum;
import top.egon.cola.component.tianshu.model.si.DdcSiResourceDescriptor;
import top.egon.cola.component.yuheng.admin.mcp.domain.exception.McpValidationException;
import top.egon.cola.component.yuheng.admin.mcp.service.McpNativeSiReader;
import top.egon.cola.component.yuheng.admin.mcp.service.McpPublishedSiResolver;
import top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpApiMappingContentBO;
import top.egon.cola.component.yuheng.admin.shared.controller.GatewayAdminExceptionHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class McpApiMappingReleaseTest {
    private static final String API_DIGEST = "a".repeat(64);
    private static final String SI_DIGEST = "b".repeat(64);

    @ParameterizedTest
    @CsvSource({
            "UNAUTHENTICATED, 401, YUHENG_MCP_SI_AUTHENTICATION_INVALID",
            "PERMISSION_DENIED, 403, YUHENG_MCP_SI_ACCESS_DENIED",
            "FAILED_PRECONDITION, 422, YUHENG_MCP_SI_VERSION_REJECTED",
            "UNAVAILABLE, 503, YUHENG_MCP_SI_UNAVAILABLE",
            "DEADLINE_EXCEEDED, 503, YUHENG_MCP_SI_UNAVAILABLE"
    })
    void nativeResolutionErrorsKeepTheirRestStatusWithoutExposingProviderDetails(
            Status.Code code, int httpStatus, String responseCode) throws Exception {
        var pinned = siReference(exactSiSnapshot());
        var reader = mock(McpNativeSiReader.class);
        when(reader.callerAppCode()).thenReturn("yuheng-reader");
        when(reader.resolveExecutionResource(7L, pinned.bizCode(), pinned.namespaceCode(), pinned.envCode(),
                pinned.ownerAppCode(), "yuheng-reader", DdcSiKindEnum.MCP, pinned.resourceKey(), 3L))
                .thenThrow(Status.fromCode(code).withDescription("private provider diagnostic").asRuntimeException());

        assertThatThrownBy(() -> new McpPublishedSiResolver(reader).resolveClosure(7L, pinned))
                .isInstanceOfSatisfying(CommonException.class, error -> {
                    var response = new GatewayAdminExceptionHandler().common(error);
                    assertThat(response.getStatusCode().value()).isEqualTo(httpStatus);
                    assertThat(response.getBody().code()).isEqualTo(responseCode);
                    assertThat(response.getBody().message()).doesNotContain("private provider diagnostic");
                });
    }

    @Test
    void keepsTheExactSourceAndPublishedSiIdentitiesInDraftRoundTrip() {
        var mapping = McpApiMappingContentBO.fromContent(
                content(source(), server("inventory"), List.of("catalog:read", "audit:read")));

        assertThat(mapping.sourceOperation().definitionDigest()).isEqualTo(API_DIGEST);
        assertThat(mapping.siServer().resourceId()).isEqualTo("81");
        assertThat(mapping.siServer().versionId()).isEqualTo("82");
        assertThat(mapping.siServer().versionNo()).isEqualTo("3");
        assertThat(mapping.siServer().digestSha256()).isEqualTo(SI_DIGEST);
        assertThat(mapping.siServer().ownerAppCode()).isEqualTo("inventory");
        assertThat(mapping.content().get("additionalPermissions")).isEqualTo(List.of("audit:read", "catalog:read"));
        assertThat(McpApiMappingContentBO.fromContent(mapping.content())).isEqualTo(mapping);
    }

    @Test
    void mappingCanOnlyAddRestrictionsAndCannotSupplyASchemaOrChangeTheSourceShape() {
        var mapping = content(source(), server("inventory"), List.of());
        mapping.put("inputSchema", Map.of("type", "object"));
        assertThatIllegalArgumentException().isThrownBy(() -> McpApiMappingContentBO.fromContent(mapping));
        mapping.remove("inputSchema");
        mapping.put("disabled", "false");
        assertThatIllegalArgumentException().isThrownBy(() -> McpApiMappingContentBO.fromContent(mapping));
        mapping.put("disabled", false);
        var override = new LinkedHashMap<>(source());
        override.put("definitionDigest", "current");
        mapping.put("sourceOperation", override);
        assertThatIllegalArgumentException().isThrownBy(() -> McpApiMappingContentBO.fromContent(mapping));
    }

    @Test
    void absentPinAndNoncanonicalIdentifiersCannotSelectAnActiveVersion() {
        var unpinned = new LinkedHashMap<>(server("inventory"));
        unpinned.remove("versionNo");
        assertThatIllegalArgumentException().isThrownBy(() ->
                McpApiMappingContentBO.fromContent(content(source(), unpinned, List.of())));
        unpinned.put("versionNo", "03");
        assertThatIllegalArgumentException().isThrownBy(() ->
                McpApiMappingContentBO.fromContent(content(source(), unpinned, List.of())));
        unpinned.put("versionNo", "9223372036854775808");
        assertThatIllegalArgumentException().isThrownBy(() ->
                McpApiMappingContentBO.fromContent(content(source(), unpinned, List.of())));
    }

    @Test
    void onlyImmutableInputsRoundTripAndOwnerIdentityParticipatesInEquality() {
        var callerPermissions = new ArrayList<>(List.of("audit:read"));
        var server = new LinkedHashMap<>(server("inventory"));
        var first = McpApiMappingContentBO.fromContent(content(source(), server, callerPermissions));
        callerPermissions.clear();
        server.put("ownerAppCode", "reporting");

        assertThat(first.additionalPermissions()).containsExactly("audit:read");
        assertThat(first.siServer().ownerAppCode()).isEqualTo("inventory");
        assertThat(McpApiMappingContentBO.fromContent(content(source(), server, List.of("audit:read"))))
                .isNotEqualTo(first);
    }

    @Test
    void resolvesOnlyThePinnedSiCoordinateAndReturnsItsVerifiedClosureDigest() throws Exception {
        var data = exactSiSnapshot();
        DdcSiCatalogClient catalog = mock(DdcSiCatalogClient.class);
        when(catalog.resolveExecutionResource(7, "commerce", "tooling", "prod", "inventory",
                "yuheng-reader", DdcSiKindEnum.MCP, "inventory-mcp", 3L)).thenReturn(Optional.of(data));

        String closure = new McpPublishedSiResolver(catalog).resolveClosure(7, "yuheng-reader", siReference(data));

        assertThat(closure).isEqualTo(data.getDependencyManifest().closureDigest());
        verify(catalog).resolveExecutionResource(7, "commerce", "tooling", "prod", "inventory",
                "yuheng-reader", DdcSiKindEnum.MCP, "inventory-mcp", 3L);
        verifyNoMoreInteractions(catalog);
    }

    @ParameterizedTest
    @MethodSource("invalidSiSnapshots")
    void rejectsChangedSiFactsWithoutFallingBackToCurrentVersion(
            UnaryOperator<DdcSiResourceDescriptor> modify) throws Exception {
        var data = exactSiSnapshot();
        var pinned = siReference(data);
        DdcSiCatalogClient catalog = mock(DdcSiCatalogClient.class);
        when(catalog.resolveExecutionResource(7, "commerce", "tooling", "prod", "inventory",
                "yuheng-reader", DdcSiKindEnum.MCP, "inventory-mcp", 3L))
                .thenReturn(Optional.of(modify.apply(data)));

        assertThatThrownBy(() -> new McpPublishedSiResolver(catalog).resolveClosure(7, "yuheng-reader", pinned))
                .isInstanceOfSatisfying(McpValidationException.class, failure ->
                        assertThat(failure.code()).isEqualTo("YUHENG_MCP_API_MAPPING_SI_MISMATCH"));
        verify(catalog).resolveExecutionResource(7, "commerce", "tooling", "prod", "inventory",
                "yuheng-reader", DdcSiKindEnum.MCP, "inventory-mcp", 3L);
        verifyNoMoreInteractions(catalog);
    }

    private static Stream<UnaryOperator<DdcSiResourceDescriptor>> invalidSiSnapshots() {
        return Stream.of(
                data -> data.setOwnerAppCode("other-app"),
                data -> data.setEnvCode("dev"),
                data -> data.setTenantId(8L),
                data -> data.setResolvedVersionId(83L),
                data -> data.setResolvedVersionNo(4L),
                data -> data.setSpecJson("{\"protocolVersion\":\"changed\"}"),
                data -> data.setDependencyManifest(null),
                data -> data.setStatus(DdcSiLifecycleStatusEnum.ARCHIVED));
    }

    private static DdcSiResourceDescriptor exactSiSnapshot() throws Exception {
        String body = "{\"protocolVersion\":\"2025-11-25\"}";
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
        var root = new DdcSiDependencyManifestVO.VersionIdentityVO("7", "81", "82", "3",
                DdcSiKindEnum.MCP, "prod", digest);
        return new DdcSiResourceDescriptor()
                .setTenantId(7L).setBizCode("commerce").setNamespaceCode("tooling").setEnvCode("prod")
                .setOwnerAppCode("inventory").setResourceKey("inventory-mcp").setKind(DdcSiKindEnum.MCP)
                .setResourceId(81L).setResolvedVersionId(82L).setResolvedVersionNo(3L).setActiveVersionNo(5)
                .setStatus(DdcSiLifecycleStatusEnum.PUBLISHED).setSpecJson(body).setDigestSha256(digest)
                .setDependencyManifest(DdcSiDependencyManifestVO.create(root, null, List.of(), List.of()));
    }

    private static McpApiMappingContentBO.SiServerVersionVO siReference(DdcSiResourceDescriptor data) {
        return new McpApiMappingContentBO.SiServerVersionVO("81", "82", "3", data.getDigestSha256(),
                "commerce", "tooling", "prod", "inventory", "inventory-mcp");
    }

    private static Map<String, Object> content(Map<String, Object> source, Map<String, Object> server,
            List<?> permissions) {
        var value = new LinkedHashMap<String, Object>();
        value.put("sourceOperation", source);
        value.put("siServer", server);
        value.put("additionalPermissions", permissions);
        value.put("minimumRiskLevel", "HIGH");
        value.put("disabled", false);
        return value;
    }

    private static Map<String, Object> source() {
        return Map.of("operationId", "51", "operationKey", "inventory.getItem", "definitionDigest", API_DIGEST);
    }

    private static Map<String, Object> server(String owner) {
        return Map.of("resourceId", "81", "versionId", "82", "versionNo", "3", "digestSha256", SI_DIGEST,
                "bizCode", "commerce", "namespaceCode", "tooling", "envCode", "prod", "ownerAppCode", owner,
                "resourceKey", "inventory-mcp");
    }
}
