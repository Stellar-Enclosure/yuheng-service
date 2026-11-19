package top.egon.cola.component.yuheng.admin.routing.service;

import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.access.AccessDeniedException;
import top.egon.cola.platform.tianquan.jianshen.starter.permission.PermissionExecutionGuard;
import top.egon.cola.component.yuheng.admin.routing.domain.dto.GatewayDraftMutationControlDTO;
import top.egon.cola.component.yuheng.admin.shared.domain.exception.GatewayAdminIdempotencyConflictException;
import top.egon.cola.component.yuheng.admin.shared.repository.IdempotencyRepository;
import top.egon.cola.component.yuheng.admin.shared.domain.RequestAuditContext;
import top.egon.cola.component.yuheng.admin.catalog.repository.GatewayCatalogRepository;
import top.egon.cola.component.yuheng.admin.catalog.domain.vo.GatewayCurrentOperationDefinitionVO;
import top.egon.cola.component.yuheng.admin.mcp.domain.bo.McpApiMappingContentBO;
import top.egon.cola.component.yuheng.admin.routing.domain.dto.GatewayPolicyMutationDTO;
import top.egon.cola.component.yuheng.admin.shared.domain.AdminActor;
import top.egon.cola.component.yuheng.admin.observability.repository.GatewayAuditLogRepository;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler;
import top.egon.cola.component.yuheng.admin.routing.domain.vo.GatewayDraftValidationIssueVO;
import top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayDraftBO;
import top.egon.cola.component.yuheng.admin.routing.repository.GatewayDraftRepository;
import top.egon.cola.component.yuheng.admin.routing.repository.GatewayDraftJpaRepository;
import top.egon.cola.component.yuheng.admin.rule.service.GatewayRuleCanonicalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import top.egon.cola.component.yuheng.admin.catalog.domain.bo.GatewayOperationBO;
import top.egon.cola.component.yuheng.admin.catalog.domain.bo.GatewayOperationDefinitionBO;
import top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayPolicyDraftBO;
import top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayRouteDraftBO;
import top.egon.cola.component.yuheng.admin.shared.domain.bo.IdempotencyBO;

class GatewayDraftServiceTest {

    @BeforeAll
    static void bindTheProcessWideEngine() {
        SnowflakeIdGenerator.initialize(0L, Duration.ofMillis(5));
    }

    private static final Instant NOW =
            Instant.parse("2026-07-30T08:00:00Z");

    @Test
    void yamlPreviewAndImportOnlyReplaceManagedRoutes() {
        Fixture fixture = fixture();
        AtomicReference<IdempotencyBO> saved = new AtomicReference<>();
        when(fixture.idempotency.find("YUHENG_DRAFT", "group-1", "yaml-idempotency"))
                .thenAnswer(ignored -> Optional.ofNullable(saved.get()));
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(fixture.idempotency).save(any());
        Map<String, Object> old = Map.of("host", "api.example.com", "httpMethod", "GET",
                "pathPattern", "/old", "accessZones", List.of("PUBLIC"),
                "priority", 0, "yuhengRouteSource", "YAML");
        Map<String, Object> manual = Map.of("host", "api.example.com", "httpMethod", "GET",
                "pathPattern", "/manual", "accessZones", List.of("PUBLIC"), "priority", 0);
        when(fixture.store.routes("group-1")).thenReturn(List.of(
                new GatewayRouteDraftBO("group-1", "old", "operation-1", old, true, NOW, "admin"),
                new GatewayRouteDraftBO("group-1", "manual", "operation-1", manual, true, NOW, "admin")));
        var request = yamlRequest(false, true);

        var preview = fixture.service.previewYamlRoutes("group-1", request);
        assertThat(preview.created()).containsExactly("orders");
        assertThat(preview.deleted()).containsExactly("old");
        assertThat(preview.updated()).isEmpty();
        assertThat(preview.changes()).containsKeys("orders", "old");
        verify(fixture.store, never()).upsertRoute(any());

        var result = fixture.service.importYamlRoutes("group-1", request, actor(), request());
        assertThat(result.revision()).isEqualTo(1);
        assertThat(fixture.service.importYamlRoutes("group-1", request, actor(), request()).replayed()).isTrue();
        ArgumentCaptor<GatewayRouteDraftBO> route = ArgumentCaptor.forClass(GatewayRouteDraftBO.class);
        verify(fixture.store).upsertRoute(route.capture());
        assertThat(route.getValue().getContent()).containsEntry("yuhengRouteSource", "YAML");
        verify(fixture.store).deleteRoute("group-1", "old");
        verify(fixture.store, never()).deleteRoute("group-1", "manual");
    }

