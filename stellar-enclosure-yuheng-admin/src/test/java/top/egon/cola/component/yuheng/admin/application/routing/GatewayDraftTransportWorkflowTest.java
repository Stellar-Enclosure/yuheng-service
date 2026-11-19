package top.egon.cola.component.yuheng.admin.routing.service;

import top.egon.cola.component.common.id.snowflake.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import com.fasterxml.jackson.databind.json.JsonMapper;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.BindingVO;
import top.egon.cola.component.yuheng.admin.exposure.repository.GatewayUrlExposureRepository.PublicationDomainVO;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.AuthorizationBinding;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.BackendReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.BindingResolver;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.GatewayReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.GrantReference;
import top.egon.cola.component.yuheng.admin.exposure.service.GatewayHttpRouteCompiler.PublicationBinding;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.egon.cola.component.yuheng.admin.shared.repository.IdempotencyRepository;
import top.egon.cola.component.yuheng.admin.catalog.repository.GatewayCatalogRepository;
import top.egon.cola.component.yuheng.admin.shared.domain.AdminActor;
import top.egon.cola.component.yuheng.admin.observability.repository.GatewayAuditLogRepository;
import top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayDraftBO;
import top.egon.cola.component.yuheng.admin.routing.repository.GatewayDraftJpaRepository;
import top.egon.cola.component.yuheng.admin.routing.repository.GatewayDraftRepository;
import top.egon.cola.component.yuheng.admin.shared.controller.GatewayAdminExceptionHandler;
import top.egon.cola.component.yuheng.admin.routing.controller.GatewayDraftController;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import top.egon.cola.component.yuheng.admin.catalog.domain.bo.GatewayOperationBO;
import top.egon.cola.component.yuheng.admin.catalog.domain.bo.GatewayOperationDefinitionBO;
import top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayRouteDraftBO;

class GatewayDraftTransportWorkflowTest {

    @BeforeAll
    static void bindTheProcessWideEngine() {
        SnowflakeIdGenerator.initialize(0L, Duration.ofMillis(5));
    }

    @BeforeEach
    void establishTrustedTenant() { MDC.put("tenantId", "7"); }

    @AfterEach
    void clearTenant() { MDC.remove("tenantId"); }

    private static final Instant NOW =
            Instant.parse("2026-07-30T08:00:00Z");