    @Test
    void yamlImportRequiresExplicitAdoptionAndManualApiCannotChangeOwnedRoutes() {
        Fixture fixture = fixture();
        Map<String, Object> manual = Map.of("host", "api.example.com", "httpMethod", "POST",
                "pathPattern", "/api/biz/orders", "accessZones", List.of("PUBLIC"), "priority", 0);
        when(fixture.store.routes("group-1")).thenReturn(List.of(
                new GatewayRouteDraftBO("group-1", "orders", "operation-1", manual, true, NOW, "admin")));
        assertThatThrownBy(() -> fixture.service.previewYamlRoutes("group-1", yamlRequest(false, false)))
                .hasMessageContaining("not YAML-managed");
        assertThat(fixture.service.previewYamlRoutes("group-1", yamlRequest(true, false)).updated())
                .containsExactly("orders");

        Map<String, Object> managed = new LinkedHashMap<>(manual);
        managed.put("yuhengRouteSource", "YAML");
        when(fixture.store.routes("group-1")).thenReturn(List.of(
                new GatewayRouteDraftBO("group-1", "orders", "operation-1", managed, true, NOW, "admin")));
        assertThatThrownBy(() -> fixture.service.putRoute("group-1", "orders", mutation(manual), actor(), request()))
                .hasMessageContaining("YAML-managed");
        verify(fixture.store, never()).upsertRoute(any());
    }

    private top.egon.cola.component.yuheng.admin.routing.domain.dto.GatewayYamlRoutesRequestDTO yamlRequest(
            boolean adopt, boolean replace) {
        return new top.egon.cola.component.yuheng.admin.routing.domain.dto.GatewayYamlRoutesRequestDTO("""
                egon:
                  yuheng:
                    route-spec:
                      schema-version: 1
                      gateway-group: group-1
                      routes:
                        - id: orders
                          operation-id: operation-1
                          predicates: [Host=api.example.com, Method=POST, Path=/api/biz/orders]
                          access-zones: [PUBLIC]
                """, 0, "yaml-idempotency", "import routes", adopt, replace);
    }

    @Test
    void normalizesBeforeDigestAndStorage() {
        Fixture fixture = fixture();
        AtomicReference<top.egon.cola.component.yuheng.admin.shared.domain.bo.IdempotencyBO> saved =
                new AtomicReference<>();
        when(fixture.idempotency.find(
                "YUHENG_DRAFT",
                "group-1",
                "idem-1"
        )).thenAnswer(ignored -> Optional.ofNullable(saved.get()));
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(fixture.idempotency).save(any());
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("host", "ai.example.com");
        legacy.put("listener", "PUBLIC");
        legacy.put("method", "POST");
        legacy.put("path", "/v1/**");
        legacy.put("customExtension", Map.of("enabled", false));

        fixture.service.putRoute(
                "group-1",
                "route-1",
                mutation(legacy),
                actor(),
                request()
        );
        top.egon.cola.component.yuheng.admin.routing.domain.vo.GatewayDraftMutationResultVO replay = fixture.service.putRoute(
                "group-1",
                "route-1",
                mutation(Map.of(
                        "host", "ai.example.com",
                        "httpMethod", "POST",
                        "pathPattern", "/v1/**",
                        "accessZones", List.of("PUBLIC"),
                        "priority", 0,
                        "customExtension", Map.of("enabled", false)
                )),
                actor(),
                request()
        );

        ArgumentCaptor<top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayRouteDraftBO> route =
                ArgumentCaptor.forClass(top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayRouteDraftBO.class);
        verify(fixture.store).upsertRoute(route.capture());
        assertThat(route.getValue().getContent()).isEqualTo(Map.of(
                "host", "ai.example.com",
                "httpMethod", "POST",
                "pathPattern", "/v1/**",
                "accessZones", List.of("PUBLIC"),
                "priority", 0,
                "customExtension", Map.of("enabled", false)
        ));
        assertThat(replay.replayed()).isTrue();
    }

    @Test
    void replaysARequestStoredWithThePreUpgradeRawCommandDigest() {
        Fixture fixture = fixture();
        Map<String, Object> legacy = legacyRoute("/v1/**");
        top.egon.cola.component.yuheng.admin.routing.domain.dto.GatewayRouteMutationDTO command = mutation(legacy);
        when(fixture.idempotency.find(
                "YUHENG_DRAFT",
                "group-1",
                "idem-1"
        )).thenReturn(Optional.of(legacyRecord(
                legacyDigest("route-1", command)
        )));

        top.egon.cola.component.yuheng.admin.routing.domain.vo.GatewayDraftMutationResultVO result = fixture.service.putRoute(
                "group-1",
                "route-1",
                command,
                actor(),
                request()
        );

        assertThat(result).isEqualTo(
                new top.egon.cola.component.yuheng.admin.routing.domain.vo.GatewayDraftMutationResultVO(4L, "route-1", true)
        );
        verify(fixture.store, never()).upsertRoute(any());
    }

    @Test
    void conflictsWhenRawCommandDiffersFromThePreUpgradeDigest() {
        Fixture fixture = fixture();
        top.egon.cola.component.yuheng.admin.routing.domain.dto.GatewayRouteMutationDTO original = mutation(
                legacyRoute("/v1/**")
        );
        when(fixture.idempotency.find(
                "YUHENG_DRAFT",
                "group-1",
                "idem-1"
        )).thenReturn(Optional.of(legacyRecord(
                legacyDigest("route-1", original)
        )));

        assertThatThrownBy(() -> fixture.service.putRoute(
                "group-1",
                "route-1",
                mutation(legacyRoute("/v2/**")),
                actor(),
                request()
        )).isInstanceOf(GatewayAdminIdempotencyConflictException.class);

        verify(fixture.store, never()).upsertRoute(any());
    }

    @Test
    void preservesExplicitNullPolicyContentValues() {
        Fixture fixture = fixture();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("authenticationMode", "NONE");
        content.put("credentialRecoveryProviderId", null);

        fixture.service.putPolicy(
                "group-1",
                "policy-1",
                new top.egon.cola.component.yuheng.admin.routing.domain.dto.GatewayPolicyMutationDTO(
                        "SECURITY",
                        "OPERATION",
                        content,
                        true,
                        0L,
                        "policy-idem-1",
                        "configure public policy"
                ),
                actor(),
                request()
        );

        ArgumentCaptor<top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayPolicyDraftBO> policy =
                ArgumentCaptor.forClass(
                        top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayPolicyDraftBO.class
                );
        verify(fixture.store).upsertPolicy(policy.capture());
        assertThat(policy.getValue().getContent())
                .containsEntry("authenticationMode", "NONE")
                .containsKey("credentialRecoveryProviderId");
        assertThat(policy.getValue().getContent()
                .get("credentialRecoveryProviderId")).isNull();
    }

    @Test
    void ordinaryDraftWriteAccessCannotCreateAnMcpMappingWhenTheMcpGuardIsUnavailable() {
        Fixture fixture = fixture();
        when(fixture.catalog.loadCurrentOperationDefinitions("group-1")).thenReturn(List.of(mappingSource()));

        assertThatThrownBy(() -> fixture.service.putPolicy("group-1", "inventory-tool",
                mappingPolicy(mappingContent()), actor(), request()))
                .isInstanceOf(AccessDeniedException.class);

        verify(fixture.store, never()).upsertPolicy(any());
    }

    @Test
    void ordinaryDraftPermissionsCannotReplaceOrDeleteAnExistingMcpMapping() {
        Fixture fixture = fixture();
        when(fixture.store.policies("group-1")).thenReturn(List.of(new GatewayPolicyDraftBO(
                "group-1", "inventory-tool", McpApiMappingContentBO.POLICY_TYPE, "OPERATION",
                mappingContent(), true, NOW, "admin")));
        var unrelatedPolicy = new GatewayPolicyMutationDTO("SECURITY", "OPERATION", Map.of(), true,
                0L, "replace-mapping", "try replace mapping");
        var delete = new GatewayDraftMutationControlDTO(0L, "delete-mapping", "try delete mapping");

        assertThatThrownBy(() -> fixture.service.putPolicy("group-1", "inventory-tool",
                unrelatedPolicy, actor(), request())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> fixture.service.deletePolicy("group-1", "inventory-tool",
                delete, actor(), request())).isInstanceOf(AccessDeniedException.class);
        verify(fixture.store, never()).upsertPolicy(any());
        verify(fixture.store, never()).deletePolicy("group-1", "inventory-tool");
    }