    @Test
    void previewsAndImportsYamlThroughDraftApi() throws Exception {
        Fixture fixture = fixture();
        String yaml = """
                egon:
                  yuheng:
                    route-spec:
                      schema-version: 1
                      gateway-group: "810"
                      routes:
                        - id: route-1
                          operation-id: operation-1
                          predicates: [Host=ai.example.com, Method=POST, Path=/v1/orders]
                          access-zones: [PUBLIC]
                """;
        String payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "yaml", yaml, "expectedRevision", 0, "idempotencyKey", "idem-1",
                "changeReason", "import routes", "adoptExisting", false, "replaceManaged", false));
        String base = "/api/v1/yuheng/admin/yuheng-groups/810/draft";

        fixture.mockMvc.perform(post(base + "/routes/yaml/preview")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created[0]").value("route-1"));
        fixture.mockMvc.perform(post(base + "/routes/yaml")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));
        fixture.mockMvc.perform(get(base))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[0].content.yuhengRouteSource").value("YAML"))
                .andExpect(jsonPath("$.routes[0].content.pathPattern").value("/v1/orders"));
        fixture.mockMvc.perform(post(base + "/validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void preservesAnEditableLegacyOpenAiPolicyAndRejectsItsNativePublication()
            throws Exception {
        Fixture fixture = fixture();

        fixture.mockMvc.perform(put(
                        "/api/v1/yuheng/admin/yuheng-groups/810"
                                + "/draft/routes/route-1"
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operationId": "operation-1",
                                  "content": {
                                    "host": "ai.example.com",
                                    "listener": "PUBLIC",
                                    "method": "POST",
                                    "path": "/v1/chat/completions",
                                    "transportPolicy": {
                                      "profile": "OPENAI_HTTP",
                                      "transportProtocol": "HTTP",
                                      "requestBodyMode": "STREAMING",
                                      "responseMode": "AUTO_STREAM",
                                      "maxRequestBodyBytes": 536870912,
                                      "connectTimeoutMs": 10000,
                                      "responseHeaderTimeoutMs": 120000,
                                      "streamIdleTimeoutMs": 90000,
                                      "totalTimeoutMs": 1800000,
                                      "bodyLogEnabled": false,
                                      "retryEnabled": false,
                                      "futureOption": false
                                    }
                                  },
                                  "enabled": true,
                                  "expectedRevision": 0,
                                  "idempotencyKey": "idem-1",
                                  "changeReason": "configure OpenAI route"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId").value("route-1"));

        fixture.mockMvc.perform(get(
                        "/api/v1/yuheng/admin/yuheng-groups/810/draft"
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[0].content.httpMethod")
                        .value("POST"))
                .andExpect(jsonPath("$.routes[0].content.pathPattern")
                        .value("/v1/chat/completions"))
                .andExpect(jsonPath("$.routes[0].content.accessZones[0]")
                        .value("PUBLIC"))
                .andExpect(jsonPath(
                        "$.routes[0].content.transportPolicy.profile"
                ).value("OPENAI_HTTP"))
                .andExpect(jsonPath(
                        "$.routes[0].content.transportPolicy.bodyLogEnabled"
                ).value(false))
                .andExpect(jsonPath(
                        "$.routes[0].content.transportPolicy.retryEnabled"
                ).value(false))
                .andExpect(jsonPath(
                        "$.routes[0].content.transportPolicy.futureOption"
                ).value(false))
                .andExpect(jsonPath("$.routes[0].content.listener")
                        .doesNotExist())
                .andExpect(jsonPath("$.routes[0].content.model")
                        .doesNotExist())
                .andExpect(jsonPath("$.routes[0].content.tokenLimit")
                        .doesNotExist());

        fixture.mockMvc.perform(post(
                        "/api/v1/yuheng/admin/yuheng-groups/810"
                                + "/draft/validate"
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].code").value("TRANSPORT_OPTION_RETIRED"));
    }

    @Test
    void reportsMissingHostThroughTheManagementWorkflow()
            throws Exception {
        Fixture fixture = fixture();

        fixture.mockMvc.perform(put(
                        "/api/v1/yuheng/admin/yuheng-groups/810"
                                + "/draft/routes/route-1"
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operationId": "operation-1",
                                  "content": {
                                    "listener": "PUBLIC",
                                    "method": "POST",
                                    "path": "/v1/**"
                                  },
                                  "enabled": true,
                                  "expectedRevision": 0,
                                  "idempotencyKey": "idem-1",
                                  "changeReason": "import legacy route"
                                }
                                """))
                .andExpect(status().isOk());

        fixture.mockMvc.perform(post(
                        "/api/v1/yuheng/admin/yuheng-groups/810"
                                + "/draft/validate"
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].path")
                        .value("routes.route-1.host"))
                .andExpect(jsonPath("$.errors[0].code")
                        .value("ROUTE_HOST_REQUIRED"));
    }

    private Fixture fixture() {
        GatewayDraftJpaRepository drafts = mock(GatewayDraftJpaRepository.class);
        GatewayDraftRepository store = mock(GatewayDraftRepository.class);
        GatewayCatalogRepository catalog = mock(GatewayCatalogRepository.class);
        IdempotencyRepository idempotency = mock(IdempotencyRepository.class);
        GatewayAuditLogRepository audits =
                mock(GatewayAuditLogRepository.class);
        GatewayDraftBO draft = new GatewayDraftBO(
                "810",
                "admin",
                NOW
        );
        AtomicReference<top.egon.cola.component.yuheng.admin.routing.domain.bo.GatewayRouteDraftBO> route =
                new AtomicReference<>();
        when(drafts.findById("810")).thenReturn(Optional.of(draft));
        when(drafts.save(any(GatewayDraftBO.class))).thenAnswer(invocation -> {
            GatewayDraftBO saved = invocation.getArgument(0);
            return saved.setRevision(saved.getRevision() + 1);
        });
        when(store.routes("810")).thenAnswer(ignored ->
                route.get() == null ? List.of() : List.of(route.get()));
        when(store.policies("810")).thenReturn(List.of());
        doAnswer(invocation -> {
            route.set(invocation.getArgument(0));
            return null;
        }).when(store).upsertRoute(any());
        when(idempotency.find("YUHENG_DRAFT", "810", "idem-1"))
                .thenReturn(Optional.empty());
        when(catalog.findOperation("operation-1"))
                .thenReturn(Optional.of(operation()));
        when(catalog.loadDefinitions("operation-1"))
                .thenReturn(List.of(definition()));
        var compiler = new GatewayHttpRouteCompiler(JsonMapper.builder().build());
        var binding = new PublicationBinding(7, 810, "route-1", "operation-1", "local", "default", "fixture", 1,
                new PublicationDomainVO("cluster", "edge", "1",
                        List.of(new BindingVO("edge-system", "public-apps", "https-api")), true),
                new GatewayReference("edge-system", "public-apps", "gateway-uid", "https-api", true, true),
                "yuheng-routes", Set.of("ai.example.com"), Set.of("POST"), Set.of(),
                List.of(new BackendReference("apps-prod", "orders", "service-uid", "2", 80, 8080, 100, "HTTP_1_1",
                        new GrantReference("apps-prod", "allow-orders", "grant-uid", "4", "yuheng-routes", "orders"),
                        "a".repeat(64), Set.of("pod-uid"), true)),
                new AuthorizationBinding(GatewayHttpRouteCompiler.AUTHORIZATION_PROFILE, "https://ai.example.com/v1",
                        "1", "b".repeat(64), true, List.of()));
        var factory = new StaticListableBeanFactory();
        BindingResolver resolver = (owner, group, routeId, operationId) ->
                owner == 7 && group == 810 && routeId.equals("route-1") && operationId.equals("operation-1")
                        ? Optional.of(binding) : Optional.empty();
        factory.addBean("gatewayHttpRouteBindingResolver", resolver);
        var exposures = mock(GatewayUrlExposureRepository.class);
        when(exposures.findOwnedRoute(7L, 810L, "route-1")).thenReturn(Optional.empty());
        GatewayDraftService service = new GatewayDraftService(
                drafts,
                store,
                catalog,
                idempotency,
                audits,
                Clock.fixed(NOW, ZoneOffset.UTC), compiler, factory.getBeanProvider(BindingResolver.class), exposures);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                        new GatewayDraftController(service)
                )
                .setCustomArgumentResolvers(new FixedActorResolver())
                .setControllerAdvice(new GatewayAdminExceptionHandler())
                .build();
        return new Fixture(mockMvc);
    }

    private top.egon.cola.component.yuheng.admin.catalog.domain.bo.GatewayOperationBO operation() {
        return new top.egon.cola.component.yuheng.admin.catalog.domain.bo.GatewayOperationBO(
                "operation-1",
                "application-1",
                "interface-1",
                "openai",
                "HTTP",
                "POST /v1/**",
                true,
                Map.of(
                        "env", "local",
                        "namespace", "default",
                        "serviceName", "openai-compatible-provider",
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
                "c".repeat(64),
                "openai",
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

    private record Fixture(MockMvc mockMvc) {
    }

    private static final class FixedActorResolver
            implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return AdminActor.class.equals(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(
                MethodParameter parameter,
                ModelAndViewContainer mavContainer,
                NativeWebRequest webRequest,
                WebDataBinderFactory binderFactory) {
            return new AdminActor(
                    "admin",
                    top.egon.cola.component.yuheng.admin.shared.domain.enums.AdminActorTypeEnum.USER,
                    Set.of("yuheng:drafts:write"),
                    Set.of()
            );
        }
    }
}