    @Test
    void rejectsAnUnregisteredMappingSourceBeforeAnyPolicyMutation() {
        var guard = mock(PermissionExecutionGuard.class);
        Fixture fixture = fixture(guard);

        assertThatThrownBy(() -> fixture.service.putPolicy("group-1", "inventory-tool",
                mappingPolicy(mappingContent()), actor(), request()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("YUHENG_MCP_API_MAPPING_SOURCE_UNAVAILABLE");

        verify(fixture.store, never()).upsertPolicy(any());
    }

    @Test
    void storesTheCanonicalMappingAndItsOwnDraftRevision() {
        var guard = mock(PermissionExecutionGuard.class);
        Fixture fixture = fixture(guard);
        when(fixture.catalog.loadCurrentOperationDefinitions("group-1")).thenReturn(List.of(mappingSource()));
        var payload = mappingContent();
        payload.put("additionalPermissions", List.of("catalog:read", "audit:read"));

        var result = fixture.service.putPolicy("group-1", "inventory-tool",
                mappingPolicy(payload), actor(), request());

        verify(guard).requireExecution("yuheng.api.mcpToolAdmin.putOverride");
        var policy = ArgumentCaptor.forClass(GatewayPolicyDraftBO.class);
        verify(fixture.store).upsertPolicy(policy.capture());
        assertThat(policy.getValue().getPolicyType()).isEqualTo(McpApiMappingContentBO.POLICY_TYPE);
        assertThat(policy.getValue().getContent().get("additionalPermissions"))
                .isEqualTo(List.of("audit:read", "catalog:read"));
        assertThat(result.revision()).isEqualTo(1L);
    }

    @Test
    void refusesAStaleSourceDigestInStoredMappingValidation() {
        Fixture fixture = fixture();
        when(fixture.catalog.loadCurrentOperationDefinitions("group-1")).thenReturn(List.of(
                new GatewayCurrentOperationDefinitionVO(mappingSource().operation(),
                        mappingSource().definition().setDefinitionSha256("c".repeat(64)))));
        when(fixture.store.policies("group-1")).thenReturn(List.of(new GatewayPolicyDraftBO(
                "group-1", "inventory-tool", McpApiMappingContentBO.POLICY_TYPE, "OPERATION",
                mappingContent(), true, NOW, "admin")));

        var report = fixture.service.validate("group-1");

        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).extracting(GatewayDraftValidationIssueVO::code)
                .contains("YUHENG_MCP_API_MAPPING_SOURCE_UNAVAILABLE");
    }

    private Map<String, Object> mappingContent() {
        return new LinkedHashMap<>(Map.of(
                "sourceOperation", Map.of("operationId", "51", "operationKey", "inventory.getItem",
                        "definitionDigest", "a".repeat(64)),
                "siServer", Map.of("resourceId", "81", "versionId", "82", "versionNo", "3",
                        "digestSha256", "b".repeat(64), "bizCode", "commerce", "namespaceCode", "tooling",
                        "envCode", "prod", "ownerAppCode", "inventory", "resourceKey", "inventory-mcp"),
                "additionalPermissions", List.of(), "disabled", false));
    }

    private GatewayCurrentOperationDefinitionVO mappingSource() {
        var operation = operation().setId("51").setOperationKey("inventory.getItem").setSourceType("OPENAPI31");
        var definition = definition().setOperationId("51").setDefinitionSha256("a".repeat(64))
                .setAttributes(Map.of("mcpExposure", Map.of("registerMcp", true, "mcpServerCode", "inventory-mcp")));
        return new GatewayCurrentOperationDefinitionVO(operation, definition);
    }

    private GatewayPolicyMutationDTO mappingPolicy(Map<String, Object> content) {
        return new GatewayPolicyMutationDTO(McpApiMappingContentBO.POLICY_TYPE, "OPERATION",
                content, true, 0L, "mapping-put", "publish inventory tool");
    }

    @Test
    void validatesAndDiffsPublicDraftWithoutInventingAPlatformBinding() {
        Fixture fixture = fixture();
        when(fixture.store.routes("group-1")).thenReturn(List.of(
                new GatewayRouteDraftBO("group-1", "public-on", "operation-1",
                        legacyRoute("/v1/orders"), true, NOW, "admin"),
                new GatewayRouteDraftBO("group-1", "public-off", "operation-1",
                        legacyRoute("/v1/orders-old"), false, NOW, "admin")));
        var report = fixture.service.validate("group-1");
        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).filteredOn(issue -> issue.code().equals("YUHENG_CILIUM_PUBLICATION_BINDING_REQUIRED"))
                .extracting(GatewayDraftValidationIssueVO::path).containsExactly("routes.public-on");
        var diff = fixture.service.diff("group-1");
        assertThat(diff.capabilityProfileId()).isEqualTo(GatewayHttpRouteCompiler.CAPABILITY_PROFILE_ID);
        assertThat(diff.draftSha256()).isEqualTo(report.draftSha256());
        assertThat(diff.exposureIssues()).containsAll(report.errors());
        verify(fixture.store, never()).upsertRoute(any());
    }

    @Test
    void reportsRouteTransportErrorsAtDraftFieldPaths() {
        Fixture fixture = fixture();
        when(fixture.store.routes("group-1")).thenReturn(List.of(
                new top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayRouteDraftBO(
                        "group-1",
                        "route-1",
                        "operation-1",
                        Map.of(
                                "listener", "PUBLIC",
                                "method", "POST",
                                "path", "/v1/**",
                                "transportPolicy", Map.of(
                                        "connectTimeoutMs", 99
                                )
                        ),
                        true,
                        NOW,
                        "admin"
                )
        ));
        when(fixture.store.policies("group-1")).thenReturn(List.of());

        top.egon.cola.component.yuheng.admin.routing.domain.vo.GatewayDraftValidationReportVO report =
                fixture.service.validate("group-1");

        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).contains(
                new top.egon.cola.component.yuheng.admin.routing.domain.vo.GatewayDraftValidationIssueVO(
                        "routes.route-1.host",
                        "ROUTE_HOST_REQUIRED",
                        "Host is required"
                ),
                new top.egon.cola.component.yuheng.admin.routing.domain.vo.GatewayDraftValidationIssueVO(
                        "routes.route-1.transportPolicy.connectTimeoutMs",
                        "TRANSPORT_VALUE_OUT_OF_RANGE",
                        "connectTimeoutMs must be an integer from 100 to 60000"
                )
        );
    }

    @Test
    void reportsNonStringTextFieldsFromJacksonBoundContent() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> content = new ObjectMapper().readValue(
                """
                        {
                          "host": {"tenant": "x"},
                          "listener": "PUBLIC",
                          "method": false,
                          "path": "/v1/**",
                          "futureFalse": false,
                          "futureNumber": 7,
                          "futureNull": null
                        }
                        """,
                new TypeReference<>() {
                }
        );
        when(fixture.store.routes("group-1")).thenReturn(List.of(
                new top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayRouteDraftBO(
                        "group-1",
                        "route-1",
                        "operation-1",
                        content,
                        true,
                        NOW,
                        "admin"
                )
        ));
        when(fixture.store.policies("group-1")).thenReturn(List.of());

        top.egon.cola.component.yuheng.admin.routing.domain.vo.GatewayDraftValidationReportVO report =
                fixture.service.validate("group-1");

        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).contains(
                new top.egon.cola.component.yuheng.admin.routing.domain.vo.GatewayDraftValidationIssueVO(
                        "routes.route-1.host",
                        "ROUTE_HOST_INVALID",
                        "Host must be a string"
                ),
                new top.egon.cola.component.yuheng.admin.routing.domain.vo.GatewayDraftValidationIssueVO(
                        "routes.route-1.httpMethod",
                        "ROUTE_METHOD_INVALID",
                        "HTTP Method must be a string"
                )
        );
    }

    private Fixture fixture() {
        return fixture(null);
    }

    private Fixture fixture(PermissionExecutionGuard guard) {
        GatewayDraftJpaRepository drafts = mock(GatewayDraftJpaRepository.class);
        GatewayDraftRepository store = mock(GatewayDraftRepository.class);
        GatewayCatalogRepository catalog = mock(GatewayCatalogRepository.class);
        IdempotencyRepository idempotency = mock(IdempotencyRepository.class);
        GatewayAuditLogRepository audits =
                mock(GatewayAuditLogRepository.class);
        GatewayDraftBO draft = new GatewayDraftBO(
                "group-1",
                "admin",
                NOW
        );
        when(drafts.findById("group-1")).thenReturn(Optional.of(draft));
        when(drafts.save(any(GatewayDraftBO.class))).thenAnswer(invocation -> {
            GatewayDraftBO saved = invocation.getArgument(0);
            return saved.setRevision(saved.getRevision() + 1);
        });
        when(catalog.findOperation("operation-1"))
                .thenReturn(Optional.of(operation()));
        when(catalog.loadDefinitions("operation-1"))
                .thenReturn(List.of(definition()));
        GatewayDraftService service = new GatewayDraftService(
                drafts,
                store,
                catalog,
                idempotency,
                audits, Clock.fixed(NOW, ZoneOffset.UTC), null, null, null,
                new StaticListableBeanFactory(guard == null ? Map.of() : Map.of("permissionExecutionGuard", guard))
                        .getBeanProvider(PermissionExecutionGuard.class));
        return new Fixture(service, store, idempotency, catalog);
    }

    private top.egon.cola.component.yuheng.admin.routing.domain.dto.GatewayRouteMutationDTO mutation(
            Map<String, Object> content) {
        return new top.egon.cola.component.yuheng.admin.routing.domain.dto.GatewayRouteMutationDTO(
                "operation-1",
                content,
                true,
                0L,
                "idem-1",
                "configure route"
        );
    }

    private Map<String, Object> legacyRoute(String path) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("host", "ai.example.com");
        content.put("listener", "PUBLIC");
        content.put("method", "POST");
        content.put("path", path);
        return content;
    }

    private String legacyDigest(
            String routeId,
            top.egon.cola.component.yuheng.admin.routing.domain.dto.GatewayRouteMutationDTO command) {
        GatewayRuleCanonicalizer canonicalizer =
                new GatewayRuleCanonicalizer();
        return GatewayRuleCanonicalizer.sha256(
                canonicalizer.canonicalBytes(Map.of(
                        "action", "PUT_ROUTE",
                        "routeId", routeId,
                        "command", command
                ))
        );
    }

    private top.egon.cola.component.yuheng.admin.shared.domain.bo.IdempotencyBO legacyRecord(String digest) {
        return new top.egon.cola.component.yuheng.admin.shared.domain.bo.IdempotencyBO(
                "YUHENG_DRAFT",
                "group-1",
                "idem-1",
                digest,
                "route-1",
                Map.of("revision", 4L, "resourceId", "route-1"),
                NOW,
                NOW.plusSeconds(604_800)
        );
    }

    private top.egon.cola.component.yuheng.admin.catalog.domain.bo.GatewayOperationBO operation() {
        return new top.egon.cola.component.yuheng.admin.catalog.domain.bo.GatewayOperationBO(
                "operation-1",
                "application-1",
                "interface-1",
                "orders",
                "HTTP",
                "POST /v1/orders",
                true,
                Map.of(
                        "env", "local",
                        "namespace", "default",
                        "serviceName", "orders",
                        "group", "default",
                        "version", "v1",
                        "transport", "http"
                ),
                "MANUAL",
                "ACTIVE",
                "definition-1",
                1L,
                NOW,
                NOW
        );
    }

    private top.egon.cola.component.yuheng.admin.catalog.domain.bo.GatewayOperationDefinitionBO definition() {
        return new top.egon.cola.component.yuheng.admin.catalog.domain.bo.GatewayOperationDefinitionBO(
                "definition-1",
                "operation-1",
                1L,
                "sha256",
                "orders",
                List.of(),
                Map.of("type", "object"),
                Map.of("type", "object"),
                List.of(),
                null,
                Map.of("responseMode", "TRANSPARENT"),
                true,
                NOW,
                "admin"
        );
    }

    private AdminActor actor() {
        return new AdminActor(
                "admin",
                top.egon.cola.component.yuheng.admin.shared.domain.enums.AdminActorTypeEnum.USER,
                Set.of(),
                Set.of()
        );
    }

    private RequestAuditContext request() {
        return new RequestAuditContext("request-1", "trace-1");
    }

    private record Fixture(
            GatewayDraftService service,
            GatewayDraftRepository store,
            IdempotencyRepository idempotency,
            GatewayCatalogRepository catalog
    ) {
    }
}
